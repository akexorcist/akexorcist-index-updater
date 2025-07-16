package shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GhostWebhookPayload(
    val post: PostContainer
)

@Serializable
data class PostContainer(
    val current: Post
)

@Serializable
data class Post(
    val id: String,
    val title: String,
    val url: String,
    val slug: String,
)

suspend fun runServer(port: Int) {
    println("""{ "message": "Starting server on port $port." }""")

    val client = HttpClient(ClientCIO) {
        install(ClientContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        install(Logging) {
            logger = Logger.SIMPLE
            level = LogLevel.ALL
        }
    }

    embeddedServer(
        factory = ServerCIO,
        host = "0.0.0.0",
        port = port,
    ) {
        install(ServerContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                }
            )
        }
        routing {
            post("/webhook/akexorcist") {
                val remoteIp = call.request.headers["x-forwarded-for"]
                    ?.split(",")
                    ?.firstOrNull()
                    ?.trim()
                    ?: call.request.origin.remoteHost

                println("Remote IP : $remoteIp")
                if (remoteIp == "localhost" || remoteIp == "178.128.83.45") {
                    val payload = call.receive<GhostWebhookPayload>()
                    println("Received webhook: $payload")
                    call.respond("OK")
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Invalid webhook source")
                }
            }
        }
    }.startSuspend(wait = true)
}