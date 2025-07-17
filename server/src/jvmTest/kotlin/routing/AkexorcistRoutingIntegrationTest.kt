package routing

import api.GhostApi
import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.mockk
import parser.PostContentParser
import shared.Post
import shared.Tag
import kotlinx.serialization.json.Json
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText

class AkexorcistRoutingIntegrationTest : FunSpec({
    val validIp = "1.2.3.4"
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

    fun Application.testModule(
        appConfig: AppConfiguration,
        ghostApi: GhostApi,
        postContentParser: PostContentParser
    ) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        routing {
            akexorcistWebhook(appConfig, ghostApi, postContentParser)
        }
    }

    test("POST /webhook/akexorcist returns 200 OK for valid request") {
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
        val postContentParser = PostContentParser()
        testApplication {
            application { testModule(appConfig, ghostApi, postContentParser) }
            val response = client.post("/webhook/akexorcist?credential=passphrase") {
                header("X-Forwarded-For", validIp)
            }
            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{ "message": "Update index successfully." }"""
        }
    }

    test("POST /webhook/akexorcist returns 403 Forbidden for invalid IP") {
        val appConfig = mockk<AppConfiguration> {
            coEvery { getAllowedIps() } returns setOf(validIp)
            coEvery { getVerificationPassphrase() } returns "passphrase"
        }
        val ghostApi = mockk<GhostApi>(relaxed = true)
        val postContentParser = PostContentParser()
        testApplication {
            application { testModule(appConfig, ghostApi, postContentParser) }
            val response = client.post("/webhook/akexorcist?credential=passphrase") {
                header("X-Forwarded-For", "9.9.9.9")
            }
            response.status shouldBe HttpStatusCode.Forbidden
            response.bodyAsText() shouldBe """{ "message": "Invalid IP" }"""
        }
    }

    test("POST /webhook/akexorcist returns 500 InternalServerError if updatePostHtml throws") {
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
        val postContentParser = PostContentParser()
        testApplication {
            application { testModule(appConfig, ghostApi, postContentParser) }
            val response = client.post("/webhook/akexorcist?credential=passphrase") {
                header("X-Forwarded-For", validIp)
            }
            response.status shouldBe HttpStatusCode.InternalServerError
            response.bodyAsText() shouldBe """{ "message": "Unable to update the post: fail" }"""
        }
    }
}) 