package com.example.mediahub.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediahub.App
import com.example.mediahub.data.Library
import com.example.mediahub.data.Progress
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.engine.*
import com.example.mediahub.ui.detail.DetailCache
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class PlayerUi(
    val rule: RuleEntity? = null,
    val title: String = "",
    val chapters: List<Chapter> = emptyList(),
    val index: Int = 0,
    val media: MediaUrl? = null,
    /** 开始播放时跳转到的位置（断点续看） */
    val startPos: Long = 0,
    val resolving: Boolean = false,
    val error: String? = null,
)

class PlayerViewModel(private val ruleId: String, private val sourceId: String, startIndex: Int) : ViewModel() {
    private val _ui = MutableStateFlow(PlayerUi(index = startIndex))
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    private val key = Library.key(ruleId, sourceId)
    private var saved: Progress? = null

    init {
        viewModelScope.launch {
            runCatching {
                saved = Library.progress(key)
                val rule = App.instance.db.ruleDao().get(ruleId) ?: error("规则不存在")
                val d = DetailCache.get(ruleId, sourceId)
                    ?: EngineManager.get(rule).detail(sourceId).also { DetailCache.put(ruleId, sourceId, it) }
                _ui.update { it.copy(rule = rule, title = d.drama.title, chapters = d.chapters) }
                play(startIndex)
            }.onFailure { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    fun play(i: Int) {
        val s = _ui.value
        val rule = s.rule ?: return
        if (i !in s.chapters.indices) return
        job?.cancel()
        val resume = saved?.takeIf { it.chapterIndex == i }?.position ?: 0L
        _ui.update { it.copy(index = i, media = null, resolving = true, error = null) }
        persist(i, resume)
        job = viewModelScope.launch {
            runCatching {
                val m = EngineManager.get(rule).resolveMedia(s.chapters[i])
                require(m.url.isNotBlank()) { "resolveMedia 未返回地址" }
                if (m.referer.isBlank() && rule.baseUrl.isNotBlank()) m.copy(referer = rule.baseUrl) else m
            }.onSuccess { m ->
                AppLog.i("Player", "播放 ${s.chapters[i].title}: ${m.url} (referer=${m.referer}, from=${resume}ms)")
                _ui.update { it.copy(media = m, startPos = resume, resolving = false) }
            }.onFailure { e -> _ui.update { it.copy(resolving = false, error = e.message) } }
        }
    }

    /** 播放器回报当前位置（只有地址已就绪时才记录，避免把进度覆盖成 0） */
    fun save(position: Long) {
        if (_ui.value.media == null) return
        persist(_ui.value.index, position)
    }

    private fun persist(i: Int, pos: Long) {
        val title = _ui.value.chapters.getOrNull(i)?.title.orEmpty()
        val p = Progress(key, i, title, pos)
        saved = p
        Library.saveProgress(p)
    }

    fun next() = play(_ui.value.index + 1)
    fun hasNext() = _ui.value.index + 1 < _ui.value.chapters.size
    fun reportError(msg: String) = _ui.update { it.copy(error = msg) }

    class Factory(private val r: String, private val s: String, private val i: Int) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = PlayerViewModel(r, s, i) as T
    }
}
