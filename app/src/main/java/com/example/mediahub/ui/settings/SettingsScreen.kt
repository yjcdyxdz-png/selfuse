package com.example.mediahub.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mediahub.data.AppSettings
import com.example.mediahub.data.BackupManager
import com.example.mediahub.data.UpdateTracker
import com.example.mediahub.data.VideoPrefs
import com.example.mediahub.net.CookieStore
import com.example.mediahub.novel.NovelCache
import com.example.mediahub.ui.common.relTime
import com.example.mediahub.ui.detail.DetailCache
import com.example.mediahub.ui.player.SPEEDS
import com.example.mediahub.ui.player.fmtSpeed
import com.example.mediahub.util.DiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val modeFlow = remember { AppSettings.themeMode(app) }
    val mode by modeFlow.collectAsState(initial = AppSettings.THEME_SYSTEM)
    val videoFlow = remember { AppSettings.videoPrefs(app) }
    val video by videoFlow.collectAsState(initial = VideoPrefs())
    val autoFlow = remember { AppSettings.shelfAutoCheck(app) }
    val autoCheck by autoFlow.collectAsState(initial = true)
    var cacheSize by remember { mutableLongStateOf(-1L) }
    var cookieCount by remember { mutableIntStateOf(-1) }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    LaunchedEffect(Unit) {
        val (size, count) = withContext(Dispatchers.IO) { DiskCache.sizeBytes() to CookieStore.count() }
        cacheSize = size
        cookieCount = count
    }

    // ---- 备份与恢复 ----
    var working by remember { mutableStateOf<String?>(null) }
    var resultTitle by remember { mutableStateOf("") }
    var resultText by remember { mutableStateOf<String?>(null) }
    var exportDialog by remember { mutableStateOf(false) }
    var exportOpt by remember { mutableStateOf(BackupManager.Options()) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreSummary by remember { mutableStateOf<BackupManager.Summary?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = "正在导出备份…"
            runCatching { BackupManager.export(app, uri, exportOpt) }
                .onSuccess { s ->
                    resultTitle = "导出完成"
                    resultText = buildString {
                        if (exportOpt.rules) append("规则 ${s.rules} 个\n")
                        if (exportOpt.library) append("书架 ${s.shelf} 部，阅读进度 ${s.progress} 条\n")
                        if (exportOpt.settings) append("App 和阅读器设置\n")
                        if (exportOpt.cookies) append("Cookie（含登录状态，请勿分享这个文件）\n")
                        append("\n换手机时把这个 zip 拷过去，在 设置 → 从备份恢复 里选择它")
                    }
                }
                .onFailure { resultTitle = "导出失败"; resultText = it.message ?: it.toString() }
            working = null
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = "正在读取备份…"
            runCatching { BackupManager.inspect(app, uri) }
                .onSuccess { restoreUri = uri; restoreSummary = it }
                .onFailure { resultTitle = "无法恢复"; resultText = it.message ?: it.toString() }
            working = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("外观")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AppSettings.THEME_SYSTEM to "跟随系统", AppSettings.THEME_LIGHT to "浅色", AppSettings.THEME_DARK to "深色")
                    .forEach { (m, name) ->
                        FilterChip(selected = mode == m, onClick = { scope.launch { AppSettings.setThemeMode(app, m) } },
                            label = { Text(name) })
                    }
            }
            Hint("小说阅读页的背景和夜间模式在阅读设置里单独调整")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("书架")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("打开书架时自动检查更新", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(autoCheck, { v -> scope.launch { AppSettings.setShelfAutoCheck(app, v) } })
            }
            Hint("距上次检查超过 30 分钟才会自动检查，也可以在书架右上角手动刷新。上次检查：${relTime(UpdateTracker.lastCheckAt)}")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("视频")
            Text("默认倍速", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { s ->
                    FilterChip(selected = video.speed == s, onClick = { scope.launch { AppSettings.setVideoSpeed(app, s) } },
                        label = { Text("${fmtSpeed(s)}x") })
                }
            }
            Text("长按倍速", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1f to "关闭", 1.5f to "1.5x", 2f to "2.0x", 3f to "3.0x").forEach { (s, name) ->
                    FilterChip(selected = video.longPressSpeed == s,
                        onClick = { scope.launch { AppSettings.setLongPressSpeed(app, s) } }, label = { Text(name) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("竖屏短剧上下滑切集", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(video.swipeEpisodes, { v -> scope.launch { AppSettings.setSwipeEpisodes(app, v) } })
            }
            Hint("播放手势：单击显示控制栏，双击暂停，长按临时倍速；左右滑快进快退；左半屏上下滑调亮度、右半屏调音量。" +
                "竖屏短剧开启切集后，上下滑改为切换上一集 / 下一集。跳过片头片尾在播放器右上角 ⏱ 按钮里按剧设置")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("缓存")
            ListItem(
                headlineContent = { Text("详情、目录和正文缓存") },
                supportingContent = { Text(if (cacheSize < 0) "计算中…" else formatSize(cacheSize)) },
                trailingContent = {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { DiskCache.clear() }
                            DetailCache.clearMemory()
                            NovelCache.clearMemory()
                            cacheSize = 0
                            snack.showSnackbar("缓存已清除")
                        }
                    }) { Text("清除") }
                },
            )
            Hint("打开过的详情和目录会先显示缓存，超过 30 分钟会在后台自动更新。清除缓存不影响书架和阅读进度")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Cookie")
            ListItem(
                headlineContent = { Text("已保存的 Cookie") },
                supportingContent = { Text(if (cookieCount < 0) "读取中…" else "$cookieCount 条") },
                trailingContent = {
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { CookieStore.clear() }
                            cookieCount = 0
                            snack.showSnackbar("Cookie 已清除")
                        }
                    }) { Text("清除") }
                },
            )
            Hint("网站下发的 Cookie 会自动保存，重启 App 后仍然有效。清除后需要登录的源要重新登录")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("备份与恢复")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportDialog = true }, enabled = working == null) { Text("导出备份") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    enabled = working == null,
                ) { Text("从备份恢复") }
            }
            working?.let {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            Hint("备份是一个 zip 文件，包含导入的规则、书架、阅读进度和设置，不包含缓存。换手机或重装 App 后选择它即可恢复")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("关于")
            ListItem(headlineContent = { Text("版本") }, supportingContent = { Text(version.ifBlank { "未知" }) })
        }
    }

    if (exportDialog) {
        var o by remember { mutableStateOf(exportOpt) }
        AlertDialog(
            onDismissRequest = { exportDialog = false },
            title = { Text("导出备份") },
            text = {
                Column {
                    CheckRow("规则（模块和书源文件、检测结果）", o.rules) { o = o.copy(rules = it) }
                    CheckRow("书架、阅读进度、片头片尾设置", o.library) { o = o.copy(library = it) }
                    CheckRow("App 设置和阅读器设置", o.settings) { o = o.copy(settings = it) }
                    CheckRow("Cookie（网站登录状态）", o.cookies) { o = o.copy(cookies = it) }
                    if (o.cookies) Hint("Cookie 相当于登录凭证，包含它的备份文件不要分享给别人")
                }
            },
            confirmButton = {
                TextButton(enabled = o.any, onClick = {
                    exportOpt = o
                    exportDialog = false
                    exportLauncher.launch(BackupManager.defaultFileName())
                }) { Text("选择保存位置") }
            },
            dismissButton = { TextButton(onClick = { exportDialog = false }) { Text("取消") } },
        )
    }

    restoreSummary?.let { sum ->
        var o by remember(sum) {
            mutableStateOf(BackupManager.Options(
                rules = sum.rules > 0,
                library = sum.shelf > 0 || sum.progress > 0,
                settings = sum.hasSettings,
                cookies = sum.hasCookies,
            ))
        }
        AlertDialog(
            onDismissRequest = { restoreSummary = null },
            title = { Text("从备份恢复") },
            text = {
                Column {
                    Text(
                        "备份时间：" + fmtDate(sum.createdAt) + (if (sum.appVersion.isNotBlank()) " · 版本 ${sum.appVersion}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (sum.rules > 0) CheckRow("规则 ${sum.rules} 个", o.rules) { o = o.copy(rules = it) }
                    if (sum.shelf > 0 || sum.progress > 0)
                        CheckRow("书架 ${sum.shelf} 部 · 阅读进度 ${sum.progress} 条", o.library) { o = o.copy(library = it) }
                    if (sum.hasSettings) CheckRow("App 设置和阅读器设置", o.settings) { o = o.copy(settings = it) }
                    if (sum.hasCookies) CheckRow("Cookie（网站登录状态）", o.cookies) { o = o.copy(cookies = it) }
                    Spacer(Modifier.height(8.dp))
                    Hint("恢复是合并：备份里的内容会加进来，同一个规则或作品以备份为准，本机多出来的不会删除；阅读进度保留较新的那一条")
                }
            },
            confirmButton = {
                TextButton(enabled = o.any, onClick = {
                    val u = restoreUri
                    val opt = o
                    restoreSummary = null
                    if (u != null) scope.launch {
                        working = "正在恢复…"
                        runCatching { BackupManager.restore(app, u, opt) }
                            .onSuccess { r ->
                                resultTitle = if (r.errors.isEmpty()) "恢复完成" else "恢复完成（部分失败）"
                                resultText = buildString {
                                    if (opt.rules) append("规则 ${r.rules} 个\n")
                                    if (opt.library) {
                                        append("书架 ${r.shelf} 部，阅读进度 ${r.progress} 条")
                                        if (r.progressKept > 0) append("（${r.progressKept} 条本机更新，已保留）")
                                        append("\n")
                                    }
                                    if (r.settings) append("设置已恢复\n")
                                    if (r.cookies) append("Cookie 已恢复\n")
                                    if (r.errors.isNotEmpty()) {
                                        append("\n失败 ${r.errors.size} 项：\n")
                                        append(r.errors.take(10).joinToString("\n"))
                                    }
                                }
                                cookieCount = withContext(Dispatchers.IO) { CookieStore.count() }
                            }
                            .onFailure { resultTitle = "恢复失败"; resultText = it.message ?: it.toString() }
                        working = null
                    }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { restoreSummary = null }) { Text("取消") } },
        )
    }

    resultText?.let { t ->
        AlertDialog(
            onDismissRequest = { resultText = null },
            title = { Text(resultTitle) },
            text = { Text(t.trim()) },
            confirmButton = { TextButton(onClick = { resultText = null }) { Text("确定") } },
        )
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun fmtDate(t: Long): String =
    if (t <= 0) "未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t))

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun formatSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1048576.0)
}
