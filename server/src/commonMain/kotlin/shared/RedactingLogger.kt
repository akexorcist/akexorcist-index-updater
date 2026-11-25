package shared

import io.ktor.client.plugins.logging.Logger

private val keyQueryParameter = Regex("""([?&]key=)[^&\s]+""")

internal fun redactSecrets(message: String): String = keyQueryParameter.replace(message, "$1<redacted>")

class RedactingLogger(private val delegate: Logger) : Logger {
    override fun log(message: String) {
        delegate.log(redactSecrets(message))
    }
}
