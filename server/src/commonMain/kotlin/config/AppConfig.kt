package config

interface AppConfiguration {
    fun getGhostApiUrl(): String
    fun getIndexPostId(): String
    fun getAllowedIps(): Set<String>
    fun getTagWhitelist(): Set<String>
    fun getAdminApiKey(): String
    fun getContentApiKey(): String
    fun getVerificationPassphrase(): String
}

class DefaultAppConfiguration : AppConfiguration {
    private val dotenv by lazy { io.github.cdimascio.dotenv.dotenv() }

    override fun getGhostApiUrl(): String =
        System.getenv("GHOST_API_URL")
            ?: dotenv["GHOST_API_URL"]
            ?: ""

    override fun getIndexPostId(): String =
        System.getenv("INDEX_POST_ID")
            ?: dotenv["INDEX_POST_ID"]
            ?: ""

    override fun getAllowedIps(): Set<String> =
        System.getenv("ALLOWED_IPS")?.split(",")?.toSet()
            ?: dotenv["ALLOWED_IPS"]?.split(",")?.toSet()
            ?: emptySet()

    override fun getTagWhitelist(): Set<String> =
        System.getenv("TAG_WHITELIST")?.split(",")?.toSet()
            ?: dotenv["TAG_WHITELIST"]?.split(",")?.toSet()
            ?: emptySet()

    override fun getAdminApiKey(): String =
        System.getenv("GHOST_ADMIN_API_KEY")
            ?: dotenv["GHOST_ADMIN_API_KEY"]
            ?: ""

    override fun getContentApiKey(): String =
        System.getenv("GHOST_CONTENT_API_KEY")
            ?: dotenv["GHOST_CONTENT_API_KEY"] ?: ""

    override fun getVerificationPassphrase(): String =
        System.getenv("VERIFICATION_PASSPHRASE")
            ?: dotenv["VERIFICATION_PASSPHRASE"] ?: ""
}
