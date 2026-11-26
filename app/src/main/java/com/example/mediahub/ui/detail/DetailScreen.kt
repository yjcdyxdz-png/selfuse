package com.example.mediahub.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.comic.ComicSupport
import com.example.mediahub.data.Library
import com.example.mediahub.data.ShelfItem
import com.example.mediahub.ui.common.Cover
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    ruleId: String, sourceId: String,
    onBack: () -> Unit,
    onPlay: (index: Int) -> Unit,
    /** 漫画：group 为选中的话（按 group 分话时），ep 为第几话（-1 表示未指定） */
    onRead: (group: String?, page: Int, ep: Int) -> Unit,
) {
    val vm: DetailViewModel = viewModel(key = "$ruleId|$sourceId", factory = DetailViewModel.Factory(ruleId, sourceId))
    val state by vm.state.collectAsState()
    val key = Library.key(ruleId, sourceId)
    val onShelf by remember(key) { Library.observeOnShelf(key) }.collectAsState(false)
    val scope = rememberCoroutineScope()

    Scaffold(topBar = {
        TopAppBar(title = { Text((state as? DetailState.Ok)?.detail?.drama?.title ?: "详情", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                (state as? DetailState.Ok)?.let { s ->
                    IconButton(onClick = {
                        val d = s.detail.drama
                        val type = if (ComicSupport.isComic(s.rule, s.detail)) "comic" else "video"
                        scope.launch { Library.setOnShelf(ShelfItem(key, ruleId, sourceId, type, d.title, d.cover, d.author), !onShelf) }
                    }) { Icon(if (onShelf) Icons.Filled.Star else Icons.Filled.StarBorder, if (onShelf) "移出书架" else "加入书架") }
                }
                IconButton(onClick = { vm.load(force = true) }) { Icon(Icons.Default.Refresh, "刷新") }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (val s = state) {
                DetailState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is DetailState.Error -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败：${s.msg}")
                    Button(onClick = { vm.load(true) }) { Text("重试") }
                }
                is DetailState.Ok -> DetailContent(s, key, onPlay, onRead)
            }
        }
    }
}

@Composable
private fun DetailContent(s: DetailState.Ok, key: String, onPlay: (Int) -> Unit, onRead: (String?, Int, Int) -> Unit) {
    val d = s.detail.drama
    val chapters = s.detail.chapters
    val groups = remember(chapters) { chapters.map { it.group }.distinct() }
    var group by remember(groups) { mutableStateOf(groups.firstOrNull() ?: "") }
    val comic = remember(s) { ComicSupport.isComic(s.rule, s.detail) }
    // 漫画的分话方式（不调用模块；需要调用才能确定的按「页」显示，进阅读器后再判断）
    val mode = remember(s) { if (comic) ComicSupport.mode(s.detail, ComicSupport.manifestOf(s.rule)) else ComicSupport.Mode.SINGLE }
    val byGroup = comic && mode == ComicSupport.Mode.GROUP
    val byChapter = comic && mode == ComicSupport.Mode.CHAPTER
    val grp = if (groups.size > 1) group else null
    val pageList = remember(grp, chapters) { ComicSupport.pages(s.detail, grp) }
    val progress by remember(key) { Library.observeProgress(key) }.collectAsState(null)

    fun comicSummary(): String = when {
        byChapter -> "共 ${chapters.size} 话"
        byGroup -> "共 ${groups.size} 话 · 本话 ${pageList.size} 页"
        else -> "共 ${pageList.size} 页"
    }

    LazyVerticalGrid(GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Cover(d.cover, s.rule.baseUrl, Modifier.width(110.dp).height(150.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.title, style = MaterialTheme.typography.titleMedium)
                    if (d.author.isNotBlank()) Text(d.author, style = MaterialTheme.typography.bodySmall)
                    if (d.status.isNotBlank()) Text(d.status, style = MaterialTheme.typography.bodySmall)
                    Text((if (comic) comicSummary() else "共 ${chapters.size} 集") + " · ${s.rule.name}",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(d.description, maxLines = 6, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val p = progress
                        if (p != null && chapters.isNotEmpty()) Button(onClick = {
                            if (comic) onRead(Library.groupOf(p.extra), p.position.toInt(), Library.epOf(p.extra) ?: -1)
                            else onPlay(p.chapterIndex.coerceIn(0, chapters.lastIndex))
                        }) {
                            Text(
                                when {
                                    !comic -> "继续 ${p.chapterTitle.ifBlank { "第${p.chapterIndex + 1}集" }}"
                                    p.extra.isNotEmpty() && p.chapterTitle.isNotBlank() -> "继续 ${p.chapterTitle} 第${p.position + 1}页"
                                    else -> "继续 第${p.position + 1}页"
                                },
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        else if (comic && chapters.isNotEmpty()) Button(onClick = {
                            when {
                                byChapter -> onRead(null, 0, 0)
                                byGroup -> onRead(grp, 0, groups.indexOf(group).coerceAtLeast(0))
                                else -> onRead(grp, 0, -1)
                            }
                        }) { Text("开始阅读") }
                        else if (!comic && chapters.isNotEmpty()) Button(onClick = { onPlay(0) }) { Text("开始播放") }
                    }
                }
            }
        }
        if (groups.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
            ScrollableTabRow(selectedTabIndex = groups.indexOf(group).coerceAtLeast(0), edgePadding = 0.dp) {
                groups.forEachIndexed { gi, g -> Tab(selected = g == group, onClick = { group = g },
                    text = { Text(g.ifBlank { if (byGroup) "第${gi + 1}话" else "默认" }) }) }
            }
        }
        if (chapters.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text("没有分集") }
        chapters.forEachIndexed { i, c ->
            if (groups.size <= 1 || c.group == group) item(key = "$i") {
                val current = if (byChapter) Library.epOf(progress?.extra.orEmpty()) == i else !comic && progress?.chapterIndex == i
                val btn: @Composable (@Composable RowScope.() -> Unit) -> Unit = { content ->
                    val click = {
                        when {
                            byChapter -> onRead(null, 0, i)
                            byGroup -> onRead(grp, pageList.indexOf(c).coerceAtLeast(0), groups.indexOf(group).coerceAtLeast(0))
                            comic -> onRead(grp, pageList.indexOf(c).coerceAtLeast(0), -1)
                            else -> onPlay(i)
                        }
                    }
                    if (current) Button(onClick = click, contentPadding = PaddingValues(4.dp), content = content)
                    else OutlinedButton(onClick = click, contentPadding = PaddingValues(4.dp), content = content)
                }
                btn {
                    Text(c.title.ifBlank { if (byChapter) "第${i + 1}话" else "第${i + 1}集" },
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
