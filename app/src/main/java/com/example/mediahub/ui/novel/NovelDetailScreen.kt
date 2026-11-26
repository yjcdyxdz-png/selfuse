package com.example.mediahub.ui.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.App
import com.example.mediahub.data.Library
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.ShelfItem
import com.example.mediahub.data.UpdateTracker
import com.example.mediahub.novel.*
import com.example.mediahub.ui.common.Cover
import com.example.mediahub.util.AppLog
import com.example.mediahub.util.DiskCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

sealed interface NovelDetailState {
    data object Loading : NovelDetailState
    data class Error(val msg: String) : NovelDetailState
    data class Ok(val rule: RuleEntity, val data: BookData) : NovelDetailState
}

private val bookJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** 加载书籍信息和目录：内存 → 磁盘 → 网络。详情页和阅读页共用 */
suspend fun loadBook(ruleId: String, bookUrl: String, force: Boolean = false): Pair<RuleEntity, BookData> {
    val rule = App.instance.db.ruleDao().get(ruleId) ?: error("书源不存在（可能已删除）")
    val k = NovelCache.key(ruleId, bookUrl)
    if (!force) {
        NovelCache.books[k]?.let { return rule to it }
        val disk = withContext(Dispatchers.IO) {
            DiskCache.read("book", k)?.let { runCatching { bookJson.decodeFromString(BookData.serializer(), it) }.getOrNull() }
        }
        if (disk != null) {
            NovelCache.books[k] = disk
            NovelCache.loadedAt[k] = System.currentTimeMillis() - DiskCache.ageMs("book", k)
            return rule to disk
        }
    }
    val web = BookEngines.get(rule)
    val info = web.bookInfo(bookUrl, NovelCache.hits[bookUrl])
    val data = BookData(info, web.toc(info))
    NovelCache.books[k] = data
    NovelCache.loadedAt[k] = System.currentTimeMillis()
    App.instance.appScope.launch { DiskCache.write("book", k, bookJson.encodeToString(BookData.serializer(), data)) }
    return rule to data
}

private fun bookAgeMs(ruleId: String, bookUrl: String): Long =
    System.currentTimeMillis() - (NovelCache.loadedAt[NovelCache.key(ruleId, bookUrl)] ?: 0L)

class NovelDetailViewModel(private val ruleId: String, private val bookUrl: String) : ViewModel() {
    private val _state = MutableStateFlow<NovelDetailState>(NovelDetailState.Loading)
    val state = _state.asStateFlow()
    init { load() }
    fun load(force: Boolean = false) = viewModelScope.launch {
        _state.value = NovelDetailState.Loading
        _state.value = runCatching {
            val (rule, data) = loadBook(ruleId, bookUrl, force)
            // 用的是缓存且已超过 30 分钟：后台更新目录，看到新章节
            if (!force && bookAgeMs(ruleId, bookUrl) > 30 * 60 * 1000L) refreshSilently()
            UpdateTracker.markSeen(Library.key(ruleId, bookUrl), data.chapters.size)
            NovelDetailState.Ok(rule, data)
        }.getOrElse { NovelDetailState.Error(it.message ?: it.toString()) }
    }
    private fun refreshSilently() = viewModelScope.launch {
        runCatching { loadBook(ruleId, bookUrl, force = true) }
            .onSuccess { (r, d) ->
                UpdateTracker.markSeen(Library.key(ruleId, bookUrl), d.chapters.size)
                _state.value = NovelDetailState.Ok(r, d)
            }
            .onFailure { AppLog.w("Book", "后台更新目录失败，继续使用缓存：${it.message}") }
    }
    class Factory(private val r: String, private val u: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = NovelDetailViewModel(r, u) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelDetailScreen(ruleId: String, bookUrl: String, onBack: () -> Unit, onRead: (Int) -> Unit) {
    val vm: NovelDetailViewModel = viewModel(key = "n|$ruleId|$bookUrl", factory = NovelDetailViewModel.Factory(ruleId, bookUrl))
    val state by vm.state.collectAsState()
    val key = Library.key(ruleId, bookUrl)
    val onShelf by remember(key) { Library.observeOnShelf(key) }.collectAsState(false)
    val progress by remember(key) { Library.observeProgress(key) }.collectAsState(null)
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(title = { Text((state as? NovelDetailState.Ok)?.data?.info?.name ?: "书籍详情", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                (state as? NovelDetailState.Ok)?.let { s ->
                    IconButton(onClick = {
                        val i = s.data.info
                        scope.launch { Library.setOnShelf(ShelfItem(key, ruleId, bookUrl, "novel", i.name, i.coverUrl, i.author), !onShelf) }
                    }) { Icon(if (onShelf) Icons.Filled.Star else Icons.Filled.StarBorder, if (onShelf) "移出书架" else "加入书架") }
                }
                IconButton(onClick = { vm.load(true) }) { Icon(Icons.Default.Refresh, "刷新") }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                NovelDetailState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is NovelDetailState.Error -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败：${s.msg}"); Button(onClick = { vm.load(true) }) { Text("重试") }
                }
                is NovelDetailState.Ok -> {
                    val info = s.data.info
                    val chapters = s.data.chapters
                    LazyColumn {
                        item {
                            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Cover(info.coverUrl, s.rule.baseUrl, Modifier.width(96.dp).height(132.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                                    listOf(info.author, info.kind, info.wordCount,
                                        if (info.lastChapter.isNotBlank()) "最新：${info.lastChapter}" else "")
                                        .filter { it.isNotBlank() }
                                        .forEach { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                                    Text("${chapters.size} 章 · ${s.rule.name}", style = MaterialTheme.typography.bodySmall)
                                    val p = progress
                                    Button(onClick = { onRead(p?.chapterIndex?.coerceIn(0, chapters.lastIndex) ?: 0) },
                                        enabled = chapters.isNotEmpty(), modifier = Modifier.padding(top = 6.dp)) {
                                        Text(if (p != null) "继续 ${p.chapterTitle}" else "开始阅读", maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                            if (info.intro.isNotBlank()) Text(info.intro, Modifier.padding(horizontal = 12.dp),
                                style = MaterialTheme.typography.bodySmall, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            HorizontalDivider(Modifier.padding(top = 8.dp))
                        }
                        itemsIndexed(chapters) { i, c ->
                            Text(c.title + if (c.isVip) " 🔒" else "", Modifier.fillMaxWidth().clickable { onRead(i) }
                                .padding(horizontal = 16.dp, vertical = 12.dp), maxLines = 1,
                                fontWeight = if (progress?.chapterIndex == i) FontWeight.Bold else null,
                                color = if (progress?.chapterIndex == i) MaterialTheme.colorScheme.primary else Color.Unspecified)
                        }
                    }
                }
            }
        }
    }
}
