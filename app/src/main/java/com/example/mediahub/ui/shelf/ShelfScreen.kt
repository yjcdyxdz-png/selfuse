package com.example.mediahub.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mediahub.App
import com.example.mediahub.data.Library
import com.example.mediahub.data.ShelfEntry
import com.example.mediahub.ui.common.Cover
import com.example.mediahub.ui.common.typeLabel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun progressLabel(e: ShelfEntry): String {
    val p = e.progress ?: return "未开始"
    return when (e.item.contentType) {
        "video" -> "看到 " + p.chapterTitle.ifBlank { "第${p.chapterIndex + 1}集" } + " " + fmtTime(p.position)
        "comic" -> "读到 第${p.position + 1}页"
        else -> "读到 " + p.chapterTitle.ifBlank { "第${p.chapterIndex + 1}章" }
    }
}

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(onResume: (ShelfEntry) -> Unit, onDetail: (ShelfEntry) -> Unit) {
    val entries by remember { Library.observeShelf() }.collectAsState(initial = null)
    val baseUrls by remember { App.instance.rules.observe().map { l -> l.associate { it.id to it.baseUrl } } }
        .collectAsState(initial = emptyMap())
    val scope = rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<String?>(null) }

    val list = entries ?: return
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("书架是空的\n在详情页点 ☆ 加入书架", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list, key = { it.item.key }) { e ->
            Box {
                Column(Modifier.combinedClickable(onClick = { onResume(e) }, onLongClick = { menuFor = e.item.key })) {
                    Cover(e.item.cover, baseUrls[e.item.ruleId].orEmpty(), Modifier.fillMaxWidth().aspectRatio(0.72f))
                    Text(e.item.title, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(typeLabel(e.item.contentType) + " · " + progressLabel(e), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = menuFor == e.item.key, onDismissRequest = { menuFor = null }) {
                    DropdownMenuItem(text = { Text("继续") }, onClick = { menuFor = null; onResume(e) })
                    DropdownMenuItem(text = { Text("详情") }, onClick = { menuFor = null; onDetail(e) })
                    DropdownMenuItem(text = { Text("移出书架") }, onClick = {
                        menuFor = null; scope.launch { Library.remove(e.item.key) }
                    })
                }
            }
        }
    }
}
