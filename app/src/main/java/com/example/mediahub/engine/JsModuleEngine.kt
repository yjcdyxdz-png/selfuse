package com.example.mediahub.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.evaluate
import com.example.mediahub.net.CookieStore
import com.example.mediahub.util.AppLog
import com.example.mediahub.util.JsAssets
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executors

/**
 * 一个 JS 模块对应一个引擎实例（独立的 QuickJS 运行时）。
 * QuickJS 非线程安全：所有调用串行化到专用单线程上。
 */
class JsModuleEngine(
    val sourceId: String,
    private val moduleDir: File,
    client: OkHttpClient,
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "qjs-$sourceId") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val bridge = HostBridge(sourceId, client)
    private var qjs: QuickJs? = null

    val manifest: JsonObject by lazy {
        runCatching { json.parseToJsonElement(File(moduleDir, "manifest.json").readText()).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
    }
    val contentType: String get() = (manifest["contentType"] as? JsonPrimitive)?.contentOrNull ?: "video"
    private var exportedFns: Set<String> = emptySet()

    private suspend fun ensure(): QuickJs {
        qjs?.let { return it }
        val q = QuickJs.create(dispatcher)
        q.memoryLimit = 64L * 1024 * 1024
        q.maxStackSize = 1024L * 1024
        q.function("__h_fetch") { a -> bridge.fetch(a.str(0)) }
        q.function("__h_fetchText") { a -> bridge.fetchText(a.str(0)) }
        q.function("__h_parse") { a -> bridge.parseHtml(a.str(0)) }
        q.function("__h_queryAll") { a -> bridge.queryAll(a.str(0), a.str(1)) }
        q.function("__h_dramaId") { a -> bridge.dramaId(a.str(0)) }
        q.function("__h_chapterId") { a -> bridge.chapterId(a.str(0), a.str(1)) }
        q.function("__h_log") { a -> bridge.log(a.str(0)); null }
        q.function("__h_cookieGet") { a -> CookieStore.header(a.str(0)) }
        q.function("__h_cookieSet") { a -> CookieStore.set(a.str(0), a.str(1)); null }
        q.function("__h_cookieRemove") { a -> CookieStore.remove(a.str(0)); null }

        q.evaluate<Any?>(JsAssets.polyfill, "polyfill.js")
        q.evaluate<Any?>(HostPrelude.build(JsonPrimitive(sourceId).toString()), "prelude.js")
        q.evaluate<Any?>(File(moduleDir, "main.js").readText(), "main.js")
        q.evaluate<Any?>(HostPrelude.CAPTURE, "capture.js")
        val keys = q.evaluate<String>(
            "JSON.stringify(Object.keys(__mod).filter(function(k){return typeof __mod[k]==='function'}))")
        exportedFns = json.decodeFromString<List<String>>(keys).toSet()
        AppLog.i("Engine", "$sourceId 已加载，导出: $exportedFns")
        qjs = q
        return q
    }

    fun hasFunction(name: String) = name in exportedFns

    /** 调用导出函数，返回原始 JSON */
    suspend fun callRaw(fn: String, vararg args: JsonElement, timeoutMs: Long = 60_000): JsonElement =
        withContext(dispatcher) {
            mutex.withLock {
                val q = ensure()
                val code = HostPrelude.call(JsonPrimitive(fn).toString(), JsonArray(args.toList()).toString())
                try {
                    val r = withTimeout(timeoutMs) {
                        q.evaluate<Any?>(code, "call_$fn.js")
                        var out = q.evaluate<String>(HostPrelude.READ, "read.js")
                        // Promise 尚未完成：执行到期的定时器并推动微任务队列，直到有结果
                        while (out == "\u0002") {
                            delay(5)
                            q.evaluate<Any?>(TICK, "tick.js")
                            out = q.evaluate<String>(HostPrelude.READ, "read.js")
                        }
                        out
                    }
                    if (r.startsWith("\u0001")) throw JsModuleException(r.substring(1))
                    json.parseToJsonElement(r)
                } catch (t: Throwable) {
                    if (t is CancellationException && t !is TimeoutCancellationException) throw t
                    AppLog.e("Engine", "$sourceId.$fn 失败: ${t.message}", t)
                    throw JsModuleException("$fn: ${t.message}", t)
                }
            }
        }

    private suspend inline fun <reified T> call(fn: String, vararg args: JsonElement): T =
        json.decodeFromJsonElement(callRaw(fn, *args).normalize())

    // ---------------- 模块接口 ----------------
    suspend fun categories(): List<Category> {
        val r = callRaw("categories")
        val arr = (r as? JsonObject)?.get("items") ?: r
        return (arr as? JsonArray)?.map { el ->
            when (el) {
                is JsonPrimitive -> Category(el.content, el.content)
                is JsonObject -> Category(
                    id = el.str("id") ?: el.str("value") ?: el.str("name").orEmpty(),
                    name = el.str("name") ?: el.str("title") ?: el.str("id").orEmpty(),
                    extra = el,
                )
                else -> Category(el.toString(), el.toString())
            }
        } ?: emptyList()
    }

    /** validCategory 可选；未导出视为全部有效 */
    suspend fun validCategory(c: Category): Boolean {
        ensureLoaded()
        if (!hasFunction("validCategory")) return true
        val r = callRaw("validCategory", JsonPrimitive(c.id))
        return (r as? JsonPrimitive)?.booleanOrNull ?: (r !is JsonNull)
    }

    suspend fun catalogPage(page: Int, category: Category?): PageResult =
        // 传分类 id 字符串：模块常用 String(cat) 或 CATS[cat] 查表，传对象会变成 "[object Object]"
        call("catalogPage", JsonPrimitive(page), category?.id?.let(::JsonPrimitive) ?: JsonNull)

    suspend fun search(page: Int, keyword: String): PageResult =
        call("search", JsonPrimitive(page), JsonPrimitive(keyword))

    suspend fun detail(sourceId: String): DetailResult = call("detail", JsonPrimitive(sourceId))

    suspend fun resolveMedia(chapter: Chapter): MediaUrl {
        val r = callRaw("resolveMedia", buildJsonObject { put("chapter", json.encodeToJsonElement(chapter)) })
        return when (r) {
            is JsonPrimitive -> MediaUrl(url = r.content)
            else -> json.decodeFromJsonElement(r.normalize())
        }
    }

    suspend fun transformImage(info: JsonObject): JsonElement? {
        ensureLoaded()
        return if (hasFunction("transformImage")) callRaw("transformImage", info) else null
    }

    suspend fun ensureLoaded() { withContext(dispatcher) { mutex.withLock { ensure() } } }

    override fun close() {
        runBlocking(dispatcher) { mutex.withLock { qjs?.close(); qjs = null } }
        executor.shutdown()
    }

    private fun Array<Any?>.str(i: Int) = getOrNull(i)?.toString() ?: ""
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}

private const val TICK = "typeof __runTimers === 'function' ? __runTimers() : 0"

class JsModuleException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/** 把数字/null 等非字符串字段转成字符串，避免模块返回 id: 123 时解析失败 */
internal fun JsonElement.normalize(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (k, v) ->
        when {
            k in STRING_KEYS && v is JsonNull -> JsonPrimitive("")
            k in STRING_KEYS && v is JsonPrimitive && !v.isString -> JsonPrimitive(v.content)
            else -> v.normalize()
        }
    })
    is JsonArray -> JsonArray(map { it.normalize() })
    else -> this
}

private val STRING_KEYS = setOf("id", "source", "sourceId", "title", "cover", "url", "description",
    "videoUrl", "group", "referer", "quality", "author", "status")
