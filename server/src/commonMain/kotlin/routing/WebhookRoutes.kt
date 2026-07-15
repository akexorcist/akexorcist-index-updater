package routing

import api.GhostApi
import config.AppConfiguration
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import parser.PostContentParser
import shared.GhostWebhookPayload
import shared.ServerResponse

fun Route.akexorcistWebhook(
    appConfig: AppConfiguration,
    ghostApi: GhostApi,
    postContentParser: PostContentParser
) {
    post("/webhook/akexorcist") {
        val remoteIp = call.request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
            ?: call.request.origin.remoteHost
        val credential = call.request.queryParameters["credential"]
        val payload = try {
            val body = call.receive<GhostWebhookPayload>()
            WebhookPayload.Parsed(body.post?.current?.id)
        } catch (e: Exception) {
            println("""{ "message": "Unable to parse webhook body: ${e.message}" }""")
            WebhookPayload.Malformed
        }
        val response = processAkexorcistWebhook(remoteIp, credential, payload, appConfig, ghostApi, postContentParser)
        call.respond(response.status, response.message)
    }
}

internal suspend fun processAkexorcistWebhook(
    remoteIp: String,
    credential: String?,
    payload: WebhookPayload,
    appConfig: AppConfiguration,
    ghostApi: GhostApi,
    postContentParser: PostContentParser,
): ServerResponse {
    println("""{ "message": "Incoming webhook from $remoteIp" }""")
    if (remoteIp !in appConfig.getAllowedIps()) {
        return ServerResponse(HttpStatusCode.Forbidden, """{ "message": "Invalid IP" }""")
    }
    val expectedCredential = appConfig.getVerificationPassphrase()
    if (expectedCredential.isEmpty()) {
        println("""{ "message": "Rejecting webhook: verification passphrase is not configured" }""")
        return ServerResponse(HttpStatusCode.Unauthorized, """{ "message": "Invalid credential" }""")
    }
    if (credential != expectedCredential) {
        return ServerResponse(HttpStatusCode.Unauthorized, """{ "message": "Invalid credential" }""")
    }
    val postId = when (payload) {
        is WebhookPayload.Malformed -> {
            println("""{ "message": "Rejecting webhook: malformed payload" }""")
            return ServerResponse(HttpStatusCode.BadRequest, """{ "message": "Invalid webhook payload." }""")
        }
        is WebhookPayload.Parsed -> payload.postId
    }
    if (postId != null && postId == appConfig.getIndexPostId()) {
        println("""{ "message": "Skipping webhook processing for index post" }""")
        return ServerResponse(HttpStatusCode.OK, """{ "message": "Webhook received for index post, skipping processing." }""")
    }

    println("""{ "message": "Getting all posts" }""")
    val posts = ghostApi.getAllPosts()

    println("""{ "message": "Converting into index article format" }""")
    val grouped = postContentParser.groupAndSortPostsByTag(posts, appConfig.getTagWhitelist())
    val lexical = postContentParser.formatPostsAsLexical(grouped, appConfig.getTagWhitelist())

    println("""{ "message": "Updating index article" }""")
    val post = ghostApi.getPostById(appConfig.getIndexPostId())
    return post?.updatedAt?.let { updatedAt ->
        try {
            ghostApi.updatePostHtml(appConfig.getIndexPostId(), lexical, updatedAt)
            println("""{ "message": "Update index successfully" }""")
            ServerResponse(HttpStatusCode.OK, """{ "message": "Update index successfully." }""")
        } catch (e: Exception) {
            println("""{ "message": "Unable to update the post: ${e.message}" }""")
            ServerResponse(HttpStatusCode.InternalServerError, """{ "message": "Unable to update the post: ${e.message}" }""")
        }
    } ?: run {
        println("""{ "message": "Unable to update the post: post not found or missing updated_at" }""")
        ServerResponse(HttpStatusCode.InternalServerError, """{ "message": "Unable to update the post: post not found or missing updated_at." }""")
    }
}

sealed interface WebhookPayload {
    data class Parsed(val postId: String?) : WebhookPayload
    data object Malformed : WebhookPayload
}
