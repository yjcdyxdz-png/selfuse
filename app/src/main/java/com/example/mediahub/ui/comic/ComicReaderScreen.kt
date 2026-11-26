package com.example.mediahub.ui.comic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.mediahub.comic.TileTransformation
import com.example.mediahub.engine.HostBridge
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(ruleId: String, sourceId: String, group: String?, startPage: Int, onBack: () -> Unit) {
    val vm: ComicReaderViewModel = viewModel(key = "c|$ruleId|$sourceId|$group",
        factory = ComicReaderViewModel.Factory(ruleId, sourceId, group))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showBars by remember { mutableStateOf(false) }
    val current by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    var jumped by remember { mutableStateOf(false) }
    LaunchedEffect(ui.pages.size) {
        if (!jumped && ui.pages.isNotEmpty()) {
            listState.scrollToItem(startPage.coerceIn(0, ui.pages.lastIndex)); jumped = true
        }
    }
    LaunchedEffect(current, ui.pages.size) { for (i in current..current + 3) vm.ensurePage(i) }
    LaunchedEffect(current, jumped) { if (jumped) { kotlinx.coroutines.delay(600); vm.save(current) } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            ui.error != null -> Text("加载失败：${ui.error}", color = Color.White, modifier = Modifier.align(Alignment.Center))
            ui.pages.isEmpty() -> Text("没有页面", color = Color.White, modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null) { showBars = !showBars }) {
                itemsIndexed(ui.pages, key = { i, _ -> i }) { i, p -> PageImage(i, p, ui) { vm.retry(i) } }
            }
        }
        AnimatedVisibility(showBars, Modifier.align(Alignment.TopCenter)) {
            TopAppBar(title = { Text(ui.title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
        }
        if (ui.pages.isNotEmpty()) {
            AnimatedVisibility(showBars, Modifier.align(Alignment.BottomCenter)) {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("${current + 1} / ${ui.pages.size}  ${ui.pages.getOrNull(current)?.chapter?.title.orEmpty()}")
                        if (ui.pages.size > 1) Slider(
                            value = current.toFloat(), valueRange = 0f..ui.pages.lastIndex.toFloat(),
                            onValueChange = { v -> scope.launch { listState.scrollToItem(v.toInt()) } })
                    }
                }
            }
            if (!showBars) Text("${current + 1}/${ui.pages.size}", color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                    .background(Color(0x88000000)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun PageImage(i: Int, p: ComicPage, ui: ComicUi, onRetry: () -> Unit) {
    val ctx = LocalContext.current
    val url = p.url
    if (url == null) {
        Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
            if (p.error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${i + 1} 页加载失败", color = Color.White)
                TextButton(onClick = onRetry) { Text("重试") }
            } else Text("${i + 1}", color = Color.Gray)
        }
        return
    }
    var reload by remember(url) { mutableIntStateOf(0) }
    val request = remember(url, reload) {
        ImageRequest.Builder(ctx).data(url)
            .addHeader("User-Agent", HostBridge.DEFAULT_UA)
            .apply { if (p.referer.isNotBlank()) addHeader("Referer", p.referer) }
            .apply { p.headers.forEach { (k, v) -> setHeader(k, v) } }
            .apply {
                val e = ui.engine
                if (ui.hasTransform && e != null)
                    transformations(TileTransformation(e, url, i, Json.encodeToJsonElement(p.chapter)))
            }
            .setParameter("reload", reload)
            .crossfade(true).build()
    }
    SubcomposeAsyncImage(model = request, contentDescription = null, contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth(),
        loading = { Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator() } },
        error = { Box(Modifier.fillMaxWidth().height(480.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("第 ${i + 1} 页加载失败", color = Color.White)
                TextButton(onClick = { reload++ }) { Text("重试") }
            } } })
}
