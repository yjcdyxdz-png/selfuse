package com.example.mediahub.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediahub.cast.CastController
import com.example.mediahub.cast.CastState
import com.example.mediahub.cast.CastTarget
import com.example.mediahub.cast.Dlna
import com.example.mediahub.cast.DlnaDevice
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 投屏中：代替视频画面显示的控制面板 */
@Composable
fun CastPanel(
    modifier: Modifier,
    cast: CastState,
    episodeTitle: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    busy: Boolean,
    error: String?,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onOpenSheet: () -> Unit,
) {
    val clip = LocalClipboardManager.current
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    val browser = cast.target == CastTarget.Browser
    val pcOnline = browser && now - cast.seenAt < 4000

    Box(modifier.background(Color(0xFF111418))) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpenSheet)) {
                Icon(Icons.Default.CastConnected, null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("正在投屏到 ${cast.targetName}", color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
            if (browser) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cast.pageUrl, color = Color(0xFF8AB4F8), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { clip.setText(AnnotatedString(cast.pageUrl)) }) {
                        Icon(Icons.Default.ContentCopy, "复制地址", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                    }
                }
                Text(if (pcOnline) "电脑已连接" else "在电脑浏览器（Chrome / Edge）打开上面的地址",
                    color = if (pcOnline) Color(0xFF81C995) else Color.LightGray, style = MaterialTheme.typography.labelMedium)
            }
            Text(episodeTitle, color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            val err = error ?: cast.error
            if (err != null) Text(err, color = Color(0xFFFF8A80), style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (busy || cast.sending) LinearProgressIndicator(Modifier.fillMaxWidth(0.6f).padding(top = 6.dp))

            if (cast.durationMs > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(if (seeking) seekValue.toLong() else cast.positionMs), color = Color.White, fontSize = 12.sp)
                Slider(
                    value = (if (seeking) seekValue else cast.positionMs.toFloat()).coerceIn(0f, cast.durationMs.toFloat()),
                    onValueChange = { seeking = true; seekValue = it },
                    onValueChangeFinished = { CastController.seek(seekValue.toLong()); seeking = false },
                    valueRange = 0f..cast.durationMs.toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)),
                )
                Text(fmtTime(cast.durationMs), color = Color.White, fontSize = 12.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconButton(onClick = onPrev, enabled = hasPrev) {
                    Icon(Icons.Default.SkipPrevious, "上一集", tint = if (hasPrev) Color.White else Color.Gray)
                }
                FilledIconButton(onClick = { CastController.togglePlay() }, modifier = Modifier.size(52.dp)) {
                    Icon(if (cast.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (cast.playing) "暂停" else "播放")
                }
                IconButton(onClick = onNext, enabled = hasNext) {
                    Icon(Icons.Default.SkipNext, "下一集", tint = if (hasNext) Color.White else Color.Gray)
                }
                if (err != null) TextButton(onClick = onRetry) { Text("重试") }
                OutlinedButton(onClick = { CastController.stop() }) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("停止投屏")
                }
            }
        }
    }
}

/** 选择投屏方式 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    val cast by CastController.state.collectAsState()
    var devices by remember { mutableStateOf<List<DlnaDevice>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    val search = {
        scope.launch {
            searching = true; err = null
            runCatching { Dlna.discover(ctx) }
                .onSuccess { devices = it }
                .onFailure { err = it.message ?: it.toString() }
            searching = false; searched = true
        }
        Unit
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("投屏到电脑", style = MaterialTheme.typography.titleMedium)
            Text("手机和电脑要连同一个 Wi-Fi（电脑连手机热点也可以）。投屏时视频经手机转发，防盗链的源也能播。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (cast.target != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("正在投屏到 ${cast.targetName}", Modifier.weight(1f))
                        TextButton(onClick = { CastController.stop(); onDismiss() }) { Text("停止投屏") }
                    }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Computer, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("电脑浏览器", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        Text("推荐 · 不用装软件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("点「开始」后，在电脑的 Chrome 或 Edge 打开显示的地址。手机这边可以暂停、拖进度、换集。",
                        style = MaterialTheme.typography.bodySmall)
                    if (cast.target == CastTarget.Browser) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(cast.pageUrl, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { clip.setText(AnnotatedString(cast.pageUrl)) }) {
                                Icon(Icons.Default.ContentCopy, "复制地址")
                            }
                        }
                    } else Button(onClick = {
                        runCatching { CastController.startBrowser(ctx) }.onFailure { err = it.message }
                    }) { Text("开始") }
                }
            }

            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Tv, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("DLNA 播放器", style = MaterialTheme.typography.titleSmall)
                    }
                    Text("电脑上装一个 DLNA 接收软件（例如开源的 Macast），也可以投到电视或盒子。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = search, enabled = !searching) { Text(if (searching) "正在搜索…" else "搜索设备") }
                    if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    devices.forEach { d ->
                        val connected = (cast.target as? CastTarget.Dlna)?.device?.let { it.udn == d.udn && it.location == d.location } == true
                        ListItem(
                            headlineContent = { Text(d.name) },
                            supportingContent = { Text(d.host) },
                            trailingContent = { if (connected) Text("已连接", color = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable {
                                runCatching { CastController.startDlna(ctx, d) }
                                    .onSuccess { onDismiss() }
                                    .onFailure { err = it.message }
                            },
                        )
                    }
                    if (searched && !searching && devices.isEmpty()) Text(
                        "没有找到设备。请确认：接收软件已经打开；电脑和手机在同一个 Wi-Fi；" +
                            "Windows 的网络类型是「专用网络」（公用网络会挡住设备搜索），并且防火墙允许了接收软件。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
