package parser

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import shared.Post
import shared.Tag

class PostContentParserTest : FunSpec({
    val parser = PostContentParser()

    val tagWhitelist = setOf("Kotlin", "Ktor", "Backend")

    val posts = listOf(
        Post(
            id = "1",
            title = "Kotlin Basics",
            url = "https://example.com/kotlin-basics",
            tags = listOf(Tag("Kotlin")),
            createdAt = "2024-06-01T10:00:00Z"
        ),
        Post(
            id = "2",
            title = "Ktor Guide",
            url = "https://example.com/ktor-guide",
            tags = listOf(Tag("Ktor")),
            createdAt = "2024-06-02T10:00:00Z"
        ),
        Post(
            id = "3",
            title = "Advanced Kotlin",
            url = "https://example.com/advanced-kotlin",
            tags = listOf(Tag("Kotlin")),
            createdAt = "2024-06-03T10:00:00Z"
        ),
        Post(
            id = "4",
            title = "Backend Patterns",
            url = "https://example.com/backend-patterns",
            tags = listOf(Tag("Backend")),
            createdAt = "2024-06-04T10:00:00Z"
        ),
        Post(
            id = "5",
            title = "No Tag Post",
            url = "https://example.com/no-tag",
            tags = emptyList(),
            createdAt = "2024-06-05T10:00:00Z"
        )
    )

    test("groupAndSortPostsByTag groups and sorts posts by tag in whitelist") {
        val grouped = parser.groupAndSortPostsByTag(posts, tagWhitelist)
        grouped.keys shouldContainExactly tagWhitelist
        grouped["Kotlin"]!!.map { it.title } shouldBe listOf("Advanced Kotlin", "Kotlin Basics")
        grouped["Ktor"]!!.map { it.title } shouldBe listOf("Ktor Guide")
        grouped["Backend"]!!.map { it.title } shouldBe listOf("Backend Patterns")
    }

    test("groupAndSortPostsByTag returns empty for tags not in whitelist") {
        val grouped = parser.groupAndSortPostsByTag(posts, setOf("NotExist"))
        grouped shouldBe emptyMap()
    }

    test("formatPostsAsLexical produces correct lexical JSON structure") {
        val grouped = parser.groupAndSortPostsByTag(posts, tagWhitelist)
        val lexicalJson = parser.formatPostsAsLexical(grouped, tagWhitelist)
        val root = Json.parseToJsonElement(lexicalJson).jsonObject["root"]!!.jsonObject
        root["type"]!!.toString() shouldBe "\"root\""
        val children = root["children"]!!.jsonArray
        // Should have 3 headings and 3 lists (one for each tag in whitelist)
        children.count { it.jsonObject["type"]?.toString() == "\"heading\"" } shouldBe 3
        children.count { it.jsonObject["type"]?.toString() == "\"list\"" } shouldBe 3
    }

    test("formatPostsAsLexical with empty posts produces empty children") {
        val grouped = parser.groupAndSortPostsByTag(emptyList(), tagWhitelist)
        val lexicalJson = parser.formatPostsAsLexical(grouped, tagWhitelist)
        val root = Json.parseToJsonElement(lexicalJson).jsonObject["root"]!!.jsonObject
        root["children"]!!.jsonArray.size shouldBe 0
    }

    test("formatPostsAsLexical skips tags with no posts") {
        val grouped = parser.groupAndSortPostsByTag(posts, setOf("Kotlin"))
        val lexicalJson = parser.formatPostsAsLexical(grouped, setOf("Kotlin", "NotExist"))
        val root = Json.parseToJsonElement(lexicalJson).jsonObject["root"]!!.jsonObject
        // Only "Kotlin" should be present
        val children = root["children"]!!.jsonArray
        children.count { it.jsonObject["type"]?.toString() == "\"heading\"" } shouldBe 1
        children.count { it.jsonObject["type"]?.toString() == "\"list\"" } shouldBe 1
    }
}) 