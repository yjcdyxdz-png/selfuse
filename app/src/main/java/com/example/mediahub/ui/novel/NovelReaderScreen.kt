package com.example.mediahub.ui.novel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.data.*
import com.example.mediahub.novel.BookChapter
import com.example.mediahub.novel.BookEngines
import com.example.mediahub.novel.NovelCache
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 打开章节时定位到末尾（从下一章往回翻） */
const val OFFSET_END = Int.MAX_VALUE

data class NovelReaderUi(
    val bookName: String = "",
    val chapters: List<BookChapter> = emptyList(),
    val index: Int = 0,
    val paragraphs: List<String> = emptyList(),
    val startOffset: Int = 0,
    /** 每次打开章节递增，用于重置阅读位置 */
    val token: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

class NovelReaderViewModel(private val ruleId: String, private val bookUrl: String, start: Int) : ViewModel() {
    private val _ui = MutableStateFlow(NovelReaderUi(index = start))
    val ui = _ui.asStateFlow()
    private var rule: RuleEntity? = null
    private var job: Job? = null
    private val cacheKey = NovelCache.key(ruleId, bookUrl)
    private val key = Library.key(ruleId, bookUrl)

    init {
        viewModelScope.launch {
            val saved = Library.progress(key)
            runCatching { loadBook(ruleId, bookUrl) }
                .onSuccess { (r, d) ->
                    rule = r
                    UpdateTracker.markSeen(key, d.chapters.size)
                    _ui.update { it.copy(bookName = d.info.name, chapters = d.chapters) }
                    open(start, if (saved?.chapterIndex == start) saved.position.toInt() else 0)
                }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun open(i: Int, offset: Int = 0) {
        val chapters = _ui.value.chapters
        val r = rule ?: return
        if (i !in chapters.indices) return
        job?.cancel()
        _ui.update { it.copy(index = i, loading = true, error = null, paragraphs = emptyList(),
            startOffset = offset, token = it.token + 1) }
        if (offset != OFFSET_END) save(offset)
        job = viewModelScope.launch {
            runCatching { fetch(r, chapters, i) }
                .onSuccess { t -> _ui.update { it.copy(loading = false, paragraphs = t.split("\n").filter(String::isNotBlank)) } }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message) } }
            if (i + 1 in chapters.indices) runCatching { fetch(r, chapters, i + 1) }   // 预加载下一章
        }
    }

    fun save(offset: Int) {
        val s = _ui.value
        val c = s.chapters.getOrNull(s.index) ?: return
        Library.saveProgress(Progress(key, s.index, c.title, offset.coerceAtLeast(0).toLong()))
    }

    private suspend fun fetch(r: RuleEntity, chapters: List<BookChapter>, i: Int): String =
        NovelCache.content(cacheKey, chapters[i].url)
            ?: BookEngines.get(r).content(chapters[i], chapters.getOrNull(i + 1)?.url)
                .also { NovelCache.putContent(cacheKey, chapters[i].url, it) }

    class Factory(private val r: String, private val u: String, private val i: Int) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = NovelReaderViewModel(r, u, i) as T
    }
}

/** 章节全文：第 0 项是标题，之后每项一个段落；starts 是每项在全文中的起始位置 */
private class ChapterText(val title: String, paragraphs: List<String>) {
    val items: List<String>
    val starts: IntArray
    val text: String
    init {
        val sb = StringBuilder(title)
        val st = ArrayList<Int>(paragraphs.size + 1).apply { add(0) }
        val it = ArrayList<String>(paragraphs.size + 1).apply { add(title) }
        for (p in paragraphs) {
            sb.append('\n'); st += sb.length
            val line = "\u3000\u3000" + p
            sb.append(line); it += line
        }
        text = sb.toString(); starts = st.toIntArray(); items = it
    }
    fun itemOf(offset: Int): Int {
        if (offset == OFFSET_END) return starts.lastIndex
        var k = 0
        for (i in starts.indices) if (starts[i] <= offset) k = i else break
        return k
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelReaderScreen(ruleId: String, bookUrl: String, index: Int, onBack: () -> Unit) {
    val vm: NovelReaderViewModel = viewModel(key = "nr|$ruleId|$bookUrl", factory = NovelReaderViewModel.Factory(ruleId, bookUrl, index))
    val ui by vm.ui.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by remember { ReaderPrefs.flow(ctx) }.collectAsState(initial = ReaderSettings())
    var showBars by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }

    val (bgL, fgL) = settings.colors()
    val bg = Color(bgL); val fg = Color(fgL)
    val title = ui.chapters.getOrNull(ui.index)?.title.orEmpty()
    val style = TextStyle(fontSize = settings.fontSize.sp, lineHeight = (settings.fontSize * settings.lineSpacing).sp, color = fg)

    // 当前阅读位置（全文字符偏移）；切换翻页方式或字号时据此恢复
    var curOffset by remember(ui.token) { mutableIntStateOf(ui.startOffset) }
    val report: (Int) -> Unit = { o -> curOffset = o; vm.save(o) }

    Box(Modifier.fillMaxSize().background(bg)) {
        when {
            ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            ui.error != null -> Column(Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败：${ui.error}", color = fg); Button(onClick = { vm.open(ui.index) }) { Text("重试") }
            }
            else -> {
                val ct = remember(ui.token, ui.paragraphs) { ChapterText(title, ui.paragraphs) }
                val hasNext = ui.index + 1 < ui.chapters.size
                if (settings.pageMode == ReaderSettings.MODE_PAGE) PagedText(ct, style, fg, curOffset, report,
                    onPrevChapter = { if (ui.index > 0) vm.open(ui.index - 1, OFFSET_END) },
                    onNextChapter = { if (hasNext) vm.open(ui.index + 1) },
                    onCenter = { showBars = !showBars })
                else ScrollText(ct, style, fg, curOffset, report, hasNext,
                    onNextChapter = { vm.open(ui.index + 1) }, onCenter = { showBars = !showBars })
            }
        }

        AnimatedVisibility(showBars, Modifier.align(Alignment.TopCenter)) {
            TopAppBar(title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } })
        }
        AnimatedVisibility(showBars, Modifier.align(Alignment.BottomCenter)) {
            BottomAppBar {
                TextButton(onClick = { vm.open(ui.index - 1) }, enabled = ui.index > 0) { Text("上一章") }
                IconButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, "目录") }
                Text("${ui.index + 1}/${ui.chapters.size}", Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "阅读设置") }
                TextButton(onClick = { vm.open(ui.index + 1) }, enabled = ui.index + 1 < ui.chapters.size) { Text("下一章") }
            }
        }
    }

    if (showSettings) ReaderSettingsSheet(settings, { s -> scope.launch { ReaderPrefs.save(ctx, s) } }) { showSettings = false }
    if (showToc) ModalBottomSheet(onDismissRequest = { showToc = false }) {
        val st = rememberLazyListState((ui.index - 3).coerceAtLeast(0))
        LazyColumn(state = st, modifier = Modifier.fillMaxHeight(0.7f)) {
            itemsIndexed(ui.chapters) { i, c ->
                Text(c.title, Modifier.fillMaxWidth().clickable { showToc = false; vm.open(i) }
                    .padding(horizontal = 20.dp, vertical = 12.dp), maxLines = 1,
                    color = if (i == ui.index) MaterialTheme.colorScheme.primary else Color.Unspecified,
                    fontWeight = if (i == ui.index) FontWeight.Bold else null)
            }
        }
    }
}

