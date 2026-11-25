package routing

import api.GhostApi
import config.AppConfiguration
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import parser.PostContentParser
import shared.GhostWebhookPayload
import shared.ServerResponse
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val SIGNATURE_TOLERANCE_MILLIS = 5 * 60 * 1000L

private val payloadJson = Json { ignoreUnknownKeys = true }

private val alreadyDeliveredResponse = ServerResponse(HttpStatusCode.OK, """{ "message": "Webhook already delivered, skipping processing." }""")

fun Route.akexorcistWebhook(
    appConfig: AppConfiguration,
    ghostApi: GhostApi,
    postContentParser: PostContentParser,
    clock: () -> Long = System::currentTimeMillis,
) {
    val rebuildLock = Mutex()
    val recentSignatures = RecentSignatures(windowMillis = SIGNATURE_TOLERANCE_MILLIS)
    post("/webhook/akexorcist") {
        val remoteIp = call.request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: call.request.origin.remoteHost
        val rawBody = call.receive<ByteArray>()
        val signatureHeader = call.request.headers["X-Ghost-Signature"]
        val nowMillis = clock()
        val decision = verifyAkexorcistWebhook(
            remoteIp = remoteIp,
            signatureHeader = signatureHeader,
            rawBody = rawBody,
            appConfig = appConfig,
            nowMillis = nowMillis,
        )
        if (decision is WebhookDecision.Accepted) {
            val signature = parseGhostSignature(signatureHeader)
            if (signature == null || !recentSignatures.isFirstDelivery(signature.hex, signature.signedAtMillis, nowMillis)) {
                println("""{ "message": "Ignoring a webhook that was already delivered" }""")
                call.respond(alreadyDeliveredResponse.status, alreadyDeliveredResponse.message)
                return@post
            }
            call.application.launch(Dispatchers.IO) {
                rebuildLock.withLock {
                    try {
                        rebuildIndex(appConfig, ghostApi, postContentParser)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        RebuildResult.Failure(e.message ?: e::class.simpleName.orEmpty()).log()
                    }
                }
            }
        }
        call.respond(decision.response.status, decision.response.message)
    }
}

internal fun verifyAkexorcistWebhook(
    remoteIp: String,
    signatureHeader: String?,
    rawBody: ByteArray,
    appConfig: AppConfiguration,
    nowMillis: Long,
): WebhookDecision {
    println("""{ "message": "Incoming webhook from $remoteIp" }""")
    if (remoteIp !in appConfig.getAllowedIps()) {
        return WebhookDecision.Rejected(ServerResponse(HttpStatusCode.Forbidden, """{ "message": "Invalid IP" }"""))
    }
    val secret = appConfig.getWebhookSecret()
    if (secret.isEmpty()) {
        println("""{ "message": "Rejecting webhook: webhook secret is not configured" }""")
        return WebhookDecision.Rejected(ServerResponse(HttpStatusCode.Unauthorized, """{ "message": "Invalid signature" }"""))
    }
    if (!isValidGhostSignature(signatureHeader, rawBody, secret, nowMillis)) {
        return WebhookDecision.Rejected(ServerResponse(HttpStatusCode.Unauthorized, """{ "message": "Invalid signature" }"""))
    }
    val payload = try {
        payloadJson.decodeFromString<GhostWebhookPayload>(rawBody.decodeToString())
    } catch (e: Exception) {
        println("""{ "message": "Rejecting webhook: malformed payload: ${e.message}" }""")
        return WebhookDecision.Rejected(ServerResponse(HttpStatusCode.BadRequest, """{ "message": "Invalid webhook payload." }"""))
    }
    if (payload.post?.current?.id == appConfig.getIndexPostId()) {
        println("""{ "message": "Skipping webhook processing for index post" }""")
        return WebhookDecision.Skipped(ServerResponse(HttpStatusCode.OK, """{ "message": "Webhook received for index post, skipping processing." }"""))
    }
    return WebhookDecision.Accepted(ServerResponse(HttpStatusCode.Accepted, """{ "message": "Webhook accepted, updating index." }"""))
}

internal fun isValidGhostSignature(
    signatureHeader: String?,
    rawBody: ByteArray,
    secret: String,
    nowMillis: Long,
): Boolean {
    val signature = parseGhostSignature(signatureHeader) ?: return false
    if (kotlin.math.abs(nowMillis - signature.signedAtMillis) > SIGNATURE_TOLERANCE_MILLIS) return false
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    mac.update(rawBody)
    mac.update(signature.signedAtMillis.toString().toByteArray())
    val expected = mac.doFinal().joinToString("") { "%02x".format(it) }
    return MessageDigest.isEqual(expected.toByteArray(), signature.hex.lowercase().toByteArray())
}

internal data class GhostSignature(val hex: String, val signedAtMillis: Long)

internal fun parseGhostSignature(signatureHeader: String?): GhostSignature? {
    if (signatureHeader == null) return null
    val parts = signatureHeader.split(",").associate { part ->
        part.trim().substringBefore("=") to part.trim().substringAfter("=", "")
    }
    val hex = parts["sha256"]?.takeIf { it.isNotEmpty() } ?: return null
    val signedAtMillis = parts["t"]?.toLongOrNull() ?: return null
    return GhostSignature(hex, signedAtMillis)
}

internal suspend fun rebuildIndex(
    appConfig: AppConfiguration,
    ghostApi: GhostApi,
    postContentParser: PostContentParser,
): RebuildResult {
    println("""{ "message": "Getting all posts" }""")
    val posts = ghostApi.getAllPosts()
    if (posts.isEmpty()) {
        return RebuildResult.Failure("no posts fetched, refusing to overwrite the index").also { it.log() }
    }

    println("""{ "message": "Converting into index article format" }""")
    val grouped = postContentParser.groupAndSortPostsByTag(posts, appConfig.getTagWhitelist())
    val lexical = postContentParser.formatPostsAsLexical(grouped, appConfig.getTagWhitelist())

    println("""{ "message": "Updating index article" }""")
    val updatedAt = ghostApi.getPostById(appConfig.getIndexPostId())?.updatedAt
        ?: return RebuildResult.Failure("post not found or missing updated_at").also { it.log() }
    return try {
        ghostApi.updatePostHtml(appConfig.getIndexPostId(), lexical, updatedAt)
        println("""{ "message": "Update index successfully" }""")
        RebuildResult.Success
    } catch (e: Exception) {
        RebuildResult.Failure(e.message ?: e::class.simpleName.orEmpty()).also { it.log() }
    }
}

sealed interface WebhookDecision {
    val response: ServerResponse

    data class Accepted(override val response: ServerResponse) : WebhookDecision
    data class Skipped(override val response: ServerResponse) : WebhookDecision
    data class Rejected(override val response: ServerResponse) : WebhookDecision
}

sealed interface RebuildResult {
    data object Success : RebuildResult
    data class Failure(val reason: String) : RebuildResult {
        fun log() = println("""{ "message": "Unable to update the post: $reason" }""")
    }
}
