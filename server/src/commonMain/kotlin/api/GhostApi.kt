package api

import config.AppConfiguration
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlinx.serialization.json.Json
import java.util.Date
import shared.Post
import shared.PostsResponse

interface GhostApi {
    suspend fun getAllPosts(): List<Post>
    suspend fun getPostById(postId: String): Post?
    suspend fun updatePostHtml(
        postId: String,
        lexical: String,
        updatedAt: String,
    )
}

class DefaultGhostApi(
    private val config: AppConfiguration,
    private val client: HttpClient
) : GhostApi {
    override suspend fun getAllPosts(): List<Post> = try {
        val url = "${config.getGhostApiUrl()}/content/posts/" +
                "?fields=id,title,url,created_at" +
                "&include=tags" +
                "&limit=all" +
                "&key=${config.getContentApiKey()}"
        client.get(url).body<PostsResponse>().posts
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }

    override suspend fun getPostById(postId: String): Post? {
        val url = "${config.getGhostApiUrl()}/content/posts/$postId/" +
                "?fields=title,url,created_at,updated_at" +
                "&key=${config.getContentApiKey()}"
        val response = client.get(url)
        val text = response.bodyAsText()
        return try {
            Json.decodeFromString<PostsResponse>(text).posts.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun updatePostHtml(
        postId: String,
        lexical: String,
        updatedAt: String,
    ) {
        val jwt = generateGhostAdminJwt()
        val url = "${config.getGhostApiUrl()}/admin/posts/$postId/"
        val body = UpdatePostRequest(
            listOf(
                UpdatePostBody(
                    id = postId,
                    lexical = lexical,
                    updatedAt = updatedAt,
                )
            )
        )
        client.put(url) {
            contentType(ContentType.Application.Json)
            header("Authorization", "Ghost $jwt")
            setBody(Json.encodeToString(body))
        }
    }

    private fun generateGhostAdminJwt(): String {
        val parts = config.getAdminApiKey().split(":")
        val keyId = parts[0]
        val secret = parts[1]
        val algorithm = Algorithm.HMAC256(secret.hexToByteArray())
        val now = System.currentTimeMillis()
        return JWT.create()
            .withHeader(mapOf("alg" to "HS256", "kid" to keyId))
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + 5 * 60 * 1000)) // 5 minutes
            .withAudience("/admin/")
            .sign(algorithm)
    }

    // Helper to convert hex string to byte array
    private fun String.hexToByteArray(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Serializable
    data class UpdatePostRequest(val posts: List<UpdatePostBody>)

    @Serializable
    data class UpdatePostBody(
        val id: String,
        val lexical: String,
        @SerialName("updated_at") val updatedAt: String,
        val status: String = "published",
    )
} 