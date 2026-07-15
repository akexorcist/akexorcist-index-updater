package routing

import api.GhostApi
import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldNotThrowAny
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
    val tagWhitelist = setOf("Kotlin")
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

    test("returns Forbidden for IP not in allowed list") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = invalidIp,
                credential = "passphrase",
                payload = WebhookPayload.Parsed(null),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.Forbidden
    }

    test("returns Unauthorized when credential does not match configured passphrase") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = "wrong-passphrase",
                payload = WebhookPayload.Parsed(null),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.Unauthorized
        response.message shouldBe """{ "message": "Invalid credential" }"""
        coVerify(exactly = 0) { ghostApi.getAllPosts() }
        coVerify(exactly = 0) { ghostApi.updatePostHtml(any(), any(), any()) }
    }

    test("returns Unauthorized when credential is missing") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = null,
                payload = WebhookPayload.Parsed(null),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.Unauthorized
        response.message shouldBe """{ "message": "Invalid credential" }"""
        coVerify(exactly = 0) { ghostApi.updatePostHtml(any(), any(), any()) }
    }

    test("returns Unauthorized and fails closed when passphrase is not configured") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getVerificationPassphrase() } returns ""
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = "any-credential",
                payload = WebhookPayload.Parsed(null),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.Unauthorized
        response.message shouldBe """{ "message": "Invalid credential" }"""
        coVerify(exactly = 0) { ghostApi.getAllPosts() }
        coVerify(exactly = 0) { ghostApi.updatePostHtml(any(), any(), any()) }
    }

    test("returns BadRequest for a malformed payload after valid auth") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = "passphrase",
                payload = WebhookPayload.Malformed,
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.BadRequest
        response.message shouldBe """{ "message": "Invalid webhook payload." }"""
        coVerify(exactly = 0) { ghostApi.getAllPosts() }
        coVerify(exactly = 0) { ghostApi.updatePostHtml(any(), any(), any()) }
    }

    test("checks authorization before rejecting a malformed payload") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = invalidIp,
                credential = "passphrase",
                payload = WebhookPayload.Malformed,
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.Forbidden
    }

    test("returns InternalServerError if post is missing updatedAt") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getTagWhitelist() } returns tagWhitelist
            coEvery { getIndexPostId() } returns "1"
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi> {
            coEvery { getAllPosts() } returns posts
            coEvery { getPostById(any()) } returns Post(
                id = "1",
                title = "Kotlin Basics",
                url = "https://example.com/kotlin-basics",
                tags = listOf(Tag("Kotlin")),
                createdAt = "2024-06-01T10:00:00Z",
                updatedAt = null
            )
        }
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = "passphrase",
                payload = WebhookPayload.Parsed(null),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.InternalServerError
        response.message shouldBe """{ "message": "Unable to update the post: post not found or missing updated_at." }"""
    }

    test("returns OK for valid request and post with updatedAt") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getTagWhitelist() } returns tagWhitelist
            coEvery { getIndexPostId() } returns "1"
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi> {
            coEvery { getAllPosts() } returns posts
            coEvery { getPostById(any()) } returns posts[0]
            coEvery { updatePostHtml(any(), any(), any()) } returns Unit
        }
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = "passphrase",
                payload = WebhookPayload.Parsed(null),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.OK
        response.message shouldBe """{ "message": "Update index successfully." }"""
    }

    test("returns InternalServerError if GhostApi.updatePostHtml throws") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getTagWhitelist() } returns tagWhitelist
            coEvery { getIndexPostId() } returns "1"
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi> {
            coEvery { getAllPosts() } returns posts
            coEvery { getPostById(any()) } returns posts[0]
            coEvery { updatePostHtml(any(), any(), any()) } throws RuntimeException("fail")
        }
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = "passphrase",
                payload = WebhookPayload.Parsed(null),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.InternalServerError
        response.message shouldBe """{ "message": "Unable to update the post: fail" }"""
    }

    test("returns OK and skips processing when post ID matches index post ID") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getIndexPostId() } returns "index-post-id"
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val response = shouldNotThrowAny {
            processAkexorcistWebhook(
                remoteIp = validIp,
                credential = "passphrase",
                payload = WebhookPayload.Parsed("index-post-id"),
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.OK
        response.message shouldBe """{ "message": "Webhook received for index post, skipping processing." }"""
    }
}) 