package com.example.mediahub.ui.comic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.mediahub.App
import com.example.mediahub.comic.ComicSupport
import com.example.mediahub.comic.ComicSupport.Mode
import com.example.mediahub.data.Library
import com.example.mediahub.data.Progress
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.UpdateTracker
import com.example.mediahub.engine.*
import com.example.mediahub.ui.detail.DetailCache
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val mode: Mode = Mode.SINGLE,
    val episodes: List<ComicSupport.Episode> = emptyList(),
    /** 当前第几话 */
    val ep: Int = 0,
    /** 每次切换话数递增，用于重建列表 */
    val epToken: Int = 0,
    val pages: List<ComicPage> = emptyList(),
    /** 打开这一话时跳到的页码 */
    val startPage: Int = 0,
    val hasTransform: Boolean = false,
    val loading: Boolean = true,
    val epLoading: Boolean = false,
    val error: String? = null,
) {
    val hasPrevEp get() = ep > 0
    val hasNextEp get() = ep + 1 < episodes.size
    val epTitle get() = episodes.getOrNull(ep)?.title.orEmpty()
}

/**
 * @param group 详情页选中的话（GROUP 模式）
 * @param startEp 从第几话开始，-1 表示未指定
 */
class ComicReaderViewModel(
    private val ruleId: String,
    private val sourceId: String,
    private val group: String?,
    private val startEp: Int,
    private val startPage: Int,
) : ViewModel() {
    private val _ui = MutableStateFlow(ComicUi())
    val ui = _ui.asStateFlow()
    private val resolving = ConcurrentHashMap.newKeySet<Int>()
    private val sem = Semaphore(3)
    private val key = Library.key(ruleId, sourceId)
    private var detail: DetailResult? = null
    /** CHAPTER 模式：已经取到的各话图片 */
    private val epCache = ConcurrentHashMap<Int, MediaUrl>()
    private var epJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching {
                val rule = App.instance.db.ruleDao().get(ruleId) ?: error("规则不存在")
                val engine = EngineManager.get(rule)
                // 规则重新导入后，旧的详情缓存格式可能已经变了，不再使用
                val d = DetailCache.get(ruleId, sourceId)?.takeIf { DetailCache.loadedAtMs(ruleId, sourceId) >= rule.importedAt }
                    ?: engine.detail(sourceId).also { DetailCache.put(ruleId, sourceId, it) }
                engine.ensureLoaded()
                detail = d
                UpdateTracker.markSeen(key, d.chapters.size)

                var mode = ComicSupport.mode(d, engine.manifest)
                if (mode == Mode.UNKNOWN) {
                    // 分集都不是图片：先取第一个分集看返回的是一张图还是一整话
                    val probe = runCatching { engine.resolveMedia(d.chapters.first()) }.getOrNull()
                    mode = if (probe != null && probe.images.isNotEmpty()) Mode.CHAPTER else Mode.SINGLE
                    if (mode == Mode.CHAPTER) epCache[0] = probe!!
                }
                val episodes = ComicSupport.episodes(d, mode)
                var ep = 0
                var page = startPage
                when (mode) {
                    Mode.GROUP -> ep = group?.let { g -> episodes.indexOfFirst { it.group == g } }?.takeIf { it >= 0 }
                        ?: startEp.coerceAtLeast(0)
                    Mode.CHAPTER -> if (startEp >= 0) ep = startEp else {
                        // 详情页按「页」点进来的（不知道是一话一分集时）：第几页就是第几话
                        ep = startPage; page = 0
                    }
                    else -> ep = 0
                }
                ep = ep.coerceIn(0, (episodes.size - 1).coerceAtLeast(0))
                AppLog.i("Comic", "${d.drama.title}：模式 $mode，共 ${episodes.size} 话")
                _ui.value = ComicUi(rule, engine, d.drama.title, mode, episodes,
                    hasTransform = engine.hasFunction("transformImage"), loading = false)
                openEpisode(ep, page)
            }.onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    /** 打开第 i 话，从第 page 页开始 */
    fun openEpisode(i: Int, page: Int = 0) {
        val s = _ui.value
        if (i !in s.episodes.indices) return
        epJob?.cancel()
        resolving.clear()
        _ui.update { it.copy(ep = i, epLoading = true, pages = emptyList(), error = null, startPage = page, epToken = it.epToken + 1) }
        epJob = viewModelScope.launch {
            runCatching { pagesOf(i) }
                .onSuccess { p ->
                    val start = page.coerceIn(0, (p.size - 1).coerceAtLeast(0))
                    _ui.update { it.copy(pages = p, epLoading = false, startPage = start) }
                    save(start)
                    prefetch(i + 1)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    _ui.update { it.copy(epLoading = false, error = e.message ?: e.toString()) }
                }
        }
    }

    fun nextEpisode() { val s = _ui.value; if (s.hasNextEp) openEpisode(s.ep + 1) }
    fun prevEpisode() { val s = _ui.value; if (s.hasPrevEp) openEpisode(s.ep - 1) }
    fun retryEpisode() { val s = _ui.value; epCache.remove(s.ep); openEpisode(s.ep, s.startPage) }

    private suspend fun pagesOf(i: Int): List<ComicPage> {
        val s = _ui.value
        val d = detail ?: return emptyList()
        val base = s.rule?.baseUrl.orEmpty()
        val e = s.episodes[i]
        return when (s.mode) {
            Mode.CHAPTER -> {
                val c = e.chapter ?: return emptyList()
                val m = epCache[i] ?: s.engine!!.resolveMedia(c).also { epCache[i] = it }
                val imgs = m.images.ifEmpty { listOfNotNull(m.url.takeIf { it.isNotBlank() }) }
                if (imgs.isEmpty()) error("这一话没有取到图片")
                val ref = m.referer.ifBlank { base }
                val headers = m.headerMap()
                imgs.map { ComicPage(c, it, ref, headers = headers) }
            }
            Mode.GROUP -> ComicSupport.pages(d, e.group).map { ComicPage(it, it.videoUrl.takeIf(ComicSupport::isImageUrl), base) }
            else -> d.chapters.map { ComicPage(it, it.videoUrl.takeIf(ComicSupport::isImageUrl), base) }
        }
    }

    /** CHAPTER 模式：提前取好下一话的图片地址 */
    private fun prefetch(i: Int) {
        val s = _ui.value
        if (s.mode != Mode.CHAPTER || i !in s.episodes.indices || epCache.containsKey(i)) return
        val c = s.episodes[i].chapter ?: return
        val engine = s.engine ?: return
        viewModelScope.launch { runCatching { engine.resolveMedia(c) }.onSuccess { epCache[i] = it } }
    }

    /** 页面接近可见区域时调用，非图片地址通过 resolveMedia 取得（一页一个分集的情况） */
    fun ensurePage(i: Int) {
        val s = _ui.value; val p = s.pages.getOrNull(i) ?: return
        val engine = s.engine ?: return
        if (p.url != null || !resolving.add(i)) return
        val token = s.epToken
        viewModelScope.launch {
            sem.withPermit {
                val r = runCatching { engine.resolveMedia(p.chapter) }
                _ui.update { u ->
                    if (u.epToken != token || i !in u.pages.indices) u
                    else u.copy(pages = u.pages.toMutableList().also { list ->
                        list[i] = r.fold(
                            { m -> p.copy(url = (m.images.firstOrNull() ?: m.url).takeIf { it.isNotBlank() },
                                referer = m.referer.ifBlank { p.referer }, error = null, headers = m.headerMap()) },
                            { e -> p.copy(error = e.message) })
                    })
                }
                if (r.isFailure) resolving.remove(i)
            }
        }
    }

    /** 记录阅读位置（断点续看）：第几话 + 第几页 */
    fun save(page: Int) {
        val s = _ui.value
        val p = s.pages.getOrNull(page) ?: return
        val e = s.episodes.getOrNull(s.ep)
        val (title, extra) = when (s.mode) {
            Mode.GROUP -> e?.title.orEmpty() to Library.groupTag(e?.group)
            Mode.CHAPTER -> e?.title.orEmpty() to Library.epTag(s.ep)
            else -> p.chapter.title to ""
        }
        Library.saveProgress(Progress(key, s.ep, title, page.toLong(), extra))
    }

    fun retry(i: Int) {
        resolving.remove(i)
        _ui.update { u -> if (i !in u.pages.indices) u else u.copy(pages = u.pages.toMutableList().also { it[i] = it[i].copy(error = null) }) }
        ensurePage(i)
    }

    class Factory(
        private val r: String, private val s: String, private val g: String?,
        private val ep: Int, private val page: Int,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = ComicReaderViewModel(r, s, g, ep, page) as T
    }
}
