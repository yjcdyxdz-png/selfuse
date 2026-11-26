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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.example.mediahub.player.PlayerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(ruleId: String, sourceId: String, index: Int, onBack: () -> Unit) {
    val vm: PlayerViewModel = viewModel(key = "p|$ruleId|$sourceId", factory = PlayerViewModel.Factory(ruleId, sourceId, index))
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val player = remember { PlayerFactory.create(ctx) }
    var fullscreen by remember { mutableStateOf(false) }
    // 视频实际宽高比（宽/高），未知时按 16:9
    var videoRatio by remember { mutableFloatStateOf(16f / 9f) }

    LaunchedEffect(ui.media) {
        val m = ui.media ?: return@LaunchedEffect
        player.setMediaSource(PlayerFactory.mediaSource(m))
        if (ui.startPos > 0) player.seekTo(ui.startPos)
        player.prepare(); player.playWhenReady = true
    }

    // 断点续看：每 5 秒记录一次位置，暂停/离开页面时也记录
    val savePos = { if (player.currentMediaItem != null) vm.save(player.currentPosition) }
    LaunchedEffect(player) {
        while (isActive) { delay(5_000); if (player.isPlaying) savePos() }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && vm.hasNext()) vm.next()
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
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setFullscreen(activity, false, true)
            savePos()
            player.release()
        }
    }
    // 竖屏视频（短剧）全屏时保持竖屏，横屏视频才切到横屏
    LaunchedEffect(fullscreen, videoRatio) { setFullscreen(activity, fullscreen, landscape = videoRatio >= 1f) }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    val videoBox: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.background(Color.Black)) {
            AndroidView(factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    setShowNextButton(false); setShowPreviousButton(false)
                    // 按原始比例完整显示，不拉伸、不裁剪
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setFullscreenButtonClickListener { fullscreen = it }
                }
            }, modifier = Modifier.fillMaxSize())
            if (ui.resolving) CircularProgressIndicator(Modifier.align(Alignment.Center))
            ui.error?.let {
                Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it, color = Color.White)
                    TextButton(onClick = { vm.play(ui.index) }) { Text("重试") }
                }
            }
        }
    }

    if (fullscreen) { videoBox(Modifier.fillMaxSize()); return }

    Scaffold(topBar = {
        TopAppBar(title = { Text(ui.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // 播放区域跟随视频比例：横屏视频占满宽度；竖屏视频最高占屏幕 60%，两侧留黑边
            BoxWithConstraints(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
                val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.6f
                val h = (maxWidth / videoRatio).coerceAtMost(maxH)
                videoBox(Modifier.height(h).aspectRatio(videoRatio, matchHeightConstraintsFirst = true))
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
