package com.example.mediahub.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.example.mediahub.cast.CastController
import com.example.mediahub.cast.CastEvent
import com.example.mediahub.data.AppSettings
import com.example.mediahub.engine.MediaUrl
import com.example.mediahub.data.VideoPrefs
import com.example.mediahub.player.PlayerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(ruleId: String, sourceId: String, index: Int, onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel(key = "p|$ruleId|$sourceId", factory = PlayerViewModel.Factory(ruleId, sourceId, index))
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val activity = ctx as? Activity
    val scope = rememberCoroutineScope()
    val player = remember { PlayerFactory.create(ctx) }
    var fullscreen by remember { mutableStateOf(false) }
    // 视频实际宽高比（宽/高），未知时按 16:9
    var videoRatio by remember { mutableFloatStateOf(16f / 9f) }
    val prefsFlow = remember { AppSettings.videoPrefs(app) }
    val prefs by prefsFlow.collectAsState(initial = VideoPrefs())
    var showSkip by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 竖屏视频（短剧）：上下滑切集
    val shortDrama = prefs.swipeEpisodes && videoRatio < 0.9f
    // ---- 投屏 ----
    val cast by CastController.state.collectAsState()
    val casting = cast.target != null
    var showCast by remember { mutableStateOf(false) }
    // 手机播放器里当前装着的视频
    var localMedia by remember { mutableStateOf<MediaUrl?>(null) }
    val episodeTitle = ui.chapters.getOrNull(ui.index)?.title?.ifBlank { null } ?: "第${ui.index + 1}集"
    val castTitle = listOf(ui.title, episodeTitle).filter { it.isNotBlank() }.joinToString(" ")

    LaunchedEffect(vm) {
        vm.hints.collectLatest { hint = it; delay(2000); hint = null }
    }

    // 换集或开始/停止投屏时：投屏中就把视频发给投屏端，否则在手机上播放
    LaunchedEffect(ui.media, casting) {
        val m = ui.media ?: return@LaunchedEffect
        if (casting) {
            // 正在手机上看的视频接着当前位置投；新的一集从断点 / 片头之后开始
            val start = if (localMedia === m && player.currentMediaItem != null) player.currentPosition else ui.startPos
            player.pause()
            CastController.load(m, castTitle, start)
        } else {
            val resume = CastController.takeResumePosition(m.url)
            if (localMedia !== m) {
                player.setMediaSource(PlayerFactory.mediaSource(m, m.headerMap()))
                val start = resume ?: ui.startPos
                if (start > 0) player.seekTo(start)
                player.prepare()
                localMedia = m
            } else if (resume != null) {
                player.seekTo(resume)
            }
            player.playWhenReady = true
        }
    }
    // 开始投屏时退出全屏
    LaunchedEffect(casting) { if (casting) fullscreen = false }
    // 投屏端播完一集：自动下一集
    LaunchedEffect(vm) {
        CastController.events.collect { e ->
            if (e is CastEvent.Ended && CastController.active && vm.hasNext()) vm.next()
        }
    }

    // 断点续看：每 5 秒记录一次位置，暂停/离开页面时也记录（投屏时记录投屏端的位置）
    val savePos = {
        val c = CastController.state.value
        if (c.target != null) { if (c.media != null && c.positionMs > 0) vm.save(c.positionMs) }
        else if (player.currentMediaItem != null) vm.save(player.currentPosition)
        Unit
    }
    LaunchedEffect(player) {
        while (isActive) { delay(5_000); if (player.isPlaying || CastController.state.value.playing) savePos() }
    }

    // 跳过片尾：播到「总时长 - 片尾秒数」时直接下一集
    LaunchedEffect(player) {
        var firedFor = -1
        while (isActive) {
            delay(500)
            val s = vm.ui.value
            val out = s.outroMs
            val c = CastController.state.value
            val onCast = c.target != null
            val d = if (onCast) c.durationMs else player.duration
            val pos = if (onCast) c.positionMs else player.currentPosition
            val playing = if (onCast) c.playing else player.isPlaying
            if (out > 0 && d > out + 10_000 && playing && pos >= d - out && firedFor != s.index) {
                firedFor = s.index
                if (vm.hasNext()) { vm.hint("已跳过片尾"); vm.next() }
                else if (onCast) CastController.togglePlay() else player.pause()
            }
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !CastController.active && vm.hasNext()) vm.next()
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0)
                    videoRatio = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onPlayerError(error: PlaybackException) {
                vm.reportError("播放出错：${error.errorCodeName} ${error.cause?.message ?: ""}")
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_PAUSE) { savePos(); player.pause() } }
        owner.lifecycle.addObserver(obs)
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            activity?.window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                // 离开播放页恢复系统亮度
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
            setFullscreen(activity, false, true)
            savePos()
            player.release()
        }
    }
    // 竖屏视频（短剧）全屏时保持竖屏，横屏视频才切到横屏
    LaunchedEffect(fullscreen, videoRatio) { setFullscreen(activity, fullscreen, landscape = videoRatio >= 1f) }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    if (showSkip) {
        SkipDialog(
            introMs = ui.introMs, outroMs = ui.outroMs,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            onSave = { i, o ->
                vm.setSkip(i, o)
                showSkip = false
                vm.hint(if (i == 0L && o == 0L) "已清除片头片尾设置" else "已保存，本剧所有集生效")
            },
            onDismiss = { showSkip = false },
        )
    }

    if (showCast) CastSheet(onDismiss = { showCast = false })

    val videoBox: @Composable (Modifier) -> Unit = { mod ->
        if (casting) CastPanel(
            modifier = mod,
            cast = cast,
            episodeTitle = castTitle,
            hasPrev = ui.index > 0,
            hasNext = ui.index + 1 < ui.chapters.size,
            busy = ui.resolving,
            error = ui.error,
            onPrev = { vm.prev() },
            onNext = { vm.next() },
            onRetry = { vm.play(ui.index) },
            onOpenSheet = { showCast = true },
        ) else VideoSurface(
            player = player,
            modifier = mod,
            title = ui.chapters.getOrNull(ui.index)?.title.orEmpty(),
            fullscreen = fullscreen,
            shortDrama = shortDrama,
            speed = prefs.speed,
            longPressSpeed = prefs.longPressSpeed,
            hasPrev = ui.index > 0,
            hasNext = ui.index + 1 < ui.chapters.size,
            busy = ui.resolving,
            error = ui.error,
            hint = hint,
            onRetry = { vm.play(ui.index) },
            onPrev = { vm.prev() },
            onNext = { vm.next() },
            onSpeed = { s -> scope.launch { AppSettings.setVideoSpeed(app, s) } },
            onSkipSettings = { showSkip = true },
            onToggleFullscreen = { fullscreen = !fullscreen },
            onCast = { showCast = true },
        )
    }

    if (fullscreen) { videoBox(Modifier.fillMaxSize()); return }

    Scaffold(topBar = {
        TopAppBar(title = { Text(ui.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                IconButton(onClick = { showCast = true }) {
                    Icon(if (casting) Icons.Default.CastConnected else Icons.Default.Cast, "投屏")
                }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 播放区域跟随视频比例：横屏视频占满宽度；竖屏视频最高占屏幕 60%，两侧留黑边
            BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.6f
                // 投屏时用 16:9 放控制面板
                val ratio = if (casting) 16f / 9f else videoRatio
                val h = (maxWidth / ratio).coerceAtMost(maxH)
                videoBox(Modifier.height(h).aspectRatio(ratio, matchHeightConstraintsFirst = true))
            }
            Text(ui.chapters.getOrNull(ui.index)?.title ?: "", Modifier.padding(12.dp),
                style = MaterialTheme.typography.titleSmall)
            val listState = rememberLazyListState()
            LaunchedEffect(ui.index, ui.chapters.size) {
                if (ui.chapters.isNotEmpty()) listState.scrollToItem((ui.index - 2).coerceAtLeast(0))
            }
            LazyColumn(state = listState) {
                itemsIndexed(ui.chapters) { i, c ->
                    ListItem(
                        headlineContent = { Text(c.title.ifBlank { "第${i + 1}集" }) },
                        supportingContent = { if (c.group.isNotBlank()) Text(c.group) },
                        leadingContent = { if (i == ui.index) Icon(Icons.Default.PlayArrow, null) },
                        colors = if (i == ui.index) ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
                        trailingContent = { if (i != ui.index) TextButton(onClick = { vm.play(i) }) { Text("播放") } },
                    )
                }
            }
        }
    }
}

private fun setFullscreen(activity: Activity?, on: Boolean, landscape: Boolean) {
    activity ?: return
    val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (on) {
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        ctl.show(WindowInsetsCompat.Type.systemBars())
    }
}
