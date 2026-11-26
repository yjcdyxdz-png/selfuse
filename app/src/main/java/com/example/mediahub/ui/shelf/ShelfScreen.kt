package com.example.mediahub.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediahub.App
import com.example.mediahub.data.AppSettings
import com.example.mediahub.data.Library
import com.example.mediahub.data.ShelfEntry
import com.example.mediahub.data.UpdateTracker
import com.example.mediahub.ui.common.Cover
import com.example.mediahub.ui.common.relTime
import com.example.mediahub.ui.common.typeLabel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

private val SORTS = listOf(0 to "最近阅读", 1 to "最近加入", 2 to "名称", 3 to "有更新的优先")
private val TYPES = listOf("all" to "全部", "video" to "视频", "comic" to "漫画", "novel" to "小说")

fun progressLabel(e: ShelfEntry): String {
    val p = e.progress ?: return "未开始"
    return when (e.item.contentType) {
        "video" -> "看到 " + p.chapterTitle.ifBlank { "第${p.chapterIndex + 1}集" } + " " + fmtTime(p.position)
        "comic" -> if (p.extra.isNotEmpty() && p.chapterTitle.isNotBlank()) "读到 ${p.chapterTitle} 第${p.position + 1}页"
            else "读到 第${p.position + 1}页"
        else -> "读到 " + p.chapterTitle.ifBlank { "第${p.chapterIndex + 1}章" }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

private fun recentAt(e: ShelfEntry) = maxOf(e.item.addedAt, e.progress?.updatedAt ?: 0L)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(onResume: (ShelfEntry) -> Unit, onDetail: (ShelfEntry) -> Unit) {
    val app = LocalContext.current.applicationContext
    val entries by remember { Library.observeShelf() }.collectAsState(initial = null)
    val baseUrls by remember { App.instance.rules.observe().map { l -> l.associate { it.id to it.baseUrl } } }
        .collectAsState(initial = emptyMap())
    val newsFlow = remember { UpdateTracker.observe() }
    val news by newsFlow.collectAsState()
    val checking by UpdateTracker.state.collectAsState()
    val sortFlow = remember { AppSettings.shelfSort(app) }
    val sort by sortFlow.collectAsState(initial = 0)
    var type by rememberSaveable { mutableStateOf("all") }
    var sortMenu by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 打开书架时自动检查更新（距上次超过 30 分钟）
    LaunchedEffect(Unit) { UpdateTracker.checkIfDue() }

    val all = entries ?: return
    if (all.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("书架是空的\n在详情页点 ☆ 加入书架", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        return
    }

    val counts = remember(all) { all.groupingBy { it.item.contentType }.eachCount() }
    // 当前分类被清空时回到「全部」
    LaunchedEffect(counts, type) { if (type != "all" && (counts[type] ?: 0) == 0) type = "all" }

    val shown = remember(all, type, sort, news) {
        val l = if (type == "all") all else all.filter { it.item.contentType == type }
        when (sort) {
            1 -> l.sortedByDescending { it.item.addedAt }
            2 -> {
                val col = Collator.getInstance(Locale.CHINA)
                l.sortedWith { a, b -> col.compare(a.item.title, b.item.title) }
            }
            3 -> l.sortedWith(compareByDescending<ShelfEntry> { (news[it.item.key] ?: 0) > 0 }.thenByDescending { recentAt(it) })
            else -> l.sortedByDescending { recentAt(it) }
        }
    }
    val continueList = remember(all, type) {
        all.filter { it.progress != null && (type == "all" || it.item.contentType == type) }
            .sortedByDescending { it.progress?.updatedAt ?: 0L }
            .take(8)
    }
    val updatedCount = shown.count { (news[it.item.key] ?: 0) > 0 }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TYPES.forEach { (k, name) ->
                    val c = if (k == "all") all.size else counts[k] ?: 0
                    if (k == "all" || c > 0) FilterChip(selected = type == k, onClick = { type = k }, label = { Text("$name $c") })
                }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "排序") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SORTS.forEach { (k, name) ->
                        DropdownMenuItem(
                            text = { Text(name + if (k == sort) "  ✓" else "") },
                            onClick = { sortMenu = false; scope.launch { AppSettings.setShelfSort(app, k) } },
                        )
                    }
                }
            }
            if (checking != null) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else IconButton(onClick = { UpdateTracker.startCheck() }) { Icon(Icons.Default.Refresh, "检查更新") }
        }
        val c = checking
        Text(
            when {
                c != null -> "正在检查更新 ${c.done}/${c.total}"
                updatedCount > 0 -> "$updatedCount 部有更新 · 上次检查 ${relTime(UpdateTracker.lastCheckAt)}"
                else -> "上次检查更新 ${relTime(UpdateTracker.lastCheckAt)}"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (continueList.isNotEmpty()) {
                item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("继续观看", style = MaterialTheme.typography.titleSmall)
                        LazyRow(
                            contentPadding = PaddingValues(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(continueList, key = { "c|" + it.item.key }) { e ->
                                ContinueCard(e, baseUrls[e.item.ruleId].orEmpty(), news[e.item.key] ?: 0) { onResume(e) }
                            }
                        }
                    }
                }
                item(key = "allTitle", span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (type == "all") "全部" else typeLabel(type), style = MaterialTheme.typography.titleSmall)
                }
            }
            items(shown, key = { it.item.key }) { e ->
                Box {
                    Column(Modifier.combinedClickable(onClick = { onResume(e) }, onLongClick = { menuFor = e.item.key })) {
                        Box {
                            Cover(e.item.cover, baseUrls[e.item.ruleId].orEmpty(), Modifier.fillMaxWidth().aspectRatio(0.72f))
                            val n = news[e.item.key] ?: 0
                            if (n > 0) NewBadge(n, Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                        Text(e.item.title, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(typeLabel(e.item.contentType) + " · " + progressLabel(e), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = menuFor == e.item.key, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("继续") }, onClick = { menuFor = null; onResume(e) })
                        DropdownMenuItem(text = { Text("详情") }, onClick = { menuFor = null; onDetail(e) })
                        DropdownMenuItem(text = { Text("移出书架") }, onClick = {
                            menuFor = null
                            scope.launch { Library.remove(e.item.key); UpdateTracker.forget(e.item.key) }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueCard(e: ShelfEntry, referer: String, news: Int, onClick: () -> Unit) {
    Column(Modifier.width(104.dp).clip(RoundedCornerShape(6.dp)).combinedClickableCompat(onClick)) {
        Box {
            Cover(e.item.cover, referer, Modifier.fillMaxWidth().aspectRatio(0.72f))
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                Text(progressLabel(e), color = Color.White, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            if (news > 0) NewBadge(news, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(e.item.title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.combinedClickable(onClick = onClick)

@Composable
private fun NewBadge(n: Int, modifier: Modifier) {
    Text(
        if (n > 99) "更新 99+" else "更新 $n",
        color = Color.White, fontSize = 10.sp, lineHeight = 12.sp,
        modifier = modifier
            .background(MaterialTheme.colorScheme.error, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
