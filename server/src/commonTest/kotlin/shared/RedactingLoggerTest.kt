package shared

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.plugins.logging.Logger

class RedactingLoggerTest : FunSpec({
    test("hides the key at the end of a request URL") {
        redactSecrets("REQUEST: https://example.com/ghost/api/content/posts/?limit=all&key=abc123def456") shouldBe
            "REQUEST: https://example.com/ghost/api/content/posts/?limit=all&key=<redacted>"
    }

    test("hides the key in the middle of a query and keeps the other parameters") {
        redactSecrets("FROM: https://example.com/posts/?key=abc123&limit=all") shouldBe
            "FROM: https://example.com/posts/?key=<redacted>&limit=all"
    }

    test("hides every key in a multi-line log message") {
        val message = "REQUEST: https://example.com/a/?key=first\nRESPONSE: 200 OK\nFROM: https://example.com/a/?key=first"
        redactSecrets(message) shouldBe
            "REQUEST: https://example.com/a/?key=<redacted>\nRESPONSE: 200 OK\nFROM: https://example.com/a/?key=<redacted>"
    }

    test("leaves parameters that only end with key untouched") {
        redactSecrets("https://example.com/?apikey=visible&monkey=visible") shouldBe
            "https://example.com/?apikey=visible&monkey=visible"
    }

    test("passes the redacted message to the wrapped logger") {
        val received = mutableListOf<String>()
        val logger = RedactingLogger(object : Logger {
            override fun log(message: String) {
                received += message
            }
        })
        logger.log("GET https://example.com/?fields=id&key=secret-value")
        received shouldContainExactly listOf("GET https://example.com/?fields=id&key=<redacted>")
    }
})
