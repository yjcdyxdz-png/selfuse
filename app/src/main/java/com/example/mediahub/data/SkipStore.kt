package com.example.mediahub.data

import android.content.Context
import com.example.mediahub.App

/** 每部剧单独的「跳过片头 / 片尾」秒数（毫秒保存），key 与书架、进度相同：ruleId|sourceId */
object SkipStore {
    private val prefs by lazy { App.instance.getSharedPreferences("skip_intro_outro", Context.MODE_PRIVATE) }

    /** 返回 (片头毫秒, 片尾毫秒)，没设置时都是 0 */
    fun get(key: String): Pair<Long, Long> =
        prefs.getLong("$key|in", 0L) to prefs.getLong("$key|out", 0L)

    fun set(key: String, introMs: Long, outroMs: Long) {
        prefs.edit().apply {
            if (introMs > 0) putLong("$key|in", introMs) else remove("$key|in")
            if (outroMs > 0) putLong("$key|out", outroMs) else remove("$key|out")
        }.apply()
    }
}
