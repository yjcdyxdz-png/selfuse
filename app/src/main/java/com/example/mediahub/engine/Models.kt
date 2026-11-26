package com.example.mediahub.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** 与 JS 模块约定的返回格式。所有字段都有默认值，模块少返回字段也能解析。 */
@Serializable
data class Category(val id: String = "", val name: String = "", val extra: JsonElement? = null)

@Serializable
data class MediaItem(
    val id: String = "",
    val source: String = "",
    val sourceId: String = "",
    val title: String = "",
    val cover: String = "",
    val url: String = "",
    val description: String = "",
)

@Serializable
data class PageResult(val items: List<MediaItem> = emptyList(), val hasMore: Boolean = false)

@Serializable
data class Drama(
    val id: String = "",
    val source: String = "",
    val sourceId: String = "",
    val title: String = "",
    val cover: String = "",
    val url: String = "",
    val description: String = "",
    val author: String = "",
    val status: String = "",
    val tags: List<String> = emptyList(),
)

@Serializable
data class Chapter(
    val id: String = "",
    val title: String = "",
    val videoUrl: String = "",
    val group: String = "",
)

@Serializable
data class DetailResult(
    val drama: Drama = Drama(),
    val chapters: List<Chapter> = emptyList(),
    /** 线路信息，格式由模块决定，原样保留 */
    val routes: JsonElement? = null,
)

@Serializable
data class MediaUrl(val url: String = "", val referer: String = "", val quality: String = "")
