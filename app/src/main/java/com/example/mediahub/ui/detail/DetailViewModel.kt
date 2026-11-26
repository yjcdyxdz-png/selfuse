package com.example.mediahub.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediahub.App
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.engine.DetailResult
import com.example.mediahub.engine.EngineManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** 详情缓存：详情页、播放页、漫画页共用同一份分集列表 */
object DetailCache {
    private val map = ConcurrentHashMap<String, DetailResult>()
    private fun key(ruleId: String, sourceId: String) = "$ruleId|$sourceId"
    fun get(ruleId: String, sourceId: String) = map[key(ruleId, sourceId)]
    fun put(ruleId: String, sourceId: String, d: DetailResult) { map[key(ruleId, sourceId)] = d }
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
            val d = (if (force) null else DetailCache.get(ruleId, sourceId))
                ?: EngineManager.get(rule).detail(sourceId).also { DetailCache.put(ruleId, sourceId, it) }
            DetailState.Ok(rule, d)
        }.getOrElse { DetailState.Error(it.message ?: it.toString()) }
    }

    class Factory(private val ruleId: String, private val sourceId: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = DetailViewModel(ruleId, sourceId) as T
    }
}
