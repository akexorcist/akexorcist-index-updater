package routing

import api.GhostApi
import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import parser.PostContentParser
import shared.Post
import shared.Tag

class WebhookRoutesTest : FunSpec({
    val postContentParser = PostContentParser()
    val validIp = "1.2.3.4"
    val invalidIp = "9.9.9.9"
    val secret = "webhook-secret"
    val now = 1_700_000_000_000L
    val tagWhitelist = setOf("Kotlin")
    val body = """{"post":{"current":{"id":"999"}}}"""
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

    fun config(webhookSecret: String = secret) = mockk<AppConfiguration> {
        coEvery { getAllowedIps() } returns setOf(validIp)
        coEvery { getWebhookSecret() } returns webhookSecret
        coEvery { getIndexPostId() } returns "index-post-id"
        coEvery { getTagWhitelist() } returns tagWhitelist
    }

    fun verify(
        remoteIp: String = validIp,
        rawBody: String = body,
        signatureHeader: String? = ghostSignatureHeader(rawBody, secret, now),
        appConfig: AppConfiguration = config(),
        nowMillis: Long = now,
    ) = verifyAkexorcistWebhook(remoteIp, signatureHeader, rawBody.toByteArray(), appConfig, nowMillis)

    test("accepts a correctly signed webhook from an allowed IP with 202") {
        val decision = verify()
        decision.shouldBeInstanceOf<WebhookDecision.Accepted>()
        decision.response.status shouldBe HttpStatusCode.Accepted
    }

    test("returns Forbidden for IP not in allowed list") {
        val decision = verify(remoteIp = invalidIp)
        decision.shouldBeInstanceOf<WebhookDecision.Rejected>()
        decision.response.status shouldBe HttpStatusCode.Forbidden
    }

    test("checks the IP before the signature") {
        val decision = verify(remoteIp = invalidIp, signatureHeader = null)
        decision.response.status shouldBe HttpStatusCode.Forbidden
    }

    test("returns Unauthorized when the signature header is missing") {
        val decision = verify(signatureHeader = null)
        decision.shouldBeInstanceOf<WebhookDecision.Rejected>()
        decision.response.status shouldBe HttpStatusCode.Unauthorized
        decision.response.message shouldBe """{ "message": "Invalid signature" }"""
    }

    test("returns Unauthorized when the signature was made with another secret") {
        val decision = verify(signatureHeader = ghostSignatureHeader(body, "other-secret", now))
        decision.response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("returns Unauthorized when the body was changed after signing") {
        val decision = verify(
            rawBody = """{"post":{"current":{"id":"1000"}}}""",
            signatureHeader = ghostSignatureHeader(body, secret, now),
        )
        decision.response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("returns Unauthorized when the signature timestamp is older than five minutes") {
        val signedAt = now - 5 * 60 * 1000L - 1
        val decision = verify(signatureHeader = ghostSignatureHeader(body, secret, signedAt))
        decision.response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("accepts a signature timestamp within five minutes") {
        val signedAt = now - 4 * 60 * 1000L
        val decision = verify(signatureHeader = ghostSignatureHeader(body, secret, signedAt))
        decision.shouldBeInstanceOf<WebhookDecision.Accepted>()
    }

    test("returns Unauthorized for a header without a timestamp") {
        val signature = ghostSignatureHeader(body, secret, now).substringBefore(",")
        val decision = verify(signatureHeader = signature)
        decision.response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("fails closed when the webhook secret is not configured") {
        val decision = verify(
            signatureHeader = ghostSignatureHeader(body, "any-secret", now),
            appConfig = config(webhookSecret = ""),
        )
        decision.response.status shouldBe HttpStatusCode.Unauthorized
    }

    test("returns BadRequest for a correctly signed but malformed payload") {
        val decision = verify(rawBody = "not json")
        decision.shouldBeInstanceOf<WebhookDecision.Rejected>()
        decision.response.status shouldBe HttpStatusCode.BadRequest
        decision.response.message shouldBe """{ "message": "Invalid webhook payload." }"""
    }

    test("verifies the signature over the raw UTF-8 bytes of a Thai payload") {
        val thaiBody = """{"post":{"current":{"id":"999","title":"สารบัญบทความ"}}}"""
        val decision = verify(rawBody = thaiBody, signatureHeader = ghostSignatureHeader(thaiBody, secret, now))
        decision.shouldBeInstanceOf<WebhookDecision.Accepted>()
    }

    test("accepts a signature produced by Ghost's own webhook-trigger algorithm") {
        val ghostBody = """{"event":"post.published.edited","post":{"current":{"id":"999","title":"สารบัญบทความ"}}}"""
        val ghostHeader = "sha256=b916d993b7ce1e0d97a89e7e64010356a7a7e97b9b77ba67080cc9451b3c4069, t=1700000000000"
        isValidGhostSignature(ghostHeader, ghostBody.toByteArray(), "test-vector-secret", 1_700_000_000_000L) shouldBe true
    }

    test("skips processing when the post ID matches the index post ID") {
        val indexBody = """{"post":{"current":{"id":"index-post-id"}}}"""
        val decision = verify(rawBody = indexBody, signatureHeader = ghostSignatureHeader(indexBody, secret, now))
        decision.shouldBeInstanceOf<WebhookDecision.Skipped>()
        decision.response.status shouldBe HttpStatusCode.OK
        decision.response.message shouldBe """{ "message": "Webhook received for index post, skipping processing." }"""
    }

    test("rebuildIndex updates the index post on success") {
        val ghostApi = mockk<GhostApi> {
            coEvery { getAllPosts() } returns posts
            coEvery { getPostById(any()) } returns posts[0]
            coEvery { updatePostHtml(any(), any(), any()) } returns Unit
        }
        rebuildIndex(config(), ghostApi, postContentParser) shouldBe RebuildResult.Success
        coVerify(exactly = 1) { ghostApi.updatePostHtml("index-post-id", any(), "2024-06-01T10:00:00Z") }
    }

    test("rebuildIndex refuses to overwrite the index when no posts were fetched") {
        val ghostApi = mockk<GhostApi> {
            coEvery { getAllPosts() } returns emptyList()
        }
        val result = rebuildIndex(config(), ghostApi, postContentParser)
        result.shouldBeInstanceOf<RebuildResult.Failure>()
        coVerify(exactly = 0) { ghostApi.updatePostHtml(any(), any(), any()) }
    }

    test("rebuildIndex fails when the index post is missing updatedAt") {
        val ghostApi = mockk<GhostApi> {
            coEvery { getAllPosts() } returns posts
            coEvery { getPostById(any()) } returns posts[0].copy(updatedAt = null)
        }
        val result = rebuildIndex(config(), ghostApi, postContentParser)
        result shouldBe RebuildResult.Failure("post not found or missing updated_at")
        coVerify(exactly = 0) { ghostApi.updatePostHtml(any(), any(), any()) }
    }

    test("rebuildIndex reports failure when updatePostHtml throws") {
        val ghostApi = mockk<GhostApi> {
            coEvery { getAllPosts() } returns posts
            coEvery { getPostById(any()) } returns posts[0]
            coEvery { updatePostHtml(any(), any(), any()) } throws RuntimeException("fail")
        }
        rebuildIndex(config(), ghostApi, postContentParser) shouldBe RebuildResult.Failure("fail")
    }
})
