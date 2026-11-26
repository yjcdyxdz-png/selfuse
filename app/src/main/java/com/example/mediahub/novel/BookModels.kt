package com.example.mediahub.novel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

@Serializable
data class SearchBook(
    val name: String,
    val author: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val intro: String = "",
    val coverUrl: String = "",
    val bookUrl: String,
    val wordCount: String = "",
)

@Serializable
data class BookInfo(
    val name: String,
    val author: String = "",
    val kind: String = "",
    val lastChapter: String = "",
    val intro: String = "",
    val coverUrl: String = "",
    val wordCount: String = "",
    val tocUrl: String,
    val bookUrl: String,
) {
    fun toSearch() = SearchBook(name, author, kind, lastChapter, intro, coverUrl, bookUrl, wordCount)
}

@Serializable
data class BookChapter(val index: Int, val title: String, val url: String, val isVip: Boolean = false)

@Serializable
data class BookData(val info: BookInfo, val chapters: List<BookChapter>)

/** Legado 书源（只读取用到的字段，其余忽略） */
class BookSource(val json: JsonObject) {
    val url = str("bookSourceUrl")
    val name = str("bookSourceName")
    val header = str("header")
    val searchUrl = str("searchUrl")
    val exploreUrl = str("exploreUrl")
    /** 书源共享 JS 函数库（Legado 的 jsLib 字段），所有 @js 执行前先加载 */
    val jsLib = str("jsLib")
    val ruleSearch = obj("ruleSearch")
    val ruleExplore = obj("ruleExplore")
    val ruleBookInfo = obj("ruleBookInfo")
    val ruleToc = obj("ruleToc")
    val ruleContent = obj("ruleContent")

    private fun str(k: String) = (json[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 规则组可能是对象，也可能是 JSON 字符串 */
    private fun obj(k: String): Map<String, String> {
        val v = json[k] ?: return emptyMap()
        val o = when (v) {
            is JsonObject -> v
            is JsonPrimitive -> runCatching { Json.parseToJsonElement(v.content).jsonObject }.getOrNull()
            else -> null
        } ?: return emptyMap()
        return o.mapValues { (_, e) -> (e as? JsonPrimitive)?.contentOrNull ?: if (e is JsonNull) "" else e.toString() }
    }

    companion object {
        fun load(f: File) = BookSource(Json.parseToJsonElement(f.readText()).jsonObject)
    }
}
