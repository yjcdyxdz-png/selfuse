package com.example.mediahub.ui.explore

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediahub.App
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.engine.Category
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.novel.BookEngines
import com.example.mediahub.ui.common.Hit
import com.example.mediahub.ui.common.HitCard
import com.example.mediahub.ui.common.toHit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class Kind(val name: String, val js: Category? = null, val url: String = "")

data class ExploreUi(
    val rules: List<RuleEntity> = emptyList(),
    val rule: RuleEntity? = null,
    val kinds: List<Kind> = emptyList(),
    val kind: Kind? = null,
    val items: List<Hit> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class ExploreViewModel : ViewModel() {
    private val _ui = MutableStateFlow(ExploreUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            App.instance.rules.observe().collect { all ->
                val list = all.filter { it.enabled }
                _ui.update { it.copy(rules = list) }
                val cur = _ui.value.rule
                if (cur == null || list.none { it.id == cur.id }) list.firstOrNull()?.let(::selectRule)
                    ?: _ui.update { it.copy(rule = null, kinds = emptyList(), items = emptyList()) }
            }
        }
    }

    fun selectRule(rule: RuleEntity) {
        job?.cancel()
        _ui.update { it.copy(rule = rule, kinds = emptyList(), kind = null, items = emptyList(), page = 0,
            hasMore = false, loading = true, error = null) }
        job = viewModelScope.launch {
            runCatching {
                if (rule.kind == RuleKind.LEGADO_BOOK) BookEngines.get(rule).exploreKinds().map { Kind(it.first, url = it.second) }
                else {
                    val e = EngineManager.get(rule)
                    e.categories().filter { runCatching { e.validCategory(it) }.getOrDefault(true) }.map { Kind(it.name, js = it) }
                }
            }.onSuccess { kinds ->
                _ui.update { it.copy(kinds = kinds, loading = false) }
                // 书源没有发现分类时不加载；JS 模块没有分类时按 null 分类加载
                if (kinds.isNotEmpty()) selectKind(kinds.first())
                else if (rule.kind == RuleKind.JS_MODULE) selectKind(null)
                else _ui.update { it.copy(error = "该书源没有发现分类") }
            }.onFailure { e -> if (e !is CancellationException) _ui.update { it.copy(loading = false, error = e.message) } }
        }
    }

    fun selectKind(k: Kind?) {
        job?.cancel()
        // 取消了正在进行的加载，loading 要一起复位，否则 loadMore 会直接返回
        _ui.update { it.copy(kind = k, items = emptyList(), page = 0, hasMore = true, loading = false, error = null) }
        loadMore()
    }

    fun loadMore() {
        val s = _ui.value
        val rule = s.rule ?: return
        if (s.loading || !s.hasMore) return
        val page = s.page + 1
        _ui.update { it.copy(loading = true) }
        job = viewModelScope.launch {
            runCatching {
                if (rule.kind == RuleKind.LEGADO_BOOK) {
                    val url = s.kind?.url ?: return@runCatching emptyList<Hit>() to false
                    val list = BookEngines.get(rule).explore(url, page).map { it.toHit(rule) }
                    list to list.isNotEmpty()
                } else {
                    val r = EngineManager.get(rule).catalogPage(page, s.kind?.js)
                    r.items.map { it.toHit(rule) } to r.hasMore
                }
            }.onSuccess { (list, more) ->
                com.example.mediahub.util.AppLog.i("Explore", "${rule.name} 分类=${s.kind?.name} 第${page}页 返回 ${list.size} 条, hasMore=$more, ids=" +
                    list.take(5).joinToString { it.id.take(40) })
                _ui.update { u ->
                    // 用 id+标题去重：模块返回的 id 可能为空或重复，只按 id 会把整页合并成一条
                    val seen = u.items.map { it.id + "\u0000" + it.title }.toHashSet()
                    val fresh = list.filter { seen.add(it.id + "\u0000" + it.title) }
                    // 书源无法判断是否还有下一页：本页没有新条目即停止
                    com.example.mediahub.util.AppLog.i("Explore", "去重后新增 ${fresh.size} 条，累计 ${u.items.size + fresh.size} 条")
                    u.copy(items = u.items + fresh, page = page, hasMore = more && fresh.isNotEmpty(), loading = false)
                }
            }.onFailure { e ->
                // 被取消（切换规则/分类）不算失败，不能把 hasMore 置为 false
                if (e is CancellationException) return@onFailure
                _ui.update { it.copy(loading = false, hasMore = false, error = e.message) }
            }
        }
    }
}

@Composable
fun ExploreScreen(onOpen: (Hit) -> Unit, vm: ExploreViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf {
        val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        last >= grid.layoutInfo.totalItemsCount - 6
    } }
    LaunchedEffect(nearEnd, ui.items.size) { if (nearEnd && ui.items.isNotEmpty()) vm.loadMore() }

    Column(Modifier.fillMaxSize()) {
        if (ui.rules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("请先在「规则」页导入规则") }
            return
        }
        LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.rules, key = { it.id }) { r ->
                FilterChip(selected = ui.rule?.id == r.id, onClick = { vm.selectRule(r) }, label = { Text(r.name) })
            }
        }
        if (ui.kinds.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ui.kinds) { k ->
                AssistChip(onClick = { vm.selectKind(k) }, label = { Text(k.name) },
                    colors = if (ui.kind == k) AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer) else AssistChipDefaults.assistChipColors())
            }
        }
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        ui.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        LazyVerticalGrid(GridCells.Adaptive(104.dp), state = grid, contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(ui.items, key = { i, h -> "$i|${h.id}" }) { _, h -> HitCard(h) { onOpen(h) } }
        }
    }
}
