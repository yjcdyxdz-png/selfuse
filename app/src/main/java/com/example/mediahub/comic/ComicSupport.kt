package com.example.mediahub.comic

import android.net.Uri
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.engine.Chapter
import com.example.mediahub.engine.DetailResult

object ComicSupport {
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
}
