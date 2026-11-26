package com.example.mediahub.ui.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.data.AppSettings
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleHealthInfo
import com.example.mediahub.data.RuleKind
import com.example.mediahub.ui.common.fmtDuration
import com.example.mediahub.ui.common.relTime
import com.example.mediahub.ui.common.typeLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleImportScreen(
    onTest: (RuleEntity) -> Unit,
    onLogs: () -> Unit,
    onSettings: () -> Unit = {},
    vm: RuleImportViewModel = viewModel(),
) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val rules by vm.rules.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.message.collectAsState()
    val health by vm.health.collectAsState()
    val checking by vm.checking.collectAsState()
    var pendingDelete by remember { mutableStateOf<RuleEntity?>(null) }
    var askCheck by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("all") }
    val kwFlow = remember { AppSettings.healthKeyword(app) }
    val savedKw by kwFlow.collectAsState(initial = "我")

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris)
    }
    val mimes = arrayOf("application/zip", "application/x-zip-compressed", "application/json",
        "text/plain", "application/octet-stream")

    val failed = remember(rules, health) { rules.filter { health[it.id]?.ok == false } }
    val disabled = remember(rules) { rules.filter { !it.enabled } }
    val typeCounts = remember(rules) { rules.groupingBy { it.contentType }.eachCount() }
    val shown = remember(rules, health, filter) {
        when (filter) {
            "all" -> rules
            "failed" -> failed
            "untested" -> rules.filter { health[it.id] == null }
            "disabled" -> disabled
            else -> rules.filter { it.contentType == filter }
        }
    }
    // 当前筛选被清空时回到「全部」
    LaunchedEffect(shown.size, filter) { if (filter != "all" && shown.isEmpty()) filter = "all" }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("规则 ${rules.size}") }, actions = {
                if (checking != null) IconButton(onClick = vm::cancelCheck) { Icon(Icons.Default.Close, "停止检测") }
                else IconButton(onClick = { askCheck = true }, enabled = rules.isNotEmpty()) {
                    Icon(Icons.Default.NetworkCheck, "检测全部")
                }
                IconButton(onClick = onLogs) { Icon(Icons.Default.Terminal, "日志") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "设置") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(mimes) },
                icon = { Icon(Icons.Default.FileOpen, null) }, text = { Text("从本地导入") })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            checking?.let { (done, total) ->
                LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                Text("正在检测 $done/$total，结果会陆续显示在卡片上", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("尚未导入任何规则\n支持：模块 zip（manifest.json + main.js）、Legado 书源 JSON\n点击规则可进入测试页",
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                return@Column
            }
            val chips = buildList {
                add("all" to "全部 ${rules.size}")
                listOf("video" to "视频", "comic" to "漫画", "novel" to "小说").forEach { (k, n) ->
                    typeCounts[k]?.let { c -> add(k to "$n $c") }
                }
                if (failed.isNotEmpty()) add("failed" to "失败 ${failed.size}")
                val untested = rules.count { health[it.id] == null }
                if (untested in 1 until rules.size) add("untested" to "未测试 $untested")
                if (disabled.isNotEmpty()) add("disabled" to "已停用 ${disabled.size}")
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips, key = { it.first }) { (k, label) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) })
                }
            }
            if (filter == "failed" && failed.isNotEmpty() && checking == null) {
                TextButton(onClick = { vm.checkAll(savedKw, failed) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("重新检测失败的 ${failed.size} 个")
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { r ->
                    RuleCard(r, health[r.id], onClick = { onTest(r) }, onToggle = { vm.toggle(r) }, onDelete = { pendingDelete = r })
                }
            }
        }
    }

    if (askCheck) {
        var kw by remember { mutableStateOf(savedKw) }
        AlertDialog(
            onDismissRequest = { askCheck = false },
            title = { Text("检测全部规则") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("每个规则会请求第一页分类（书源用发现页）；没有分类或分类为空的，用下面的关键词搜索。每个最多等 30 秒。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(kw, { kw = it }, singleLine = true, label = { Text("搜索关键词") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    askCheck = false
                    scope.launch { AppSettings.setHealthKeyword(app, kw.trim()) }
                    vm.checkAll(kw)
                }) { Text("开始检测") }
            },
            dismissButton = { TextButton(onClick = { askCheck = false }) { Text("取消") } },
        )
    }

    msg?.let { AlertDialog(onDismissRequest = vm::dismiss, confirmButton = {
        TextButton(onClick = vm::dismiss) { Text("确定") } }, title = { Text("导入结果") }, text = { Text(it) }) }

    pendingDelete?.let { r -> AlertDialog(onDismissRequest = { pendingDelete = null },
        title = { Text("删除规则") }, text = { Text("确定删除「${r.name}」？") },
        confirmButton = { TextButton(onClick = { vm.delete(r); pendingDelete = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleCard(
    r: RuleEntity,
    h: RuleHealthInfo?,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(if (r.enabled) 1f else 0.6f)) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (r.contentType) {
                        "comic" -> Icons.Default.Image
                        "novel" -> Icons.Default.MenuBook
                        else -> Icons.Default.Movie
                    },
                    null, tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            if (r.kind == RuleKind.JS_MODULE) "JS 模块" else "书源",
                            typeLabel(r.contentType),
                            if (r.version.isNotBlank() && r.kind == RuleKind.JS_MODULE) "v${r.version}" else "",
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = r.enabled, onCheckedChange = { onToggle() })
            }
            if (r.baseUrl.isNotBlank()) Text(
                r.baseUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, end = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthLine(h, Modifier.weight(1f))
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
            }
        }
    }
}

@Composable
private fun HealthLine(h: RuleHealthInfo?, modifier: Modifier) {
    Column(modifier) {
        when {
            h == null -> Text("⚪ 未测试", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            h.ok -> Text(
                "✅ 正常" + (if (h.count > 0) " · ${h.count} 条" else "") + " · ${fmtDuration(h.ms)} · ${relTime(h.at)}" +
                    (if (h.via.isNotBlank()) "（${h.via}）" else ""),
                style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            else -> {
                Text(
                    "❌ 失败" + (if (h.step.isNotBlank()) "：${h.step}" else "") + " · ${relTime(h.at)}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (h.message.isNotBlank()) Text(
                    h.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
