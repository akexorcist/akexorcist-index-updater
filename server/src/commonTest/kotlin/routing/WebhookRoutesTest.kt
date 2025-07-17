package routing

import api.GhostApi
import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
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
                appConfig = appConfig,
                ghostApi = ghostApi,
                postContentParser = postContentParser
            )
        }
        response.status shouldBe HttpStatusCode.InternalServerError
        response.message shouldBe """{ "message": "Unable to update the post: fail" }"""
    }
}) 