package api

import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json

class DefaultGhostApiTest : FunSpec({

    val jsonContentType = headersOf(HttpHeaders.ContentType, "application/json")

    fun config() = mockk<AppConfiguration> {
        every { getGhostApiUrl() } returns "https://blog.test/ghost/api"
        every { getContentApiKey() } returns "content-key"
        every { getAdminApiKey() } returns "keyid:00aabb"
    }

    fun clientReturning(
        status: HttpStatusCode,
        body: String,
        onRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {},
    ): HttpClient {
        val engine = MockEngine { request ->
            onRequest(request)
            respond(content = body, status = status, headers = jsonContentType)
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    val postsBody = """
        {"posts":[
          {"id":"1","title":"Kotlin","url":"https://blog.test/kotlin","tags":[{"name":"Kotlin"}],"created_at":"2024-06-01T10:00:00Z"},
          {"id":"2","title":"Ktor","url":"https://blog.test/ktor","tags":[{"name":"Ktor"}],"created_at":"2024-06-02T10:00:00Z"}
        ]}
    """.trimIndent()

    val errorBody = """{"errors":[{"message":"Unknown Content API Key","type":"UnauthorizedError"}]}"""

    test("getAllPosts parses posts from a successful response") {
        val api = DefaultGhostApi(config(), clientReturning(HttpStatusCode.OK, postsBody))
        val posts = api.getAllPosts()
        posts shouldHaveSize 2
        posts.map { it.id } shouldBe listOf("1", "2")
        posts[0].tags.map { it.name } shouldBe listOf("Kotlin")
    }

    test("getAllPosts sends the content key and fields in the request URL") {
        var seenUrl = ""
        val api = DefaultGhostApi(config(), clientReturning(HttpStatusCode.OK, postsBody) { seenUrl = it.url.toString() })
        api.getAllPosts()
        seenUrl shouldContain "/content/posts/"
        seenUrl shouldContain "key=content-key"
        seenUrl shouldContain "limit=all"
    }

    test("getAllPosts returns empty list on a 401 error body instead of crashing") {
        val api = DefaultGhostApi(config(), clientReturning(HttpStatusCode.Unauthorized, errorBody))
        api.getAllPosts() shouldBe emptyList()
    }

    test("getPostById parses the first post from a successful response") {
        val singlePost = """{"posts":[{"id":"9","title":"Index","url":"https://blog.test/index","created_at":"2024-06-01T10:00:00Z","updated_at":"2024-06-03T10:00:00Z"}]}"""
        val api = DefaultGhostApi(config(), clientReturning(HttpStatusCode.OK, singlePost))
        val post = api.getPostById("9")
        post?.id shouldBe "9"
        post?.updatedAt shouldBe "2024-06-03T10:00:00Z"
    }

    test("getPostById returns null on a 401 error body") {
        val api = DefaultGhostApi(config(), clientReturning(HttpStatusCode.Unauthorized, errorBody))
        api.getPostById("9").shouldBeNull()
    }

    test("updatePostHtml sends a PUT to the admin endpoint with a Ghost JWT and lexical body") {
        var request: io.ktor.client.request.HttpRequestData? = null
        val api = DefaultGhostApi(config(), clientReturning(HttpStatusCode.OK, "{}") { request = it })
        api.updatePostHtml(postId = "9", lexical = "{\"root\":{}}", updatedAt = "2024-06-03T10:00:00Z")

        val req = request!!
        req.method shouldBe HttpMethod.Put
        req.url.toString() shouldContain "/admin/posts/9/"
        req.headers[HttpHeaders.Authorization]!! shouldStartWith "Ghost "
    }
})
