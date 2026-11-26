package com.example.mediahub.novel

import com.example.mediahub.engine.EngineManager
import com.example.mediahub.engine.HostBridge
import com.example.mediahub.util.AppLog
import net.minidev.json.JSONValue
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.nio.charset.Charset

data class UrlRequest(
    val url: String,
    val method: String,
    val body: String?,
    val headers: Map<String, String>,
    val charset: String?,
    val isJsonBody: Boolean,
)

data class StrResponse(val url: String, val body: String, val code: Int)

/**
 * Legado URL 规则：`@js:` / `<js>`、`<1,2,3>` 分页、`{{key}}` `{{page}}` `{{js}}`、
 * `url,{"method":"POST","body":"...","charset":"gbk","headers":{...}}`
 */
class AnalyzeUrl(private val source: BookSource, private val js: LegadoJs?) {

    suspend fun build(raw: String, key: String = "", page: Int = 1, baseUrl: String = source.url): UrlRequest {
        val binds = mapOf("key" to key, "searchKey" to key, "page" to page, "baseUrl" to baseUrl, "result" to "")
        var u = raw.trim()
        if (js != null) {
            if (u.startsWith("@js:", true)) u = asText(js.eval(u.substring(4), binds)).trim()
            else if (u.startsWith("<js>", true)) {
                val e = u.lastIndexOf("</js>", ignoreCase = true)
                if (e > 0) u = asText(js.eval(u.substring(4, e), binds)).trim() + u.substring(e + 5)
            }
        }
        u = PAGE_LIST.replace(u) { m ->
            val l = m.groupValues[1].split(","); l[(page - 1).coerceIn(0, l.lastIndex)].trim()
        }
        u = replaceAllSuspend(u, TPL) { expr ->
            when (val e = expr.trim()) {
                "key", "searchKey" -> key
                "page" -> page.toString()
                else -> if (js != null) asText(js.eval(e, binds)) else ""
            }
        }
        val (path, optStr) = UrlOptions.split(u)
        val opt = UrlOptions.parse(optStr)
        val charset = opt["charset"]?.toString()?.takeIf { it.isNotBlank() }
        val method = (opt["method"]?.toString() ?: "GET").uppercase()

        val headers = linkedMapOf("User-Agent" to HostBridge.DEFAULT_UA)
        headers += sourceHeaders(binds)
        toStringMap(opt["headers"]).let(headers::putAll)

        val url = encodeQuery(resolveUrl(path, baseUrl), charset)
        var body: String? = null
        var isJson = false
        if (method == "POST") {
            val b = opt["body"]
            var s = when (b) { null -> ""; is Map<*, *>, is List<*> -> JSONValue.toJSONString(b); else -> b.toString() }
            isJson = s.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            if (!isJson) s = encodePairs(s, charset)
            body = s
        }
        return UrlRequest(url, method, body, headers, charset, isJson)
    }

    private suspend fun sourceHeaders(binds: Map<String, Any?>): Map<String, String> {
        var h = source.header.trim()
        if (h.isEmpty()) return emptyMap()
        if (js != null) {
            if (h.startsWith("@js:", true)) h = asText(js.eval(h.substring(4), binds))
            else if (h.startsWith("<js>", true)) h = asText(js.eval(h.substring(4).substringBeforeLast("</js>"), binds))
        }
        return toStringMap(runCatching { JSONValue.parseWithException(h) }.getOrNull())
    }

    private fun toStringMap(v: Any?): Map<String, String> = when (v) {
        is Map<*, *> -> v.entries.associate { it.key.toString() to asText(it.value) }
        is String -> toStringMap(runCatching { JSONValue.parseWithException(v) }.getOrNull())
        else -> emptyMap()
    }

    companion object {
        private val PAGE_LIST = Regex("<([^<>]*,[^<>]*)>")
        private val ENCODED = Regex("%[0-9A-Fa-f]{2}")

        private fun encodeParam(v: String, cs: String?): String {
            if (ENCODED.containsMatchIn(v)) return v
            if (v.all { (it.code < 128 && it.isLetterOrDigit()) || it in "-_.~*" }) return v
            return runCatching { URLEncoder.encode(v, cs ?: "UTF-8") }.getOrDefault(v)
        }

        private fun encodePairs(q: String, cs: String?): String = q.split("&").joinToString("&") { kv ->
            val e = kv.indexOf('=')
            if (e < 0) kv else kv.substring(0, e) + "=" + encodeParam(kv.substring(e + 1), cs)
        }

        private fun encodeQuery(url: String, cs: String?): String {
            val q = url.indexOf('?')
            if (q < 0) return url
            return url.substring(0, q) + "?" + encodePairs(url.substring(q + 1), cs)
        }

        /** 阻塞请求（在 IO 线程或 JS 线程调用） */
        fun fetch(req: UrlRequest): StrResponse {
            val b = Request.Builder().url(req.url)
            req.headers.forEach { (k, v) -> b.header(k, v) }
            if (req.method == "POST") {
                val ct = req.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                    ?: if (req.isJsonBody) "application/json; charset=utf-8"
                    else "application/x-www-form-urlencoded; charset=${req.charset ?: "UTF-8"}"
                b.post((req.body ?: "").toByteArray(Charsets.UTF_8).toRequestBody(ct.toMediaTypeOrNull()))
            }
            AppLog.i("Book", "${req.method} ${req.url}" + (req.body?.let { " body=${it.take(200)}" } ?: ""))
            EngineManager.http.newCall(b.build()).execute().use { r ->
                val ct = r.body?.contentType()
                val bytes = r.body?.bytes() ?: ByteArray(0)
                val cs = req.charset?.let { runCatching { Charset.forName(it) }.getOrNull() }
                    ?: ct?.charset() ?: TextDecode.sniff(bytes)
                if (!r.isSuccessful) AppLog.w("Book", "HTTP ${r.code} ${req.url}")
                return StrResponse(r.request.url.toString(), String(bytes, cs), r.code)
            }
        }
    }
}
