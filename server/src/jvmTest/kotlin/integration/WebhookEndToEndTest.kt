package integration

import api.DefaultGhostApi
import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import parser.PostContentParser
import shared.GhostWebhookPayload
import shared.GhostWebhookPost
import shared.GhostWebhookPostCurrent
import shared.configureRouting
import java.util.concurrent.TimeUnit

class WebhookEndToEndTest : FunSpec({

    test("a published post webhook drives a full index rebuild against Ghost") {
        val indexPostId = "index-1"
        val contentApiKey = "content-key-123"
        val passphrase = "webhook-secret"
        val allowedIp = "127.0.0.1"

        val allPostsBody = """
            {"posts":[
              {"id":"post-99","title":"Kotlin Coroutines","url":"https://blog.test/kotlin-coroutines","tags":[{"name":"Kotlin"}],"created_at":"2024-06-01T10:00:00Z"}
            ]}
        """.trimIndent()
        val indexPostBody = """
            {"posts":[
              {"id":"$indexPostId","title":"Article Index","url":"https://blog.test/index","created_at":"2024-01-01T00:00:00Z","updated_at":"2024-06-03T10:00:00Z"}
            ]}
        """.trimIndent()

        val ghost = MockWebServer()
        ghost.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val json = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                return when {
                    request.method == "PUT" && path.contains("/admin/posts/$indexPostId/") -> json.setBody("""{"posts":[{"id":"$indexPostId"}]}""")
                    request.method == "GET" && path.contains("/content/posts/$indexPostId/") -> json.setBody(indexPostBody)
                    request.method == "GET" && path.contains("/content/posts/") -> json.setBody(allPostsBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val config = object : AppConfiguration {
            override fun getGhostApiUrl() = ghost.url("/ghost/api").toString().trimEnd('/')
            override fun getIndexPostId() = indexPostId
            override fun getAllowedIps() = setOf(allowedIp)
            override fun getTagWhitelist() = setOf("Kotlin")
            override fun getAdminApiKey() = "admin-key-id:0123456789abcdef"
            override fun getContentApiKey() = contentApiKey
            override fun getVerificationPassphrase() = passphrase
        }

        val ghostClient = HttpClient(ClientCIO) {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val server = embeddedServer(ServerCIO, port = 0, host = allowedIp) {
            install(ServerContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            configureRouting(config, DefaultGhostApi(config, ghostClient), PostContentParser())
        }
        val client = HttpClient(ClientCIO) {
            install(ClientContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val payload = GhostWebhookPayload(
            event = "post.published",
            post = GhostWebhookPost(current = GhostWebhookPostCurrent(id = "post-99"), previous = null),
        )

        try {
            ghost.start()
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().first().port

            val response = client.post("http://$allowedIp:$port/webhook/akexorcist?credential=$passphrase") {
                header("X-Forwarded-For", allowedIp)
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(payload))
            }

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldBe """{ "message": "Update index successfully." }"""

            val getAllPostsPath = ghost.takeRequest(5, TimeUnit.SECONDS).shouldNotBeNull().let {
                it.method shouldBe "GET"
                it.path.shouldNotBeNull()
            }
            getAllPostsPath shouldContain "/content/posts/"
            getAllPostsPath shouldContain "key=$contentApiKey"
            getAllPostsPath shouldContain "fields=id,title,url,created_at"
            getAllPostsPath shouldContain "limit=all"
            getAllPostsPath shouldContain "include=tags"

            val getIndexPostPath = ghost.takeRequest(5, TimeUnit.SECONDS).shouldNotBeNull().let {
                it.method shouldBe "GET"
                it.path.shouldNotBeNull()
            }
            getIndexPostPath shouldContain "/content/posts/$indexPostId/"
            getIndexPostPath shouldContain "key=$contentApiKey"

            val updateIndex = ghost.takeRequest(5, TimeUnit.SECONDS).shouldNotBeNull()
            updateIndex.method shouldBe "PUT"
            updateIndex.path.shouldNotBeNull() shouldContain "/admin/posts/$indexPostId/"
            updateIndex.getHeader("Authorization").shouldNotBeNull() shouldStartWith "Ghost "

            val updatedPost = Json.parseToJsonElement(updateIndex.body.readUtf8())
                .jsonObject["posts"]!!.jsonArray.first().jsonObject
            updatedPost["updated_at"]!!.jsonPrimitive.content shouldBe "2024-06-03T10:00:00Z"
            val lexical = updatedPost["lexical"]!!.jsonPrimitive.content
            lexical shouldContain """"text":"Kotlin""""
            lexical shouldContain "Kotlin Coroutines"
            lexical shouldContain "https://blog.test/kotlin-coroutines"

            ghost.requestCount shouldBe 3
        } finally {
            client.close()
            server.stop()
            ghostClient.close()
            ghost.shutdown()
        }
    }
})
