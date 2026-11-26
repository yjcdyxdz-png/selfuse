package com.example.mediahub.ui.comic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.mediahub.comic.TileTransformation
import com.example.mediahub.engine.HostBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    ruleId: String, sourceId: String, group: String?, startEp: Int, startPage: Int, onBack: () -> Unit,
) {
    val vm: ComicReaderViewModel = viewModel(key = "c|$ruleId|$sourceId",
        factory = ComicReaderViewModel.Factory(ruleId, sourceId, group, startEp, startPage))
    val ui by vm.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var showBars by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    // 每一话一个新的列表状态，从这一话的起始页开始
    val listState = remember(ui.epToken, ui.epLoading) { LazyListState(firstVisibleItemIndex = ui.startPage) }
    val current by remember(listState, ui.pages.size) {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, (ui.pages.size - 1).coerceAtLeast(0)) }
    }
    val multi = ui.episodes.size > 1

    LaunchedEffect(current, ui.pages.size, ui.epToken) { for (i in current..current + 3) vm.ensurePage(i) }
    LaunchedEffect(current, ui.epToken, ui.epLoading) {
        if (!ui.epLoading && ui.pages.isNotEmpty()) { delay(600); vm.save(current) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            ui.loading || ui.epLoading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                if (ui.epLoading && multi) Text(ui.epTitle, color = Color.White, modifier = Modifier.padding(top = 12.dp))
            }
            ui.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败：${ui.error}", color = Color.White, textAlign = TextAlign.Center)
                if (ui.episodes.isNotEmpty()) TextButton(onClick = vm::retryEpisode) { Text("重试") }
            }
            ui.pages.isEmpty() -> Text("没有页面", color = Color.White, modifier = Modifier.align(Alignment.Center))
            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()
                .clickable(remember { MutableInteractionSource() }, null) { showBars = !showBars }) {
                itemsIndexed(ui.pages, key = { i, _ -> "${ui.epToken}|$i" }) { i, p -> PageImage(i, p, ui) { vm.retry(i) } }
                item(key = "end|${ui.epToken}") { EpisodeEnd(ui, onNext = vm::nextEpisode, onPicker = { showPicker = true }) }
            }
        }

        AnimatedVisibility(showBars, Modifier.align(Alignment.TopCenter)) {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (multi) Text("${ui.epTitle}（${ui.ep + 1}/${ui.episodes.size}）", maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                actions = { if (multi) IconButton(onClick = { showPicker = true }) { Icon(Icons.AutoMirrored.Filled.List, "选择话数") } },
            )
        }

        AnimatedVisibility(showBars && !ui.loading, Modifier.align(Alignment.BottomCenter)) {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (ui.pages.isNotEmpty()) {
                        Text("第 ${current + 1} / ${ui.pages.size} 页", style = MaterialTheme.typography.bodyMedium)
                        if (ui.pages.size > 1) Slider(
                            value = current.toFloat(), valueRange = 0f..ui.pages.lastIndex.toFloat(),
                            onValueChange = { v -> scope.launch { listState.scrollToItem(v.toInt()) } })
                    }
                    if (multi) Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::prevEpisode, enabled = ui.hasPrevEp) { Text("上一话") }
                        TextButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) {
                            Text("${ui.epTitle}（${ui.ep + 1}/${ui.episodes.size}）", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = vm::nextEpisode, enabled = ui.hasNextEp) { Text("下一话") }
                    }
                }
            }
        }
        if (!showBars && ui.pages.isNotEmpty() && !ui.epLoading) Text(
            (if (multi) "${ui.ep + 1}话 " else "") + "${current + 1}/${ui.pages.size}", color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
                .background(Color(0x88000000)).padding(horizontal = 6.dp, vertical = 2.dp))
    }

    if (showPicker) EpisodePicker(ui, onPick = { i -> showPicker = false; showBars = false; vm.openEpisode(i) },
        onDismiss = { showPicker = false })
}

/** 一话的末尾：显示「下一话」按钮 */
@Composable
private fun EpisodeEnd(ui: ComicUi, onNext: () -> Unit, onPicker: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ui.episodes.size <= 1) {
            Text("— 完 —", color = Color.Gray)
            return@Column
        }
        Text("${ui.epTitle} 完", color = Color.LightGray)
        if (ui.hasNextEp) Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
            Text("下一话：${ui.episodes[ui.ep + 1].title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Text("已经是最后一话", color = Color.Gray)
        OutlinedButton(onClick = onPicker, modifier = Modifier.fillMaxWidth()) { Text("选择话数") }
    }
}

/** 选择话数：可以输入数字直接跳，也可以从列表里点 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodePicker(ui: ComicUi, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val n = ui.episodes.size
    var input by remember { mutableStateOf("") }
    val num = input.trim().toIntOrNull()
    val valid = num != null && num in 1..n
    val listState = rememberLazyListState((ui.ep - 3).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("共 $n 话 · 正在看第 ${ui.ep + 1} 话", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { v -> input = v.filter(Char::isDigit).take(6) },
                    modifier = Modifier.weight(1f), singleLine = true,
                    label = { Text("跳到第几话（1～$n）") },
                    isError = input.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (valid) onPick(num!! - 1) }),
                )
                Button(onClick = { onPick(num!! - 1) }, enabled = valid) { Text("跳转") }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxHeight(0.6f), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(ui.episodes) { i, e ->
                val cur = i == ui.ep
                Text(
                    "${i + 1}.  ${e.title}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (cur) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = if (cur) FontWeight.Bold else null,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(i) }.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
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
