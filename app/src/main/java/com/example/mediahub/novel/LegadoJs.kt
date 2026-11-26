package com.example.mediahub.novel

import android.util.Base64
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import net.minidev.json.JSONValue
import org.jsoup.nodes.Element
import org.seimicrawler.xpath.JXNode
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 书源 @js: 的简易运行环境。提供 java.ajax / base64Decode / base64Encode / md5Encode / md5Encode16 /
 * encodeURI / put / get / log，以及变量 result、baseUrl、key、page、src。
 * 不是完整的 Rhino 环境：调用 Java 类（Packages.xxx）的书源不受支持。
 */
class LegadoJs(
    private val tag: String,
    private val vars: MutableMap<String, String>,
    private val ajax: (String) -> String,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "legado-js") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var qjs: QuickJs? = null

    private suspend fun ensure(): QuickJs {
        qjs?.let { return it }
        val q = QuickJs.create(dispatcher)
        q.memoryLimit = 32L * 1024 * 1024
        fun Array<Any?>.s(i: Int) = getOrNull(i)?.toString() ?: ""
        q.function("__j_ajax") { a -> runCatching { ajax(a.s(0)) }.getOrElse { AppLog.e("BookJs", "ajax 失败", it); "" } }
        q.function("__j_b64d") { a -> String(Base64.decode(a.s(0), Base64.DEFAULT), Charsets.UTF_8) }
        q.function("__j_b64e") { a -> Base64.encodeToString(a.s(0).toByteArray(), Base64.NO_WRAP) }
        q.function("__j_md5") { a -> md5(a.s(0)) }
        q.function("__j_enc") { a -> URLEncoder.encode(a.s(0), a.s(1).ifBlank { "UTF-8" }) }
        q.function("__j_put") { a -> vars[a.s(0)] = a.s(1); a.s(1) }
        q.function("__j_get") { a -> vars[a.s(0)] ?: "" }
        q.function("__j_log") { a -> AppLog.i("BookJs:$tag", a.s(0)); null }
        q.evaluate<Any?>(PRELUDE)
        qjs = q
        return q
    }

    /** 执行代码，最后一个表达式的值为结果：字符串，或 Map / List（JS 对象/数组） */
    suspend fun eval(code: String, bindings: Map<String, Any?>): Any? = withContext(dispatcher) {
        mutex.withLock {
            val q = ensure()
            val decl = bindings.entries.joinToString("") { "var ${it.key} = ${toJsLiteral(it.value)};\n" }
            val wrapped = "(function(){\n" + decl +
                "var __r = eval(" + JsonPrimitive(code).toString() + ");\n" +
                "if (__r === undefined || __r === null) return '';\n" +
                "if (typeof __r === 'object') return '\\u0001J' + JSON.stringify(__r);\n" +
                "return String(__r);\n})()"
            try {
                val s = withTimeout(30_000) { q.evaluate<String>(wrapped) }
                if (s.startsWith("\u0001J")) JSONValue.parse(s.substring(2)) else s
            } catch (t: Throwable) {
                AppLog.e("BookJs:$tag", "JS 执行失败: ${t.message}\n${code.take(300)}", t)
                throw t
            }
        }
    }

    override fun close() {
        runBlocking(dispatcher) { mutex.withLock { qjs?.close(); qjs = null } }
        executor.shutdown()
    }

    companion object {
        fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

        fun toJsLiteral(v: Any?): String = when (v) {
            null -> "null"
            is String -> JsonPrimitive(v).toString()
            is Number, is Boolean -> v.toString()
            is Element -> JsonPrimitive(v.outerHtml()).toString()
            is JXNode -> JsonPrimitive(asText(v)).toString()
            is List<*> -> v.joinToString(",", "[", "]") { toJsLiteral(it) }
            is Map<*, *> -> v.entries.joinToString(",", "{", "}") {
                JsonPrimitive(it.key.toString()).toString() + ":" + toJsLiteral(it.value)
            }
            else -> JsonPrimitive(v.toString()).toString()
        }

        private val PRELUDE = """
var java = {
  ajax: function(u){ return __j_ajax(String(u)); },
  connect: function(u){ var b = __j_ajax(String(u)); return { body: function(){ return b; }, toString: function(){ return b; } }; },
  base64Decode: function(s){ return __j_b64d(String(s)); },
  base64Encode: function(s){ return __j_b64e(String(s)); },
  md5Encode: function(s){ return __j_md5(String(s)); },
  md5Encode16: function(s){ return __j_md5(String(s)).substring(8, 24); },
  encodeURI: function(s, cs){ return __j_enc(String(s), cs ? String(cs) : 'UTF-8'); },
  put: function(k, v){ return __j_put(String(k), String(v)); },
  get: function(k){ return __j_get(String(k)); },
  log: function(s){ __j_log(String(s)); return s; },
  timeFormat: function(t){ return new Date(Number(t)).toISOString().replace('T', ' ').substring(0, 19); }
};
var cookie = { getCookie: function(){ return ''; }, setCookie: function(){} };
var console = { log: java.log, info: java.log, warn: java.log, error: java.log };
"""
    }
}
