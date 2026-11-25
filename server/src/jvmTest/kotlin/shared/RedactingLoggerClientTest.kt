package shared

import api.DefaultGhostApi
import config.AppConfiguration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json

class RedactingLoggerClientTest : FunSpec({
    test("the Ghost client's request and response logs never contain the Content API key") {
        val contentKey = "f0e1d2c3b4a5968778695a4b3c"
        val logged = mutableListOf<String>()
        val config = mockk<AppConfiguration> {
            every { getGhostApiUrl() } returns "https://blog.test/ghost/api"
            every { getContentApiKey() } returns contentKey
        }
        val client = HttpClient(MockEngine {
            respond(
                content = """{"posts":[]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(Logging) {
                logger = RedactingLogger(object : Logger {
                    override fun log(message: String) {
                        logged += message
                    }
                })
                level = LogLevel.INFO
            }
        }

        DefaultGhostApi(config, client).getAllPosts()

        logged.shouldNotBeEmpty()
        val output = logged.joinToString("\n")
        output shouldContain "key=<redacted>"
        output shouldNotContain contentKey
    }
})
