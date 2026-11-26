package com.example.mediahub.data

import android.content.Context
import com.example.mediahub.App
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.ui.detail.DetailCache
import com.example.mediahub.ui.novel.loadBook
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * 书架更新提醒：记录每部作品的分集/章节数。
 * - count：最近一次查到的数量
 * - seen：用户最近一次打开详情 / 播放 / 阅读时看到的数量
 * 两者之差就是「更新 N」。
 */
object UpdateTracker {
    data class CheckState(val done: Int, val total: Int)

    private const val AUTO_INTERVAL = 30 * 60 * 1000L
    private val prefs by lazy { App.instance.getSharedPreferences("shelf_updates", Context.MODE_PRIVATE) }
    private val _news = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _state = MutableStateFlow<CheckState?>(null)
    /** 正在检查时不为空 */
    val state: StateFlow<CheckState?> = _state.asStateFlow()
    private val mutex = Mutex()
    @Volatile private var loaded = false

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val m = mutableMapOf<String, Int>()
            prefs.all.keys.filter { it.startsWith("c|") }.forEach { k ->
                val key = k.substring(2)
                val c = prefs.getInt(k, 0)
                val n = c - prefs.getInt("s|$key", c)
                if (n > 0) m[key] = n
            }
            _news.value = m
            loaded = true
        }
    }

    /** key → 新增数量（只包含有更新的） */
    fun observe(): StateFlow<Map<String, Int>> { ensure(); return _news.asStateFlow() }

    val lastCheckAt: Long get() = prefs.getLong("last_check", 0L)

    /** 打开详情 / 播放 / 阅读时调用：当前数量视为已看过，清除角标 */
    fun markSeen(key: String, count: Int) {
        ensure()
        prefs.edit().putInt("c|$key", count).putInt("s|$key", count).apply()
        _news.update { it - key }
    }

    fun forget(key: String) {
        ensure()
        prefs.edit().remove("c|$key").remove("s|$key").apply()
        _news.update { it - key }
    }

    private fun record(key: String, count: Int) {
        val e = prefs.edit().putInt("c|$key", count)
        // 第一次检查（升级前就在书架上的）：当作已看过，不显示角标
        if (!prefs.contains("s|$key")) e.putInt("s|$key", count)
        e.apply()
        val n = count - prefs.getInt("s|$key", count)
        _news.update { if (n > 0) it + (key to n) else it - key }
    }

    /** 自动检查：开关打开且距上次超过 30 分钟 */
    fun checkIfDue() {
        App.instance.appScope.launch {
            if (!AppSettings.shelfAutoCheck(App.instance).first()) return@launch
            if (System.currentTimeMillis() - lastCheckAt < AUTO_INTERVAL) return@launch
            checkAll()
        }
    }

    /** 手动检查（在应用级作用域里运行，离开书架不会中断） */
    fun startCheck() { App.instance.appScope.launch { checkAll() } }

    private suspend fun checkAll() {
        if (!mutex.tryLock()) return
        try {
            ensure()
            val items = App.instance.db.libraryDao().observeShelf().first()
            // 清理已经不在书架上的记录
            val keys = items.map { it.key }.toSet()
            val stale = prefs.all.keys.filter { (it.startsWith("c|") || it.startsWith("s|")) && it.substring(2) !in keys }
            if (stale.isNotEmpty()) {
                prefs.edit().apply { stale.forEach { remove(it) } }.apply()
                _news.update { m -> m.filterKeys { it in keys } }
            }
            _state.value = CheckState(0, items.size)
            val sem = Semaphore(3)
            coroutineScope {
                items.map { item ->
                    launch {
                        sem.withPermit {
                            val r = runCatching { withTimeout(45_000) { countOf(item) } }
                            val err = r.exceptionOrNull()
                            if (err is CancellationException && err !is TimeoutCancellationException) throw err
                            if (err != null) AppLog.w("Update", "${item.title} 检查失败：${err.message}")
                            r.getOrNull()?.let { record(item.key, it) }
                            _state.update { s -> s?.copy(done = s.done + 1) }
                        }
                    }
                }.joinAll()
            }
            prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
            AppLog.i("Update", "检查完成：${items.size} 部，${_news.value.size} 部有更新")
        } finally {
            _state.value = null
            mutex.unlock()
        }
    }

    private suspend fun countOf(item: ShelfItem): Int? {
        val rule = App.instance.db.ruleDao().get(item.ruleId) ?: return null
        if (!rule.enabled) return null
        return if (rule.kind == RuleKind.LEGADO_BOOK) {
            loadBook(item.ruleId, item.itemId, force = true).second.chapters.size
        } else {
            val d = EngineManager.get(rule).detail(item.itemId)
            DetailCache.put(item.ruleId, item.itemId, d)
            d.chapters.size
        }
    }
}
