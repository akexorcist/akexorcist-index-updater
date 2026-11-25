package routing

import api.GhostApi
import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import parser.PostContentParser
import shared.GhostWebhookPayload
import shared.GhostWebhookPost
import shared.GhostWebhookPostCurrent
import shared.Post
import shared.Tag

class AkexorcistRoutingIntegrationTest : FunSpec({
    val validIp = "1.2.3.4"
    val secret = "webhook-secret"
    val now = 1_700_000_000_000L
    val posts = listOf(
        Post(
            id = "1",
            title = "Kotlin Basics",
            url = "https://example.com/kotlin-basics",
            tags = listOf(Tag("Kotlin")),
            createdAt = "2024-06-01T10:00:00Z",
            updatedAt = "2024-06-01T10:00:00Z"
        )
    )

    fun payload(postId: String) = Json.encodeToString(
        GhostWebhookPayload(
            event = "post.published",
            post = GhostWebhookPost(current = GhostWebhookPostCurrent(id = postId), previous = null)
        )
    )

    val appConfig = mockk<AppConfiguration> {
        coEvery { getAllowedIps() } returns setOf(validIp)
        coEvery { getTagWhitelist() } returns setOf("Kotlin")
        coEvery { getIndexPostId() } returns "index-post-id"
        coEvery { getWebhookSecret() } returns secret
    }

    fun ghostApi() = mockk<GhostApi> {
        coEvery { getAllPosts() } returns posts
        coEvery { getPostById(any()) } returns posts[0]
        coEvery { updatePostHtml(any(), any(), any()) } returns Unit
    }

    fun Application.testModule(ghostApi: GhostApi) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        routing { akexorcistWebhook(appConfig, ghostApi, PostContentParser(), clock = { now }) }
    }

    suspend fun ApplicationTestBuilder.postWebhook(
        body: String,
        realIp: String? = validIp,
        signature: String? = ghostSignatureHeader(body, secret, now),
        forwardedFor: String? = null,
    ) = client.post("/webhook/akexorcist") {
        realIp?.let { header("X-Real-IP", it) }
        forwardedFor?.let { header("X-Forwarded-For", it) }
        signature?.let { header("X-Ghost-Signature", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    test("a signed webhook is answered with 202 and the index is rebuilt in the background") {
        val ghostApi = ghostApi()
        testApplication {
            application { testModule(ghostApi) }
            val response = postWebhook(payload("999"))
            response.status shouldBe HttpStatusCode.Accepted
            response.bodyAsText() shouldBe """{ "message": "Webhook accepted, updating index." }"""
            coVerify(timeout = 5_000, exactly = 1) { ghostApi.updatePostHtml("index-post-id", any(), any()) }
        }
    }

    test("a rebuild that crashes does not stop later webhooks from being processed") {
        val ghostApi = ghostApi()
        coEvery { ghostApi.getPostById(any()) } throws RuntimeException("network down") andThen posts[0]
        testApplication {
            application { testModule(ghostApi) }
            postWebhook(payload("998")).status shouldBe HttpStatusCode.Accepted
            coVerify(timeout = 5_000, exactly = 1) { ghostApi.getPostById(any()) }
            postWebhook(payload("999")).status shouldBe HttpStatusCode.Accepted
            coVerify(timeout = 5_000, exactly = 1) { ghostApi.updatePostHtml("index-post-id", any(), any()) }
        }
    }

    test("an unsigned webhook is rejected and nothing is rebuilt") {
        val ghostApi = ghostApi()
        testApplication {
            application { testModule(ghostApi) }
            val response = postWebhook(payload("999"), signature = null)
            response.status shouldBe HttpStatusCode.Unauthorized
            response.bodyAsText() shouldBe """{ "message": "Invalid signature" }"""
            coVerify(exactly = 0) { ghostApi.getAllPosts() }
        }
    }

    test("the old credential query parameter is no longer accepted") {
        val ghostApi = ghostApi()
        testApplication {
            application { testModule(ghostApi) }
            val response = client.post("/webhook/akexorcist?credential=$secret") {
                header("X-Real-IP", validIp)
                contentType(ContentType.Application.Json)
                setBody(payload("999"))
            }
            response.status shouldBe HttpStatusCode.Unauthorized
        }
    }

    test("returns 400 for a malformed body") {
        testApplication {
            application { testModule(ghostApi()) }
            val response = postWebhook("this is not a valid payload")
            response.status shouldBe HttpStatusCode.BadRequest
            response.bodyAsText() shouldBe """{ "message": "Invalid webhook payload." }"""
        }
    }

    test("returns 403 Forbidden for an IP outside the allow list") {
        testApplication {
            application { testModule(ghostApi()) }
            val response = postWebhook(payload("999"), realIp = "9.9.9.9")
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldBe """{ "message": "Invalid IP" }"""
        }
    }

    test("ignores a spoofed X-Forwarded-For when X-Real-IP is absent") {
        testApplication {
            application { testModule(ghostApi()) }
            val response = postWebhook(payload("999"), realIp = null, forwardedFor = validIp)
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("uses X-Real-IP over a spoofed X-Forwarded-For") {
        testApplication {
            application { testModule(ghostApi()) }
            val response = postWebhook(payload("999"), realIp = "9.9.9.9", forwardedFor = validIp)
            response.status shouldBe HttpStatusCode.Forbidden
        }
    }

    test("a replayed signed webhook is answered but does not rebuild the index again") {
        val ghostApi = ghostApi()
        testApplication {
            application { testModule(ghostApi) }
            val body = payload("999")
            postWebhook(body).status shouldBe HttpStatusCode.Accepted
            coVerify(timeout = 5_000, exactly = 1) { ghostApi.updatePostHtml("index-post-id", any(), any()) }
            val replay = postWebhook(body)
            replay.status shouldBe HttpStatusCode.OK
            replay.bodyAsText() shouldBe """{ "message": "Webhook already delivered, skipping processing." }"""
            coVerify(exactly = 1) { ghostApi.getAllPosts() }
        }
    }

    test("two different signed webhooks both rebuild the index") {
        val ghostApi = ghostApi()
        testApplication {
            application { testModule(ghostApi) }
            postWebhook(payload("111")).status shouldBe HttpStatusCode.Accepted
            postWebhook(payload("222")).status shouldBe HttpStatusCode.Accepted
            coVerify(timeout = 5_000, exactly = 2) { ghostApi.updatePostHtml("index-post-id", any(), any()) }
        }
    }

    test("a rejected request does not use up the signature of a later valid delivery") {
        val ghostApi = ghostApi()
        testApplication {
            application { testModule(ghostApi) }
            val body = payload("999")
            postWebhook(body, realIp = "9.9.9.9").status shouldBe HttpStatusCode.Forbidden
            postWebhook(body).status shouldBe HttpStatusCode.Accepted
            coVerify(timeout = 5_000, exactly = 1) { ghostApi.updatePostHtml("index-post-id", any(), any()) }
        }
    }

    test("skips processing when the webhook is for the index post itself") {
        val ghostApi = ghostApi()
        testApplication {
            application { testModule(ghostApi) }
            val response = postWebhook(payload("index-post-id"))
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{ "message": "Webhook received for index post, skipping processing." }"""
            coVerify(exactly = 0) { ghostApi.getAllPosts() }
        }
    }
})
