package com.example.mediahub.ui.rules

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediahub.App
import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleHealth
import com.example.mediahub.data.RuleHealthInfo
import com.example.mediahub.data.RuleKind
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.novel.BookEngines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

class RuleImportViewModel : ViewModel() {
    private val repo = App.instance.rules
    val rules = repo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val health: StateFlow<Map<String, RuleHealthInfo>> = RuleHealth.observe()
    private val _busy = MutableStateFlow(false); val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null); val message = _message.asStateFlow()
    /** 批量检测进度 (已完成, 总数)，没在检测时为空 */
    private val _checking = MutableStateFlow<Pair<Int, Int>?>(null); val checking = _checking.asStateFlow()
    private var checkJob: Job? = null

    fun import(uris: List<Uri>) = viewModelScope.launch {
        _busy.value = true
        var ok = 0; val errs = mutableListOf<String>()
        uris.forEach {
            val r = repo.import(it)
            ok += r.imported.size; errs += r.errors
            // 重新导入后旧的检测结果不再准确
            r.imported.forEach { rule -> RuleHealth.remove(rule.id) }
        }
        _busy.value = false
        _message.value = buildString {
            append("成功导入 $ok 条规则")
            if (errs.isNotEmpty()) append("\n失败 ${errs.size} 条：\n" + errs.take(10).joinToString("\n"))
        }
    }
    fun toggle(r: RuleEntity) = viewModelScope.launch { repo.setEnabled(r.id, !r.enabled) }
    fun delete(r: RuleEntity) = viewModelScope.launch { repo.delete(r); RuleHealth.remove(r.id) }
    fun dismiss() { _message.value = null }

    /** 批量检测：每个规则取一页分类 / 发现；没有的用关键词搜索。最多 4 个同时进行 */
    fun checkAll(keyword: String, only: List<RuleEntity>? = null) {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            val list = only ?: repo.observe().first()
            _checking.value = 0 to list.size
            try {
                val sem = Semaphore(4)
                list.map { rule ->
                    launch {
                        sem.withPermit {
                            RuleHealth.record(rule.id, quickCheck(rule, keyword.trim()))
                            _checking.update { it?.let { (d, t) -> (d + 1) to t } }
                        }
                    }
                }.joinAll()
            } finally {
                _checking.value = null
            }
        }
    }

    fun cancelCheck() { checkJob?.cancel() }

    private suspend fun quickCheck(rule: RuleEntity, kw: String): RuleHealthInfo {
        val t0 = System.currentTimeMillis()
        var step = ""
        return try {
            val n = withTimeout(30_000) {
                if (rule.kind == RuleKind.LEGADO_BOOK) {
                    val web = BookEngines.get(rule)
                    step = "发现"
                    val kinds = runCatching { web.exploreKinds() }.getOrDefault(emptyList())
                    var c = if (kinds.isNotEmpty()) runCatching { web.explore(kinds.first().second, 1).size }.getOrDefault(0) else 0
                    if (c == 0 && kw.isNotEmpty()) { step = "搜索「$kw」"; c = web.search(kw, 1).size }
                    c
                } else {
                    val e = EngineManager.get(rule)
                    step = "加载模块"
                    e.ensureLoaded()
                    step = "分类列表"
                    val cats = runCatching { e.categories() }.getOrDefault(emptyList())
                    val cat = cats.firstOrNull { runCatching { e.validCategory(it) }.getOrDefault(true) }
                    var c = runCatching { e.catalogPage(1, cat).items.size }.getOrDefault(0)
                    if (c == 0 && kw.isNotEmpty()) { step = "搜索「$kw」"; c = e.search(1, kw).items.size }
                    c
                }
            }
            val ms = System.currentTimeMillis() - t0
            if (n > 0) RuleHealthInfo(true, System.currentTimeMillis(), ms, n, via = "批量检测")
            else RuleHealthInfo(false, System.currentTimeMillis(), ms, 0, step, "没有返回任何结果", "批量检测")
        } catch (t: Throwable) {
            if (t is CancellationException && t !is TimeoutCancellationException) throw t
            val msg = if (t is TimeoutCancellationException) "超时（30 秒）" else (t.message ?: t.toString())
            RuleHealthInfo(false, System.currentTimeMillis(), System.currentTimeMillis() - t0, -1, step,
                msg.lineSequence().firstOrNull().orEmpty().take(200), "批量检测")
        }
    }
}
