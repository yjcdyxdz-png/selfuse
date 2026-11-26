package com.example.mediahub.engine

import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.novel.BookEngines
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 按规则 id 缓存引擎实例；规则重新导入/删除时调用 [invalidate] */
object EngineManager {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val engines = ConcurrentHashMap<String, JsModuleEngine>()

    fun get(rule: RuleEntity): JsModuleEngine {
        require(rule.kind == RuleKind.JS_MODULE) { "${rule.name} 不是 JS 模块" }
        return engines.getOrPut(rule.id) { JsModuleEngine(rule.id, File(rule.path), http) }
    }

    fun invalidate(id: String) {
        engines.remove(id)?.close()
        BookEngines.invalidate(id)
    }
}
