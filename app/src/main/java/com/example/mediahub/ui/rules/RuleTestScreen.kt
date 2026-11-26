package com.example.mediahub.ui.rules

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.App
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.engine.*
import com.example.mediahub.novel.BookEngines
import com.example.mediahub.novel.NovelCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class TestStep(val name: String, val ok: Boolean? = null, val ms: Long = 0, val output: String = "")

/** 规则测试：按完整流程逐步调用，显示每一步的耗时和返回内容，遇到失败即停止 */
class RuleTestViewModel(private val ruleId: String) : ViewModel() {
    val rule = MutableStateFlow<RuleEntity?>(null)
    private val _steps = MutableStateFlow<List<TestStep>>(emptyList()); val steps = _steps.asStateFlow()
    private val _running = MutableStateFlow(false); val running = _running.asStateFlow()
    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    init { viewModelScope.launch { rule.value = App.instance.db.ruleDao().get(ruleId) } }

    fun run(kw: String) {
        val r = rule.value ?: return
        if (_running.value) return
        viewModelScope.launch {
            _running.value = true; _steps.value = emptyList()
            try { if (r.kind == RuleKind.LEGADO_BOOK) runBook(r, kw.trim()) else runJs(r, kw.trim()) }
            finally { _running.value = false }
        }
    }

    private suspend fun <T> step(name: String, render: (T) -> String, block: suspend () -> T): T? {
        _steps.update { it + TestStep(name) }
        val t0 = System.currentTimeMillis()
        val res = runCatching { block() }
        val ms = System.currentTimeMillis() - t0
        val out = res.fold({ runCatching { render(it) }.getOrElse { e -> "（结果无法显示：${e.message}）" } },
            { e -> e.message ?: e.toString() })
        _steps.update { l -> l.dropLast(1) + TestStep(name, res.isSuccess, ms, out.take(6000)) }
        return res.getOrNull()
    }

    private fun <T> js(s: KSerializer<T>): (T) -> String = { pretty.encodeToString(s, it) }

    private suspend fun runJs(r: RuleEntity, kw: String) {
        val e = EngineManager.get(r)
        step("加载模块", { "contentType=${e.contentType}" }) { e.ensureLoaded() } ?: return
        val cats = step("categories()", js(ListSerializer(Category.serializer()))) { e.categories() }
        val cat = cats?.firstOrNull { runCatching { e.validCategory(it) }.getOrDefault(true) }
        val page = step("catalogPage(1, ${cat?.name ?: "null"})", js(PageResult.serializer())) { e.catalogPage(1, cat) }
        val search = if (kw.isNotEmpty()) step("search(1, \"$kw\")", js(PageResult.serializer())) { e.search(1, kw) } else null
        val first = search?.items?.firstOrNull() ?: page?.items?.firstOrNull() ?: return
        val sid = first.sourceId.ifBlank { first.url }
        val d = step("detail(\"$sid\")", { pretty.encodeToString(DetailResult.serializer(), it.copy(chapters = it.chapters.take(20))) +
            "\n…共 ${it.chapters.size} 个分集" }) { e.detail(sid) } ?: return
        val ch = d.chapters.firstOrNull() ?: return
        step("resolveMedia(${ch.title})", js(MediaUrl.serializer())) { e.resolveMedia(ch) }
        if (e.hasFunction("transformImage")) _steps.update { it + TestStep("transformImage", true, 0, "已导出，阅读时对每张图调用") }
    }

    private suspend fun runBook(r: RuleEntity, kw: String) {
        val w = BookEngines.get(r)
        step("发现分类", { l: List<Pair<String, String>> -> if (l.isEmpty()) "（无）" else l.joinToString("\n") { "${it.first}  ${it.second}" } }) {
            w.exploreKinds()
        }
        if (kw.isEmpty()) { _steps.update { it + TestStep("搜索", false, 0, "请输入关键词") }; return }
        val list = step("搜索「$kw」", { l -> l.take(20).joinToString("\n") { "${it.name} / ${it.author}\n  ${it.bookUrl}" } +
            "\n共 ${l.size} 条" }) { w.search(kw).onEach { NovelCache.hits[it.bookUrl] = it } } ?: return
        val b = list.firstOrNull() ?: return
        val info = step("书籍信息", { i -> "书名：${i.name}\n作者：${i.author}\n分类：${i.kind}\n最新：${i.lastChapter}\n封面：${i.coverUrl}\n" +
            "目录地址：${i.tocUrl}\n简介：${i.intro.take(300)}" }) { w.bookInfo(b.bookUrl, b) } ?: return
        val toc = step("目录", { l -> l.take(30).joinToString("\n") { "${it.index + 1}. ${it.title}  ${it.url}" } +
            "\n共 ${l.size} 章" }) { w.toc(info) } ?: return
        val c = toc.firstOrNull() ?: return
        step("正文：${c.title}", { t -> t.take(1500) + if (t.length > 1500) "\n…（共 ${t.length} 字）" else "" }) {
            w.content(c, toc.getOrNull(1)?.url)
        }
    }

    class Factory(private val id: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>) = RuleTestViewModel(id) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleTestScreen(ruleId: String, onBack: () -> Unit, onLogs: () -> Unit) {
    val vm: RuleTestViewModel = viewModel(key = "t|$ruleId", factory = RuleTestViewModel.Factory(ruleId))
    val rule by vm.rule.collectAsState()
    val steps by vm.steps.collectAsState()
    val running by vm.running.collectAsState()
    var kw by rememberSaveable { mutableStateOf("") }

    Scaffold(topBar = {
        TopAppBar(title = { Text("测试 ${rule?.name.orEmpty()}", maxLines = 1) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = { IconButton(onClick = onLogs) { Icon(Icons.Default.Terminal, "日志") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(kw, { kw = it }, Modifier.weight(1f), singleLine = true, label = { Text("搜索关键词") })
                    Button(onClick = { vm.run(kw) }, enabled = !running && rule != null) { Text("运行") }
                }
                rule?.let { Text(listOf(if (it.kind == RuleKind.JS_MODULE) "JS 模块" else "书源", it.contentType, it.version, it.baseUrl)
                    .filter { s -> s.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)) }
            }
            items(steps) { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (s.ok) {
                                null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                true -> Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                false -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            }
                            Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (s.ok != null) Text("${s.ms} ms", style = MaterialTheme.typography.labelSmall)
                        }
                        if (s.output.isNotBlank()) SelectionContainer {
                            Text(s.output, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
