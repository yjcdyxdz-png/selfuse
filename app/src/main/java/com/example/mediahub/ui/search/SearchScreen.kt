package com.example.mediahub.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.App
import com.example.mediahub.data.RuleKind
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.novel.BookEngines
import com.example.mediahub.ui.common.Hit
import com.example.mediahub.ui.common.HitRow
import com.example.mediahub.ui.common.toHit
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

data class SearchUi(
    val hits: List<Hit> = emptyList(),
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val running: Boolean = false,
)

/** 并发搜索所有已启用的规则（最多 4 个同时，每个源 30 秒超时），结果陆续追加 */
class SearchViewModel : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    fun search(kw: String) {
        val key = kw.trim(); if (key.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            val rules = App.instance.rules.observe().first().filter { it.enabled }
            _ui.value = SearchUi(total = rules.size, running = true)
            val sem = Semaphore(4)
            val jobs = rules.map { rule ->
                launch {
                    sem.withPermit {
                        val r = runCatching {
                            withTimeout(30_000) {
                                if (rule.kind == RuleKind.LEGADO_BOOK) BookEngines.get(rule).search(key).map { it.toHit(rule) }
                                else EngineManager.get(rule).search(1, key).items.map { it.toHit(rule) }
                            }
                        }.onSuccess { AppLog.i("Search", "${rule.name} 搜索「$key」返回 ${it.size} 条") }
                        }.onFailure { AppLog.e("Search", "${rule.name} 搜索失败: ${it.message}") }
                        _ui.update { u ->
                            u.copy(hits = u.hits + r.getOrDefault(emptyList()), done = u.done + 1,
                                failed = u.failed + if (r.isFailure) 1 else 0)
                        }
                    }
                }
            }
            jobs.forEach { it.join() }
            _ui.update { it.copy(running = false) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onOpen: (Hit) -> Unit, vm: SearchViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    var kw by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("all") }
    val focus = LocalFocusManager.current
    val shown = remember(ui.hits, type) { if (type == "all") ui.hits else ui.hits.filter { it.rule.contentType == type } }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(kw, { kw = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
            placeholder = { Text("搜索全部已启用的规则") },
            trailingIcon = { IconButton(onClick = { focus.clearFocus(); vm.search(kw) }) { Icon(Icons.Default.Search, "搜索") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.search(kw) }))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("all" to "全部", "video" to "视频", "comic" to "漫画", "novel" to "小说")) { (k, n) ->
                FilterChip(selected = type == k, onClick = { type = k }, label = { Text(n) })
            }
        }
        if (ui.total > 0) Text("已完成 ${ui.done}/${ui.total} 个源" + (if (ui.failed > 0) "，${ui.failed} 个失败（见日志）" else "") +
            "，共 ${shown.size} 条", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
        if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ui.total == 0 && !ui.running) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("输入关键词搜索", style = MaterialTheme.typography.bodyMedium)
        } else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(shown, key = { i, h -> "$i|${h.rule.id}|${h.id}" }) { _, h -> HitRow(h) { onOpen(h) } }
        }
    }
}
