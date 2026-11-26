package com.example.mediahub.ui.logs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediahub.util.AppLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val all by AppLog.entries.collectAsState()
    var level by remember { mutableStateOf('A') }
    var filter by remember { mutableStateOf("") }
    val clip = LocalClipboardManager.current
    val shown = remember(all, level, filter) {
        all.asReversed().filter { e ->
            (level == 'A' || (level == 'W' && e.level != 'I') || (level == 'E' && e.level == 'E')) &&
                (filter.isBlank() || e.tag.contains(filter, true) || e.msg.contains(filter, true))
        }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("日志（${shown.size}）") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                IconButton(onClick = { clip.setText(AnnotatedString(shown.asReversed().joinToString("\n"))) }) {
                    Icon(Icons.Default.ContentCopy, "复制") }
                IconButton(onClick = { AppLog.clear() }) { Icon(Icons.Default.DeleteSweep, "清空") }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                singleLine = true, placeholder = { Text("按标签或内容过滤") })
            LazyRow(contentPadding = PaddingValues(12.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf('A' to "全部", 'W' to "警告及以上", 'E' to "错误")) { (k, n) ->
                    FilterChip(level == k, { level = k }, label = { Text(n) })
                }
            }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    items(shown) { e ->
                        Text(e.toString(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                            color = when (e.level) {
                                'E' -> MaterialTheme.colorScheme.error
                                'W' -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurface
                            }, modifier = Modifier.padding(vertical = 3.dp))
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
