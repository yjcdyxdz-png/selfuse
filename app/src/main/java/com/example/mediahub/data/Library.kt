package com.example.mediahub.data

import androidx.room.*
import com.example.mediahub.App
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 书架条目。key = ruleId|itemId；itemId 对 JS 模块是 sourceId，对书源是 bookUrl */
@Entity(tableName = "shelf")
data class ShelfItem(
    @PrimaryKey val key: String,
    val ruleId: String,
    val itemId: String,
    val contentType: String,   // video / comic / novel
    val title: String,
    val cover: String,
    val author: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * 断点续看：
 * - 视频：chapterIndex = 集数，position = 播放位置（毫秒）
 * - 漫画：position = 页码，extra = 分组（"g:<group>"，无分组为空）
 * - 小说：chapterIndex = 章节，position = 章内字符位置
 */
@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val key: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val position: Long,
    val extra: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)

data class ShelfEntry(val item: ShelfItem, val progress: Progress?)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM shelf ORDER BY addedAt DESC") fun observeShelf(): Flow<List<ShelfItem>>
    @Query("SELECT * FROM shelf WHERE `key` = :key") fun observeShelfItem(key: String): Flow<ShelfItem?>
    @Upsert suspend fun upsertShelf(item: ShelfItem)
    @Query("DELETE FROM shelf WHERE `key` = :key") suspend fun deleteShelf(key: String)

    @Query("SELECT * FROM progress") fun observeProgress(): Flow<List<Progress>>
    @Query("SELECT * FROM progress WHERE `key` = :key") fun observeProgressOf(key: String): Flow<Progress?>
    @Query("SELECT * FROM progress WHERE `key` = :key") suspend fun progress(key: String): Progress?
    @Upsert suspend fun upsertProgress(p: Progress)
}

object Library {
    private val dao get() = App.instance.db.libraryDao()

    fun key(ruleId: String, itemId: String) = "$ruleId|$itemId"

    /** 按最近阅读/加入时间排序 */
    fun observeShelf(): Flow<List<ShelfEntry>> = combine(dao.observeShelf(), dao.observeProgress()) { s, p ->
        val m = p.associateBy { it.key }
        s.map { ShelfEntry(it, m[it.key]) }.sortedByDescending { maxOf(it.item.addedAt, it.progress?.updatedAt ?: 0) }
    }

    fun observeOnShelf(key: String): Flow<Boolean> = dao.observeShelfItem(key).map { it != null }
    fun observeProgress(key: String): Flow<Progress?> = dao.observeProgressOf(key)
    suspend fun progress(key: String) = dao.progress(key)

    suspend fun setOnShelf(item: ShelfItem, on: Boolean) =
        if (on) dao.upsertShelf(item) else dao.deleteShelf(item.key)

    suspend fun remove(key: String) = dao.deleteShelf(key)

    /** 不阻塞调用方（页面关闭时也能写完） */
    fun saveProgress(p: Progress) { App.instance.appScope.launch { dao.upsertProgress(p) } }

    fun groupTag(group: String?) = group?.let { "g:$it" } ?: ""
    fun groupOf(extra: String): String? = if (extra.startsWith("g:")) extra.substring(2) else null
}
