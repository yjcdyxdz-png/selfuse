package com.example.mediahub.ui.comic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediahub.App
import com.example.mediahub.comic.ComicSupport
import com.example.mediahub.data.Library
import com.example.mediahub.data.Progress
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.UpdateTracker
import com.example.mediahub.engine.*
import com.example.mediahub.ui.detail.DetailCache
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

data class ComicPage(
    val chapter: Chapter,
    val url: String?,
    val referer: String,
    val error: String? = null,
    /** resolveMedia 返回的额外请求头 */
    val headers: Map<String, String> = emptyMap(),
)

data class ComicUi(
    val rule: RuleEntity? = null,
    val engine: JsModuleEngine? = null,
    val title: String = "",
    val pages: List<ComicPage> = emptyList(),
    val hasTransform: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

class ComicReaderViewModel(
    private val ruleId: String, private val sourceId: String, private val group: String?,
) : ViewModel() {
    private val _ui = MutableStateFlow(ComicUi())
    val ui = _ui.asStateFlow()
    private val resolving = ConcurrentHashMap.newKeySet<Int>()
    private val sem = Semaphore(3)

    init {
        viewModelScope.launch {
            runCatching {
                val rule = App.instance.db.ruleDao().get(ruleId) ?: error("规则不存在")
                val engine = EngineManager.get(rule)
                val d = DetailCache.get(ruleId, sourceId)
                    ?: engine.detail(sourceId).also { DetailCache.put(ruleId, sourceId, it) }
                engine.ensureLoaded()
                UpdateTracker.markSeen(Library.key(ruleId, sourceId), d.chapters.size)
                val pages = ComicSupport.pages(d, group).map { c ->
                    ComicPage(c, c.videoUrl.takeIf(ComicSupport::isImageUrl), rule.baseUrl)
                }
                _ui.value = ComicUi(rule, engine, d.drama.title, pages, engine.hasFunction("transformImage"), false)
            }.onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    /** 页面接近可见区域时调用，非图片地址通过 resolveMedia 取得 */
    fun ensurePage(i: Int) {
        val s = _ui.value; val p = s.pages.getOrNull(i) ?: return
        val engine = s.engine ?: return
        if (p.url != null || !resolving.add(i)) return
        viewModelScope.launch {
            sem.withPermit {
                val r = runCatching { engine.resolveMedia(p.chapter) }
                _ui.update { u ->
                    u.copy(pages = u.pages.toMutableList().also { list ->
                        list[i] = r.fold(
                            { m -> p.copy(url = m.url, referer = m.referer.ifBlank { p.referer }, error = null, headers = m.headerMap()) },
                            { e -> p.copy(error = e.message) })
                    })
                }
                if (r.isFailure) resolving.remove(i)
            }
        }
    }

    private val key = Library.key(ruleId, sourceId)

    /** 记录阅读页码（断点续看） */
    fun save(page: Int) {
        val p = _ui.value.pages.getOrNull(page) ?: return
        Library.saveProgress(Progress(key, page, p.chapter.title, page.toLong(), Library.groupTag(group)))
    }

    fun retry(i: Int) {
        resolving.remove(i)
        _ui.update { u -> u.copy(pages = u.pages.toMutableList().also { it[i] = it[i].copy(error = null) }) }
        ensurePage(i)
    }

    class Factory(private val r: String, private val s: String, private val g: String?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = ComicReaderViewModel(r, s, g) as T
    }
}
