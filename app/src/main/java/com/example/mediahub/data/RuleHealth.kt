package com.example.mediahub.data

import android.content.Context
import com.example.mediahub.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** 规则最近一次测试 / 检测的结果 */
data class RuleHealthInfo(
    val ok: Boolean,
    val at: Long,
    val ms: Long,
    /** 返回的条目数，-1 表示不适用 */
    val count: Int = -1,
    /** 失败的步骤 */
    val step: String = "",
    val message: String = "",
    /** 「测试页」或「批量检测」 */
    val via: String = "",
)

object RuleHealth {
    private val prefs by lazy { App.instance.getSharedPreferences("rule_health", Context.MODE_PRIVATE) }
    private val _all = MutableStateFlow<Map<String, RuleHealthInfo>>(emptyMap())
    @Volatile private var loaded = false

    private fun ensure() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _all.value = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { parse(it) }?.let { k to it } }.toMap()
            loaded = true
        }
    }

    fun observe(): StateFlow<Map<String, RuleHealthInfo>> { ensure(); return _all.asStateFlow() }

    fun record(ruleId: String, h: RuleHealthInfo) {
        ensure()
        val o = JSONObject().put("ok", h.ok).put("at", h.at).put("ms", h.ms).put("count", h.count)
            .put("step", h.step).put("message", h.message).put("via", h.via)
        prefs.edit().putString(ruleId, o.toString()).apply()
        _all.update { it + (ruleId to h) }
    }

    fun remove(ruleId: String) {
        ensure()
        prefs.edit().remove(ruleId).apply()
        _all.update { it - ruleId }
    }

    private fun parse(s: String): RuleHealthInfo? = runCatching {
        val o = JSONObject(s)
        RuleHealthInfo(o.getBoolean("ok"), o.getLong("at"), o.optLong("ms"), o.optInt("count", -1),
            o.optString("step"), o.optString("message"), o.optString("via"))
    }.getOrNull()
}
