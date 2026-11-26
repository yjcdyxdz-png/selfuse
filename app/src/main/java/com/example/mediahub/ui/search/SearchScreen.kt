package com.example.mediahub.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.App
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.novel.BookEngines
import com.example.mediahub.ui.common.Hit
import com.example.mediahub.ui.common.HitRow
import com.example.mediahub.ui.common.toHit
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

data class SearchUi(
    val hits: List<Hit> = emptyList(),
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val running: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
)

/** 每个源各自的翻页状态 */
private class SourcePaging(val rule: RuleEntity) {
    var page = 0
    var hasMore = true
    val seen = HashSet<String>()
}

/** 并发搜索所有已启用的规则（最多 4 个同时，每个源 30 秒超时），结果陆续追加；滑到底部继续取下一页 */
class SearchViewModel : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null
    private var key = ""
    private var sources: List<SourcePaging> = emptyList()

    fun search(kw: String) {
        val k = kw.trim(); if (k.isEmpty()) return
        job?.cancel()
        key = k
        job = viewModelScope.launch {
            val rules = App.instance.rules.observe().first().filter { it.enabled }
            sources = rules.map { SourcePaging(it) }
            _ui.value = SearchUi(total = rules.size, running = true)
            fetchNextPage(first = true)
        }
    }

    /** 对还有下一页的源各取一页 */
    fun loadMore() {
        val s = _ui.value
        if (s.running || s.loadingMore || sources.none { it.hasMore }) return
        job = viewModelScope.launch {
            _ui.update { it.copy(loadingMore = true) }
            fetchNextPage(first = false)
        }
    }

    private suspend fun fetchNextPage(first: Boolean) = coroutineScope {
        val k = key
        val sem = Semaphore(4)
        sources.filter { it.hasMore }.map { src ->
            launch {
                sem.withPermit {
                    val rule = src.rule
                    val page = src.page + 1
                    val r = runCatching {
                        withTimeout(30_000) {
                            if (rule.kind == RuleKind.LEGADO_BOOK) {
                                val l = BookEngines.get(rule).search(k, page).map { it.toHit(rule) }
                                l to l.isNotEmpty()
                            } else {
                                val pr = EngineManager.get(rule).search(page, k)
                                pr.items.map { it.toHit(rule) } to pr.hasMore
                            }
                        }
                    }
                    val err = r.exceptionOrNull()
                    // 换了关键词、离开页面导致的取消直接结束；超时算失败
                    if (err is CancellationException && err !is TimeoutCancellationException) throw err
                    if (err != null) AppLog.e("Search", "${rule.name} 第${page}页搜索失败: ${err.message}")
                    val (list, more) = r.getOrDefault(emptyList<Hit>() to false)
                    // 书源判断不了有没有下一页：这一页没有新结果就停止
                    val fresh = list.filter { src.seen.add(it.id + "\u0000" + it.title) }
                    AppLog.i("Search", "${rule.name} 搜索「$k」第${page}页返回 ${list.size} 条，新增 ${fresh.size} 条")
                    src.page = page
                    src.hasMore = err == null && more && fresh.isNotEmpty()
                    _ui.update { u ->
                        u.copy(
                            hits = u.hits + fresh,
                            done = if (first) u.done + 1 else u.done,
                            failed = if (first && err != null) u.failed + 1 else u.failed,
                        )
                    }
                }
            }
        }.joinAll()
        _ui.update { it.copy(running = false, loadingMore = false, hasMore = sources.any { s -> s.hasMore }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Hit) -> Unit, vm: SearchViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var kw by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("all") }
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val shown = remember(ui.hits, type) { if (type == "all") ui.hits else ui.hits.filter { it.rule.contentType == type } }
    val doSearch = {
        focus.clearFocus()
        vm.search(kw)
        scope.launch { listState.scrollToItem(0) }
        Unit
    }
    // 滑到倒数几条时自动加载下一页
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, ui.running, ui.loadingMore, shown.size) {
        if (nearEnd && shown.isNotEmpty() && ui.hasMore && !ui.running && !ui.loadingMore) vm.loadMore()
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(kw, { kw = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
            placeholder = { Text("搜索全部已启用的规则") },
            trailingIcon = { IconButton(onClick = doSearch) { Icon(Icons.Default.Search, "搜索") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { doSearch() }))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("all" to "全部", "video" to "视频", "comic" to "漫画", "novel" to "小说")) { (k, n) ->
                FilterChip(selected = type == k, onClick = { type = k }, label = { Text(n) })
            }
        }
        if (ui.total > 0) Text("已完成 ${ui.done}/${ui.total} 个源" + (if (ui.failed > 0) "，${ui.failed} 个失败（见日志）" else "") +
            "，共 ${shown.size} 条", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ui.total == 0 && !ui.running) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("输入关键词搜索", style = MaterialTheme.typography.bodyMedium)
        } else LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(shown, key = { i, h -> "$i|${h.rule.id}|${h.id}" }) { _, h -> HitRow(h) { onOpen(h) } }
            item(key = "footer") {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    when {
                        ui.loadingMore -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        ui.hasMore && !ui.running -> TextButton(onClick = { vm.loadMore() }) { Text("加载更多") }
                        !ui.running && shown.isNotEmpty() -> Text("没有更多了", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
