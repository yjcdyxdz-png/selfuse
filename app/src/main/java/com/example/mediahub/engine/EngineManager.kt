package com.example.mediahub.engine

import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.net.CookieStore
import com.example.mediahub.novel.BookEngines
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 按规则 id 缓存引擎实例；规则重新导入/删除时调用 [invalidate] */
object EngineManager {
    /** 模块或书源手动设置的 Cookie 先挪到这个头里，避免被 CookieJar 覆盖，发送前再合并 */
    private const val EXPLICIT_COOKIE = "X-MH-Explicit-Cookie"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(CookieStore)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val c = req.header("Cookie")
            if (c.isNullOrBlank()) chain.proceed(req)
            else chain.proceed(req.newBuilder().removeHeader("Cookie").header(EXPLICIT_COOKIE, c).build())
        })
        .addNetworkInterceptor(Interceptor { chain ->
            val req = chain.request()
            val explicit = req.header(EXPLICIT_COOKIE)
            if (explicit == null) chain.proceed(req)
            else {
                // 手动设置的优先，CookieJar 里同名的不再重复
                val names = explicit.split(';').map { it.substringBefore('=').trim() }.toSet()
                val fromJar = req.header("Cookie").orEmpty().split(';').map { it.trim() }
                    .filter { it.isNotEmpty() && it.substringBefore('=').trim() !in names }
                val merged = (listOf(explicit.trim()) + fromJar).joinToString("; ")
                chain.proceed(req.newBuilder().removeHeader(EXPLICIT_COOKIE).header("Cookie", merged).build())
            }
        })
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
