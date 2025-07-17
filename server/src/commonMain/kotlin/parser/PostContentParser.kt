package parser

import kotlinx.serialization.json.Json as KotlinxJson
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import shared.Post

class PostContentParser {
    fun groupAndSortPostsByTag(
        posts: List<Post>,
        tagWhitelist: Set<String>
    ): Map<String, List<Post>> {
        return posts
            .flatMap { post -> post.tags.map { it.name to post } }
            .filter { (tag, _) -> tag in tagWhitelist }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, posts) -> posts.sortedByDescending { it.createdAt } }
    }

    fun formatPostsAsLexical(groupedPosts: Map<String, List<Post>>, tagWhitelist: Set<String>): String {
        val children = buildList {
            tagWhitelist.forEach { tag ->
                val posts = groupedPosts[tag]
                if (!posts.isNullOrEmpty()) {
                    // Heading node for the tag
                    add(
                        buildJsonObject {
                            put("type", "heading")
                            put("tag", "h1")
                            put("children", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", tag)
                                })
                            })
                        }
                    )
                    // List node for the posts
                    add(
                        buildJsonObject {
                            put("type", "list")
                            put("listType", "bullet")
                            put("children", buildJsonArray {
                                posts.forEach { post ->
                                    add(buildJsonObject {
                                        put("type", "listitem")
                                        put("children", buildJsonArray {
                                            add(buildJsonObject {
                                                put("type", "link")
                                                put("url", post.url)
                                                put("children", buildJsonArray {
                                                    add(buildJsonObject {
                                                        put("type", "text")
                                                        put("text", post.title)
                                                    })
                                                })
                                            })
                                        })
                                    })
                                }
                            })
                        }
                    )
                }
            }
        }
        val root = buildJsonObject {
            put("type", "root")
            put("children", JsonArray(children))
        }
        val lexical = buildJsonObject {
            put("root", root)
        }
        return KotlinxJson.encodeToString(JsonObject.serializer(), lexical)
    }
} 