package com.example.mediahub.ui.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.ui.common.typeLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleImportScreen(
    onTest: (RuleEntity) -> Unit,
    onLogs: () -> Unit,
    vm: RuleImportViewModel = viewModel(),
) {
    val rules by vm.rules.collectAsState()
    val busy by vm.busy.collectAsState()
    val msg by vm.message.collectAsState()
    var pendingDelete by remember { mutableStateOf<RuleEntity?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.import(uris)
    }
    val mimes = arrayOf("application/zip", "application/x-zip-compressed", "application/json",
        "text/plain", "application/octet-stream")

    Scaffold(
        topBar = { TopAppBar(title = { Text("规则管理") }, actions = {
            IconButton(onClick = onLogs) { Icon(Icons.Default.Terminal, "日志") }
        }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { picker.launch(mimes) },
                icon = { Icon(Icons.Default.FileOpen, null) }, text = { Text("从本地导入") })
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("尚未导入任何规则\n支持：模块 zip（manifest.json + main.js）、Legado 书源 JSON\n点击规则可进入测试页",
                        style = MaterialTheme.typography.bodyMedium)
                }
            } else LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                items(rules, key = { it.id }) { r ->
                    ListItem(
                        modifier = Modifier.clickable { onTest(r) },
                        headlineContent = { Text(r.name) },
                        supportingContent = {
                            Text(listOf(
                                if (r.kind == RuleKind.JS_MODULE) "JS 模块" else "书源",
                                typeLabel(r.contentType), r.version, r.baseUrl
                            ).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 1)
                        },
                        leadingContent = {
                            Icon(when (r.contentType) {
                                "comic" -> Icons.Default.Image
                                "novel" -> Icons.Default.MenuBook
                                else -> Icons.Default.Movie
                            }, null)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = r.enabled, onCheckedChange = { vm.toggle(r) })
                                IconButton(onClick = { pendingDelete = r }) { Icon(Icons.Default.Delete, "删除") }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    msg?.let { AlertDialog(onDismissRequest = vm::dismiss, confirmButton = {
        TextButton(onClick = vm::dismiss) { Text("确定") } }, title = { Text("导入结果") }, text = { Text(it) }) }

    pendingDelete?.let { r -> AlertDialog(onDismissRequest = { pendingDelete = null },
        title = { Text("删除规则") }, text = { Text("确定删除「${r.name}」？") },
        confirmButton = { TextButton(onClick = { vm.delete(r); pendingDelete = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }) }
}