@Composable
private fun ScrollText(
    ct: ChapterText, style: TextStyle, fg: Color, startOffset: Int, report: (Int) -> Unit,
    hasNext: Boolean, onNextChapter: () -> Unit, onCenter: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ct.itemOf(startOffset))
    LaunchedEffect(listState, ct) {
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { i ->
            delay(600); report(ct.starts[i.coerceIn(0, ct.starts.lastIndex)])
        }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        modifier = Modifier.fillMaxSize().systemBarsPadding()
            .clickable(remember { MutableInteractionSource() }, null, onClick = onCenter)) {
        itemsIndexed(ct.items) { i, t ->
            if (i == 0) Text(t, style = style.copy(fontSize = style.fontSize * 1.3f, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(bottom = 16.dp))
            else Text(t, style = style, modifier = Modifier.padding(bottom = (style.fontSize.value * 0.5f).dp))
        }
        item {
            if (hasNext) OutlinedButton(onClick = onNextChapter, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                Text("下一章", color = fg)
            }
        }
    }
}

/** 左右翻页：用 TextMeasurer 按可用高度把整章切成若干页；点左侧 1/3 上一页、右侧 1/3 下一页、中间呼出菜单 */
@Composable
private fun PagedText(
    ct: ChapterText, style: TextStyle, fg: Color, startOffset: Int, report: (Int) -> Unit,
    onPrevChapter: () -> Unit, onNextChapter: () -> Unit, onCenter: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val annotated = remember(ct, style) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = style.fontSize * 1.3f, fontWeight = FontWeight.Bold)) { append(ct.title) }
            append(ct.text.substring(ct.title.length))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val footer = with(density) { 24.dp.roundToPx() }
        val w = constraints.maxWidth
        val h = constraints.maxHeight - footer
        val pageStarts = remember(annotated, w, h) {
            val layout = measurer.measure(annotated, style, constraints = Constraints(maxWidth = w))
            val starts = mutableListOf(0)
            var top = 0f
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) - top > h && line > 0) {
                    val s = layout.getLineStart(line)
                    if (s > starts.last()) { starts += s; top = layout.getLineTop(line) }
                }
            }
            starts
        }
        val initial = if (startOffset == OFFSET_END) pageStarts.lastIndex
            else pageStarts.indexOfLast { it <= startOffset }.coerceAtLeast(0)
        key(pageStarts) {
            val pager = rememberPagerState(initialPage = initial) { pageStarts.size }
            LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { report(pageStarts[it]) } }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val end = pageStarts.getOrNull(page + 1) ?: annotated.length
                Column(Modifier.fillMaxSize().pointerInput(page) {
                    detectTapGestures { o ->
                        when {
                            o.x < size.width / 3f -> if (page > 0) scope.launch { pager.animateScrollToPage(page - 1) } else onPrevChapter()
                            o.x > size.width * 2f / 3f -> if (page < pageStarts.lastIndex) scope.launch { pager.animateScrollToPage(page + 1) } else onNextChapter()
                            else -> onCenter()
                        }
                    }
                }) {
                    Text(annotated.subSequence(pageStarts[page], end).trimStartNewline(), style = style,
                        modifier = Modifier.weight(1f))
                    Text("${ct.title}   ${page + 1}/${pageStarts.size}", style = style.copy(fontSize = 11.sp, lineHeight = 14.sp),
                        color = fg.copy(alpha = 0.6f), maxLines = 1)
                }
            }
        }
    }
}

private fun AnnotatedString.trimStartNewline(): AnnotatedString =
    if (text.startsWith("\n")) subSequence(1, length) else this
