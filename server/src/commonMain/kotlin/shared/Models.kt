package shared

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class Tag(
    val name: String
)

@Serializable
data class Post(
    val id: String,
    val title: String,
    val url: String,
    val tags: List<Tag> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class PostsResponse(
    val posts: List<Post>
)

@Serializable
data class GhostWebhookPostCurrent(
    val id: String
)

@Serializable
data class GhostWebhookPost(
    val current: GhostWebhookPostCurrent? = null,
    val previous: Map<String, String>? = null
)

@Serializable
data class GhostWebhookPayload(
    val event: String? = null,
    val post: GhostWebhookPost?
)

data class ServerResponse(
    val status: HttpStatusCode,
    val message: String,
)
