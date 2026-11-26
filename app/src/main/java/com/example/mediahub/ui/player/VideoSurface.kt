package com.example.mediahub.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

fun fmtSpeed(f: Float): String =
    if (f % 1f == 0f) "${f.toInt()}.0" else "%.2f".format(f).trimEnd('0')

fun fmtTime(ms: Long): String {
    val s = ms.coerceAtLeast(0) / 1000
    val h = s / 3600
    val m = s / 60 % 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/**
 * 视频画面 + 自绘控制栏 + 手势：
 * - 单击显示/隐藏控制栏，双击暂停/播放，长按临时倍速（松手恢复）
 * - 左右滑：快进快退
 * - 上下滑：左半屏调亮度，右半屏调音量；短剧模式下改为上滑下一集、下滑上一集
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    player: Player,
    modifier: Modifier,
    title: String,
    fullscreen: Boolean,
    shortDrama: Boolean,
    speed: Float,
    longPressSpeed: Float,
    hasPrev: Boolean,
    hasNext: Boolean,
    busy: Boolean,
    error: String?,
    hint: String?,
    onRetry: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSkipSettings: () -> Unit,
    onToggleFullscreen: () -> Unit,
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val haptic = LocalHapticFeedback.current
    val audio = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // ---- 播放状态（轮询，足够流畅且不用管理监听器） ----
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (isActive) {
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            position = player.currentPosition.coerceAtLeast(0L)
            duration = player.duration.coerceAtLeast(0L)
            delay(250)
        }
    }

    // ---- 界面状态 ----
    var showControls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    var gestureText by remember { mutableStateOf<String?>(null) }
    var longPressing by remember { mutableStateOf(false) }

    // 正在播放且 3.5 秒没有操作时自动隐藏控制栏
    LaunchedEffect(showControls, poke, playing, seeking, menuOpen) {
        if (showControls && playing && !seeking && !menuOpen) {
            delay(3500)
            showControls = false
        }
    }
    // 倍速（长按期间不覆盖）
    LaunchedEffect(speed) { if (!longPressing) player.setPlaybackSpeed(speed) }

    // 手势里读取最新的参数
    val speedNow by rememberUpdatedState(speed)
    val longSpeedNow by rememberUpdatedState(longPressSpeed)
    val shortDramaNow by rememberUpdatedState(shortDrama)
    val hasPrevNow by rememberUpdatedState(hasPrev)
    val hasNextNow by rememberUpdatedState(hasNext)
    val onPrevNow by rememberUpdatedState(onPrev)
    val onNextNow by rememberUpdatedState(onNext)

    fun readBrightness(): Float {
        val b = activity?.window?.attributes?.screenBrightness ?: -1f
        if (b >= 0f) return b
        return runCatching { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
            .getOrDefault(0.5f).coerceIn(0.01f, 1f)
    }

    fun writeBrightness(v: Float) {
        val w = activity?.window ?: return
        val lp = w.attributes
        lp.screenBrightness = v.coerceIn(0.01f, 1f)
        w.attributes = lp
    }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // 按原始比例完整显示，不拉伸、不裁剪
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- 手势层 ----
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { showControls = !showControls; poke++ },
                        onDoubleTap = {
                            if (player.isPlaying) player.pause() else player.play()
                            showControls = true; poke++
                        },
                        onLongPress = {
                            val ls = longSpeedNow
                            if (ls > 1f && player.isPlaying) {
                                longPressing = true
                                player.setPlaybackSpeed(ls)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (longPressing) {
                                longPressing = false
                                player.setPlaybackSpeed(speedNow)
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    // 0 未定 / 1 进度 / 2 亮度 / 3 音量 / 4 切集 / -1 忽略
                    var mode = 0
                    var acc = Offset.Zero
                    var startX = 0f
                    var startPos = 0L
                    var target = 0L
                    var startBright = 0.5f
                    var startVol = 0
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    detectDragGestures(
                        onDragStart = { o -> mode = 0; acc = Offset.Zero; startX = o.x },
                        onDragCancel = { mode = 0; gestureText = null },
                        onDragEnd = {
                            when (mode) {
                                1 -> player.seekTo(target)
                                4 -> {
                                    val th = size.height * 0.15f
                                    if (acc.y < -th && hasNextNow) onNextNow()
                                    else if (acc.y > th && hasPrevNow) onPrevNow()
                                }
                            }
                            mode = 0
                            gestureText = null
                        },
                        onDrag = onDrag@{ change, d ->
                            if (longPressing) return@onDrag
                            change.consume()
                            acc += d
                            if (mode == 0) {
                                if (abs(acc.x) < 16f && abs(acc.y) < 16f) return@onDrag
                                mode = when {
                                    abs(acc.x) > abs(acc.y) -> if (player.duration > 0) 1 else -1
                                    shortDramaNow -> 4
                                    startX < size.width / 2f -> 2
                                    else -> 3
                                }
                                startPos = player.currentPosition
                                target = startPos
                                startBright = readBrightness()
                                startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                            }
                            when (mode) {
                                1 -> {
                                    val dur = player.duration
                                    // 滑满一屏宽度：最多 3 分钟（短视频按 1 分钟算）
                                    val span = dur.coerceIn(60_000L, 180_000L)
                                    target = (startPos + acc.x / size.width * span).toLong().coerceIn(0L, dur)
                                    val delta = (target - startPos) / 1000
                                    gestureText = "${fmtTime(target)} / ${fmtTime(dur)}\n" +
                                        (if (delta >= 0) "+" else "") + "$delta 秒"
                                }
                                2 -> {
                                    val v = (startBright - acc.y / size.height).coerceIn(0.01f, 1f)
                                    writeBrightness(v)
                                    gestureText = "亮度 ${(v * 100).roundToInt()}%"
                                }
                                3 -> {
                                    val v = (startVol - acc.y / size.height * maxVol * 1.2f).roundToInt().coerceIn(0, maxVol)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    gestureText = "音量 ${v * 100 / maxVol}%"
                                }
                                4 -> {
                                    val th = size.height * 0.15f
                                    gestureText = when {
                                        acc.y < -th -> if (hasNextNow) "↑ 松手播放下一集" else "已经是最后一集"
                                        acc.y > th -> if (hasPrevNow) "↓ 松手播放上一集" else "已经是第一集"
                                        else -> "上滑下一集 · 下滑上一集"
                                    }
                                }
                            }
                        },
                    )
                },
        )

        if ((busy || buffering) && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        }

        gestureText?.let {
            Text(
                it, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (longPressing) InfoBadge("⏩ ${fmtSpeed(longPressSpeed)}x 倍速播放中")
            hint?.let { InfoBadge(it) }
        }

        // ---- 控制栏 ----
        AnimatedVisibility(
            visible = showControls && error == null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent)))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (fullscreen) IconButton(onClick = onToggleFullscreen) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "退出全屏", tint = Color.White)
                    }
                    Text(
                        if (fullscreen) title else "", color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("${fmtSpeed(speed)}x", color = Color.White) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; poke++ }) {
                            SPEEDS.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("${fmtSpeed(s)}x" + if (s == speed) "  ✓" else "") },
                                    onClick = { menuOpen = false; onSpeed(s); poke++ },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onSkipSettings(); poke++ }) {
                        Icon(Icons.Default.Timer, "跳过片头片尾", tint = Color.White)
                    }
                }

                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onPrev(); poke++ }, enabled = hasPrev) {
                        Icon(Icons.Default.SkipPrevious, "上一集", modifier = Modifier.size(34.dp),
                            tint = if (hasPrev) Color.White else Color.Gray)
                    }
                    if (busy || buffering) Spacer(Modifier.size(64.dp))
                    else IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play(); poke++ },
                        modifier = Modifier.size(64.dp),
                    ) {
                        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(52.dp))
                    }
                    IconButton(onClick = { onNext(); poke++ }, enabled = hasNext) {
                        Icon(Icons.Default.SkipNext, "下一集", modifier = Modifier.size(34.dp),
                            tint = if (hasNext) Color.White else Color.Gray)
                    }
                }

                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA000000))))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val max = duration.coerceAtLeast(1L).toFloat()
                    Text(fmtTime(if (seeking) seekValue.toLong() else position), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = (if (seeking) seekValue else position.toFloat()).coerceIn(0f, max),
                        onValueChange = { seeking = true; seekValue = it; poke++ },
                        onValueChangeFinished = { player.seekTo(seekValue.toLong()); seeking = false; poke++ },
                        valueRange = 0f..max,
                        enabled = duration > 0,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White, activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Text(fmtTime(duration), color = Color.White, fontSize = 12.sp)
                    IconButton(onClick = { onToggleFullscreen(); poke++ }) {
                        Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            if (fullscreen) "退出全屏" else "全屏", tint = Color.White)
                    }
                }
            }
        }

        error?.let {
            Column(
                Modifier.align(Alignment.Center).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(it, color = Color.White, textAlign = TextAlign.Center)
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun InfoBadge(text: String) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** 跳过片头片尾设置（本剧所有集通用） */
@Composable
fun SkipDialog(
    introMs: Long,
    outroMs: Long,
    positionMs: Long,
    durationMs: Long,
    onSave: (introMs: Long, outroMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var intro by remember { mutableFloatStateOf((introMs / 1000f).coerceIn(0f, 300f)) }
    var outro by remember { mutableFloatStateOf((outroMs / 1000f).coerceIn(0f, 300f)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳过片头片尾") },
        text = {
            Column {
                Text("对本剧所有集生效", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Text(if (intro < 1f) "片头：不跳过" else "片头：跳过开头 ${intro.roundToInt()} 秒")
                Slider(intro, { intro = it }, valueRange = 0f..300f, steps = 59)
                TextButton(onClick = { intro = (positionMs / 1000f).coerceIn(0f, 300f) }) {
                    Text("片头到当前位置（${fmtTime(positionMs)}）")
                }
                Spacer(Modifier.height(8.dp))
                Text(if (outro < 1f) "片尾：不跳过" else "片尾：跳过最后 ${outro.roundToInt()} 秒")
                Slider(outro, { outro = it }, valueRange = 0f..300f, steps = 59)
                TextButton(
                    onClick = { outro = ((durationMs - positionMs) / 1000f).coerceIn(0f, 300f) },
                    enabled = durationMs > positionMs,
                ) { Text("片尾从当前位置开始") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(intro.roundToInt() * 1000L, outro.roundToInt() * 1000L) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(0L, 0L) }) { Text("清除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
