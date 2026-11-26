package com.example.mediahub.engine

import com.example.mediahub.util.AppLog
import kotlinx.serialization.json.*
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 宿主侧实现。JS 与 Kotlin 之间统一用 JSON 字符串传参；
 * 外层 JS 包装（见 [HostPrelude]）负责 JSON.parse/stringify，对模块暴露普通对象。
 * 所有函数都是同步的（运行在引擎专用线程上），模块写不写 await 都可以。
 */
class HostBridge(
    private val sourceId: String,
    private val client: OkHttpClient,
    private val logTag: String = "JS:$sourceId",
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---------------- 网络 ----------------
    fun fetch(argJson: String): String {
        val o = json.parseToJsonElement(argJson).jsonObject
        val url = o.s("url") ?: error("host.fetch: 缺少 url")
        val method = (o.s("method") ?: "GET").uppercase()
        val anyStatus = (o["anyStatus"] as? JsonPrimitive)?.booleanOrNull ?: false
        val timeout = (o["timeoutMs"] as? JsonPrimitive)?.longOrNull ?: 15_000L
        val headers = Headers.Builder().apply {
            set("User-Agent", DEFAULT_UA)
            (o["headers"] as? JsonObject)?.forEach { (k, v) -> set(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
        }.build()
        val body = when (val b = o["body"]) {
            null, JsonNull -> null
            is JsonPrimitive -> b.content
            else -> b.toString()
        }
        val req = Request.Builder().url(url).headers(headers).apply {
            if (method == "GET" || method == "HEAD") method(method, null)
            else method(method, (body ?: "").toRequestBody(
                (headers["Content-Type"] ?: "application/x-www-form-urlencoded").toMediaTypeOrNull()))
        }.build()
        return execute(req, timeout, anyStatus)
    }

    fun fetchText(argJson: String): String {
        val o = json.parseToJsonElement(argJson).jsonObject
        val url = o.s("url") ?: error("host.fetchText: 缺少 url")
        val req = Request.Builder().url(url).header("User-Agent", DEFAULT_UA).apply {
            o.s("referer")?.let { header("Referer", it) }
        }.build()
        return execute(req, 15_000L, anyStatus = true)
    }

    private fun execute(req: Request, timeoutMs: Long, anyStatus: Boolean): String {
        val c = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        AppLog.i(logTag, "${req.method} ${req.url}")
        c.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val charset = resp.body?.contentType()?.charset() ?: sniffCharset(bytes)
            val text = String(bytes, charset)
            if (!anyStatus && !resp.isSuccessful) error("HTTP ${resp.code} ${req.url}")
            return buildJsonObject {
                put("status", resp.code)
                putJsonObject("headers") { resp.headers.names().forEach { put(it.lowercase(), resp.header(it)) } }
                put("body", text)
            }.toString()
        }
    }

    private fun sniffCharset(bytes: ByteArray): Charset {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val m = Regex("charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)
        return m?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    // ---------------- HTML ----------------
    /** HTML -> {tag, attrs, children, texts} 树（JSON 字符串） */
    fun parseHtml(html: String): String = toJson(Jsoup.parse(html).body()).toString()

    /** 在节点树上执行 CSS 选择器，返回匹配节点数组（JSON） */
    fun queryAll(nodeJson: String, selector: String): String {
        val el = fromJson(json.parseToJsonElement(nodeJson).jsonObject)
        return JsonArray(el.select(selector).map { toJson(it) }).toString()
    }

    private fun toJson(e: Element): JsonObject = buildJsonObject {
        put("tag", e.tagName())
        putJsonObject("attrs") { e.attributes().forEach { put(it.key, it.value) } }
        put("children", JsonArray(e.children().map { toJson(it) }))
        put("texts", JsonArray(e.textNodes().map { it.text() }.filter { it.isNotBlank() }.map { JsonPrimitive(it) }))
    }

    /** 反向重建 Jsoup Element。texts 放在子元素之前（结构化树不保留交错顺序） */
    private fun fromJson(o: JsonObject): Element {
        val tag = o.s("tag")?.takeIf { it.isNotBlank() && it != "#root" } ?: "div"
        val el = Element(tag)
        (o["attrs"] as? JsonObject)?.forEach { (k, v) -> el.attr(k, (v as? JsonPrimitive)?.content ?: v.toString()) }
        (o["texts"] as? JsonArray)?.forEach { el.appendChild(TextNode((it as? JsonPrimitive)?.content ?: "")) }
        (o["children"] as? JsonArray)?.forEach { c -> (c as? JsonObject)?.let { el.appendChild(fromJson(it)) } }
        return el
    }

    // ---------------- ID / 日志 ----------------
    fun dramaId(a: String) = "$sourceId:" + hash(a)
    fun chapterId(a: String, b: String) = "$sourceId:" + hash(a) + ":" + hash(b)
    fun log(msg: String) = AppLog.i(logTag, msg)

    private fun hash(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    }
}
