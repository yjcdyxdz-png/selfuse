package com.example.mediahub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RuleKind { JS_MODULE, LEGADO_BOOK }

/**
 * 已导入的规则。JS 模块解压到 filesDir/modules/<id>/，书源 JSON 存到 filesDir/booksources/<id>.json。
 * contentType: video / comic / novel
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val kind: RuleKind,
    val name: String,
    val version: String = "",
    val contentType: String = "video",
    val baseUrl: String = "",
    val enabled: Boolean = true,
    val path: String,
    val importedAt: Long = System.currentTimeMillis(),
)
