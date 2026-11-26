package com.example.mediahub.comic

import android.net.Uri
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.engine.Chapter
import com.example.mediahub.engine.DetailResult
import com.example.mediahub.engine.EngineManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 漫画的「话」有三种来源：
 * - GROUP：分集的 group 字段就是话名，同一 group 的分集是这一话的各页
 * - CHAPTER：每个分集是一话，resolveMedia 返回 {images:[...]} 作为这一话的全部页面
 * - SINGLE：只有一话，所有分集都是页面（原来的做法）
 * UNKNOWN：分集都不是图片地址，要调用一次 resolveMedia 才知道是 CHAPTER 还是 SINGLE
 * 模块可以在 manifest 里写 "comicEpisodes": "chapter" | "group" | "none" 直接指定。
 */
object ComicSupport {
    enum class Mode { SINGLE, GROUP, CHAPTER, UNKNOWN }

    data class Episode(
        val title: String,
        /** GROUP 模式：这一话对应的 group */
        val group: String? = null,
        /** CHAPTER 模式：这一话对应的分集 */
        val chapter: Chapter? = null,
    )

    private val IMG_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic")

    fun isImageUrl(url: String): Boolean {
        if (url.startsWith("data:image/")) return true
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase() ?: return false
        return path.substringAfterLast('.', "") in IMG_EXT
    }

    /** manifest 声明 comic，或分集大多是图片地址时，按漫画处理 */
    fun isComic(rule: RuleEntity, d: DetailResult): Boolean {
        if (rule.contentType.equals("comic", true)) return true
        val urls = d.chapters.map { it.videoUrl }.filter { it.isNotBlank() }
        return urls.isNotEmpty() && urls.count(::isImageUrl) * 2 > urls.size
    }

    fun pages(d: DetailResult, group: String?): List<Chapter> =
        if (group == null) d.chapters else d.chapters.filter { it.group == group }

    fun manifestOf(rule: RuleEntity): JsonObject? =
        if (rule.kind != RuleKind.JS_MODULE) null else runCatching { EngineManager.get(rule).manifest }.getOrNull()

    private fun declared(manifest: JsonObject?): Mode? =
        when ((manifest?.get("comicEpisodes") as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
            "chapter", "chapters" -> Mode.CHAPTER
            "group", "groups" -> Mode.GROUP
            "none", "single" -> Mode.SINGLE
            else -> null
        }

    fun mode(d: DetailResult, manifest: JsonObject?): Mode {
        val chapters = d.chapters
        if (chapters.isEmpty()) return Mode.SINGLE
        val allImages = chapters.all { isImageUrl(it.videoUrl) }
        when (declared(manifest)) {
            // 声明了「每个分集一话」但分集已经是图片地址（比如旧的缓存）：按单话处理
            Mode.CHAPTER -> return if (allImages) Mode.SINGLE else Mode.CHAPTER
            Mode.GROUP -> return Mode.GROUP
            Mode.SINGLE -> return Mode.SINGLE
            else -> Unit
        }
        if (chapters.map { it.group }.distinct().size > 1) return Mode.GROUP
        if (chapters.none { isImageUrl(it.videoUrl) }) return Mode.UNKNOWN
        return Mode.SINGLE
    }

    fun groupsOf(d: DetailResult): List<String> = d.chapters.map { it.group }.distinct()

    fun episodes(d: DetailResult, mode: Mode): List<Episode> = when (mode) {
        Mode.GROUP -> groupsOf(d).mapIndexed { i, g -> Episode(g.ifBlank { "第${i + 1}话" }, group = g) }
        Mode.CHAPTER -> d.chapters.mapIndexed { i, c -> Episode(c.title.ifBlank { "第${i + 1}话" }, chapter = c) }
        else -> listOf(Episode(d.drama.title.ifBlank { "全一话" }))
    }
}
