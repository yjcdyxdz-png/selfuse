package com.example.mediahub.ui.settings

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
            SectionTitle("关于")
            ListItem(headlineContent = { Text("版本") }, supportingContent = { Text(version.ifBlank { "未知" }) })
        }
    }
}

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
