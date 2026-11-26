package com.example.mediahub.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediahub.App
import com.example.mediahub.data.Library
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.UpdateTracker
import com.example.mediahub.engine.DetailResult
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.util.AppLog
import com.example.mediahub.util.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/** 详情缓存：内存 → 磁盘 → 网络。详情页、播放页、漫画页共用 */
object DetailCache {
    private const val NS = "detail"
    private val map = ConcurrentHashMap<String, DetailResult>()
    /** 每条缓存对应的数据获取时间，用来判断是否需要后台更新 */
    private val loadedAt = ConcurrentHashMap<String, Long>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun key(ruleId: String, sourceId: String) = "$ruleId|$sourceId"

    suspend fun get(ruleId: String, sourceId: String): DetailResult? {
        val k = key(ruleId, sourceId)
        map[k]?.let { return it }
        val d = withContext(Dispatchers.IO) {
            DiskCache.read(NS, k)?.let { runCatching { json.decodeFromString(DetailResult.serializer(), it) }.getOrNull() }
                ?.also { loadedAt[k] = System.currentTimeMillis() - DiskCache.ageMs(NS, k) }
        } ?: return null
        map[k] = d
        return d
    }

    fun put(ruleId: String, sourceId: String, d: DetailResult) {
        val k = key(ruleId, sourceId)
        map[k] = d
        loadedAt[k] = System.currentTimeMillis()
        App.instance.appScope.launch { DiskCache.write(NS, k, json.encodeToString(DetailResult.serializer(), d)) }
    }

    fun ageMs(ruleId: String, sourceId: String): Long =
        System.currentTimeMillis() - (loadedAt[key(ruleId, sourceId)] ?: 0L)

    fun clearMemory() { map.clear(); loadedAt.clear() }
}

sealed interface DetailState {
    data object Loading : DetailState
    data class Error(val msg: String) : DetailState
    data class Ok(val rule: RuleEntity, val detail: DetailResult) : DetailState
}

class DetailViewModel(private val ruleId: String, private val sourceId: String) : ViewModel() {
    private val _state = MutableStateFlow<DetailState>(DetailState.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load(force: Boolean = false) = viewModelScope.launch {
        _state.value = DetailState.Loading
        _state.value = runCatching {
            val rule = App.instance.db.ruleDao().get(ruleId) ?: error("规则不存在：$ruleId")
            val cached = if (force) null else DetailCache.get(ruleId, sourceId)
            if (cached != null) {
                // 先显示缓存；缓存较旧时在后台更新，看到新分集
                if (DetailCache.ageMs(ruleId, sourceId) > REFRESH_MS) refreshSilently(rule)
                seen(cached)
                DetailState.Ok(rule, cached)
            } else {
                val d = EngineManager.get(rule).detail(sourceId)
                DetailCache.put(ruleId, sourceId, d)
                seen(d)
                DetailState.Ok(rule, d)
            }
        }.getOrElse { DetailState.Error(it.message ?: it.toString()) }
    }

    private fun refreshSilently(rule: RuleEntity) = viewModelScope.launch {
        runCatching { EngineManager.get(rule).detail(sourceId) }
            .onSuccess { d ->
                DetailCache.put(ruleId, sourceId, d)
                seen(d)
                _state.value = DetailState.Ok(rule, d)
            }
            .onFailure { AppLog.w("Detail", "后台更新详情失败，继续使用缓存：${it.message}") }
    }

    private fun seen(d: DetailResult) = UpdateTracker.markSeen(Library.key(ruleId, sourceId), d.chapters.size)

    class Factory(private val ruleId: String, private val sourceId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = DetailViewModel(ruleId, sourceId) as T
    }

    companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
    }
}
