package shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.SIMPLE
import kotlinx.serialization.json.Json
import io.ktor.server.routing.routing
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import config.AppConfiguration
import config.DefaultAppConfiguration
import api.GhostApi
import api.DefaultGhostApi
import io.ktor.server.routing.post
import parser.PostContentParser
import routing.akexorcistWebhook

private val appModule = module {
    single<AppConfiguration> { DefaultAppConfiguration() }
    single { PostContentParser() }
    single {
        HttpClient(ClientCIO) {
            install(ClientContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    }
                )
            }
            install(Logging) {
                logger = Logger.SIMPLE
                level = LogLevel.INFO
            }
        }
    }
    single<GhostApi> { DefaultGhostApi(get(), get()) }
}

private fun getKoinModules() = listOf(appModule)

suspend fun runServer(port: Int) {
    println("""{ "message": "Starting server on port $port." }""")
    embeddedServer(
        factory = ServerCIO,
        host = "0.0.0.0",
        port = port,
    ) {
        install(ServerContentNegotiation) {
            json()
        }
        install(Koin) {
            modules(getKoinModules())
        }
        val appConfig by inject<AppConfiguration>()
        val ghostApi by inject<GhostApi>()
        val postContentParser by inject<PostContentParser>()
        routing {
            post("/webhook/akexorcist") {
                akexorcistWebhook(appConfig, ghostApi, postContentParser)
            }
        }
    }.startSuspend(wait = true)
}
