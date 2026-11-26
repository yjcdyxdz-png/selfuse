package com.example.mediahub.novel

import com.example.mediahub.util.AppLog
import com.jayway.jsonpath.DocumentContext
import com.jayway.jsonpath.JsonPath
import net.minidev.json.JSONValue
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.seimicrawler.xpath.JXDocument
import org.seimicrawler.xpath.JXNode

/**
 * Legado 规则解析（常见子集）：
 * - 默认 JSOUP 语法：`class.x.0@tag.a@href`、`id.x`、`tag.li!0`、`children`、`text.xxx`，`[0,2]` `[1:3]` `[!0]` 索引，
 *   不是这几种类型前缀的段按 CSS 选择器处理
 * - `@css:` 选择器@属性、`@XPath:` 或以 `/` 开头、`@json:` 或以 `$.` 开头（JSON 内容默认按 JSONPath）
 * - `&&` 合并、`||` 取第一个非空、`%%` 交替合并
 * - `##正则##替换`、`##正则##替换###`
 * - `{{表达式}}` 模板（规则或 JS）、`{$.x}` JSON 模板、`@put:{}` `@get:{}`
 * - `@js:` / `<js></js>`：前面规则的结果作为 result
 * - 列表规则前缀 `-` 倒序
 */
class AnalyzeRule(
    private val js: LegadoJs,
    var baseUrl: String,
    private val vars: MutableMap<String, String>,
    private val key: String = "",
    private val page: Int = 1,
) {
    var content: Any? = null

    private enum class Mode { CSS, XPATH, JSON, JSOUP }

    // ================= 公开接口 =================

    suspend fun getString(rule: String?, ctx: Any? = content, isUrl: Boolean = false): String {
        if (rule.isNullOrBlank()) return ""
        val list = getStringList(rule, ctx)
        return if (isUrl) list.firstOrNull { it.isNotBlank() }?.let { resolveUrl(it.trim(), baseUrl) } ?: ""
        else list.joinToString("\n").trim()
    }

    suspend fun getStringList(rule: String?, ctx: Any? = content, isUrl: Boolean = false): List<String> {
        if (rule.isNullOrBlank()) return emptyList()
        val out = try {
            val (body, code) = splitJs(rule.trim())
            var res: List<String> = if (body.isBlank()) listOf(asText(ctx)) else stringOps(body, ctx)
            if (code != null) {
                res = when (val r = js.eval(code, bindings(if (body.isBlank()) ctx else res.joinToString("\n")))) {
                    is List<*> -> r.filterNotNull().map { asText(it) }
                    null -> emptyList()
                    else -> listOf(asText(r))
                }
            }
            res
        } catch (t: Throwable) {
            AppLog.e("Rule", "规则出错: $rule", t); emptyList()
        }
        return if (isUrl) out.filter { it.isNotBlank() }.map { resolveUrl(it.trim(), baseUrl) } else out
    }

    suspend fun getElements(rule: String?, ctx: Any? = content): List<Any> {
        if (rule.isNullOrBlank()) return emptyList()
        var r = rule.trim(); var reverse = false
        if (r.startsWith("-")) { reverse = true; r = r.substring(1) } else if (r.startsWith("+")) r = r.substring(1)
        val out = try {
            val (body, code) = splitJs(r)
            var res: List<Any> = if (body.isBlank()) listOfNotNull(ctx) else elementOps(body, ctx)
            if (code != null) {
                res = when (val v = js.eval(code, bindings(if (body.isBlank()) ctx else res))) {
                    is List<*> -> v.filterNotNull()
                    is Map<*, *> -> listOf(v)
                    is String -> parseLooseList(v)
                    null -> emptyList()
                    else -> listOf(v)
                }
            }
            res
        } catch (t: Throwable) {
            AppLog.e("Rule", "列表规则出错: $rule", t); emptyList()
        }
        return if (reverse) out.reversed() else out
    }

    // ================= 运算符 =================

    private suspend fun stringOps(rule: String, ctx: Any?): List<String> {
        val (parts, op) = splitOps(rule)
        if (op == null) return stringSingle(rule, ctx)
        val all = mutableListOf<List<String>>()
        for (p in parts) {
            val r = stringSingle(p, ctx)
            if (op == "||") { if (r.any { it.isNotBlank() }) return r } else all += r
        }
        return if (op == "%%") interleave(all) else all.flatten()
    }

    private suspend fun elementOps(rule: String, ctx: Any?): List<Any> {
        val (parts, op) = splitOps(rule)
        if (op == null) return elementSingle(rule, ctx)
        val all = mutableListOf<List<Any>>()
        for (p in parts) {
            val r = elementSingle(p, ctx)
            if (op == "||") { if (r.isNotEmpty()) return r } else all += r
        }
        return if (op == "%%") interleave(all) else all.flatten()
    }

    // ================= 单条规则 =================

    private suspend fun stringSingle(raw: String, ctx: Any?): List<String> {
        val rule = applyPutGet(raw.trim(), ctx)
        val rep = Replace.parse(rule)
        val body = rep.body.trim()
        val values: List<String> = when {
            body.isEmpty() -> listOf(asText(ctx))
            body.contains("{{") || body.contains("{$.") -> listOf(template(body, ctx))
            else -> when (modeOf(body, ctx)) {
                Mode.CSS -> cssStrings(body.substring(5), ctx)
                Mode.XPATH -> xpathNodes(strip(body, "@XPath:"), ctx).map { if (it.isElement) it.asElement().text() else it.asString() }
                Mode.JSON -> jsonStrings(strip(body, "@json:"), ctx)
                Mode.JSOUP -> jsoupStrings(body, ctx)
            }
        }
        return if (rep.regex == null) values else values.map { rep.apply(it) }
    }

    private fun elementSingle(raw: String, ctx: Any?): List<Any> {
        val body = raw.trim()
        return when (modeOf(body, ctx)) {
            Mode.CSS -> root(ctx).select(body.substring(5)).toList()
            Mode.XPATH -> xpathNodes(strip(body, "@XPath:"), ctx).map { if (it.isElement) it.asElement() else it.asString() }
            Mode.JSON -> when (val v = jsonRead(strip(body, "@json:"), ctx)) {
                null -> emptyList(); is List<*> -> v.filterNotNull(); else -> listOf(v)
            }
            Mode.JSOUP -> jsoupWalk(body.split("@").filter { it.isNotBlank() }, ctx)
        }
    }

    private fun modeOf(body: String, ctx: Any?): Mode = when {
        body.startsWith("@css:", true) -> Mode.CSS
        body.startsWith("@XPath:", true) || body.startsWith("/") -> Mode.XPATH
        body.startsWith("@json:", true) || body.startsWith("$.") || body.startsWith("$[") -> Mode.JSON
        isJsonCtx(ctx) && !body.startsWith("@") -> Mode.JSON
        else -> Mode.JSOUP
    }

    private fun strip(s: String, p: String) = if (s.startsWith(p, true)) s.substring(p.length) else s

    private fun splitJs(rule: String): Pair<String, String?> {
        val a = rule.indexOf("<js>", ignoreCase = true)
        if (a >= 0) {
            val b = rule.lastIndexOf("</js>", ignoreCase = true)
            if (b > a) return rule.substring(0, a) to rule.substring(a + 4, b)
        }
        val c = rule.indexOf("@js:", ignoreCase = true)
        if (c >= 0) return rule.substring(0, c) to rule.substring(c + 4)
        return rule to null
    }

    private fun bindings(result: Any?): Map<String, Any?> = mapOf(
        "result" to result, "baseUrl" to baseUrl, "key" to key, "searchKey" to key,
        "page" to page, "src" to (content as? String ?: asText(content)),
    )

    private suspend fun template(body: String, ctx: Any?): String {
        var s = replaceAllSuspend(body, TPL) { expr ->
            val e = expr.trim()
            when {
                e == "key" || e == "searchKey" -> key
                e == "page" -> page.toString()
                RULE_LIKE.containsMatchIn(e) -> getString(e, ctx)
                else -> asText(js.eval(e, bindings(ctx)))
            }
        }
        if (s.contains("{$.")) s = JSON_TPL.replace(s) { m -> jsonStrings(m.groupValues[1], ctx).firstOrNull() ?: "" }
        return s
    }

    private suspend fun applyPutGet(rule: String, ctx: Any?): String {
        var r = rule
        PUT.find(r)?.let { m ->
            val obj = runCatching { JSONValue.parseWithException(m.groupValues[1]) as? Map<*, *> }.getOrNull()
            obj?.forEach { (k, v) -> vars[k.toString()] = getString(v.toString(), ctx) }
            r = r.replace(m.value, "")
        }
        if (r.contains("@get:", true)) r = GET.replace(r) { vars[it.groupValues[1].trim()] ?: "" }
        return r
    }

    // ================= HTML =================

    private var lastSrc: String? = null
    private var lastDoc: Element? = null

    private fun root(ctx: Any?): Element = when (ctx) {
        is Element -> ctx
        is JXNode -> if (ctx.isElement) ctx.asElement() else Jsoup.parse(ctx.asString(), baseUrl)
        null -> Jsoup.parse("")
        is String -> if (ctx === lastSrc && lastDoc != null) lastDoc!! else Jsoup.parse(ctx, baseUrl).also { lastSrc = ctx; lastDoc = it }
        else -> Jsoup.parse(asText(ctx), baseUrl)
    }

    private fun cssStrings(r: String, ctx: Any?): List<String> {
        val at = r.lastIndexOf('@')
        val (sel, attr) = if (at > 0) r.substring(0, at) to r.substring(at + 1) else r to "text"
        val els = if (sel.isBlank()) listOf(root(ctx)) else root(ctx).select(sel).toList()
        return els.map { attrOf(it, attr.trim()) }.filter { it.isNotEmpty() }
    }

    private fun jsoupStrings(rule: String, ctx: Any?): List<String> {
        val segs = rule.split("@").filter { it.isNotBlank() }
        if (segs.isEmpty()) return emptyList()
        val last = segs.last().trim()
        val lastIsAttr = segs.size > 1 || isAttrName(last)
        val els = if (lastIsAttr) jsoupWalk(segs.dropLast(1), ctx) else jsoupWalk(segs, ctx)
        return els.map { attrOf(it, if (lastIsAttr) last else "text") }.filter { it.isNotEmpty() }
    }

    private fun isAttrName(s: String) = s in KNOWN_ATTRS || s.startsWith("data-")

    private fun jsoupWalk(segs: List<String>, ctx: Any?): List<Element> {
        var cur: List<Element> = listOf(root(ctx))
        for (seg in segs) cur = cur.flatMap { segment(it, seg.trim()) }
        return cur
    }

    private data class Idx(val exclude: Boolean, val items: List<IntArray>) // IntArray: [n] 或 [start, end, step]

    private fun segment(el: Element, seg0: String): List<Element> {
        var s = seg0
        var idx: Idx? = null
        NEW_IDX.find(s)?.let { m ->
            idx = parseNewIdx(m.groupValues[1]); s = s.substring(0, m.range.first)
        }
        val type = s.substringBefore('.').substringBefore('!')
        if (type in TYPES) {
            var rest = s.removePrefix(type)
            var name = ""
            if (type != "children") {
                rest = rest.removePrefix(".")
                val cut = rest.indexOfFirst { it == '.' || it == '!' }
                name = if (cut < 0) rest else rest.substring(0, cut)
                rest = if (cut < 0) "" else rest.substring(cut)
            }
            if (idx == null && rest.isNotEmpty()) idx = parseOldIdx(rest)
            val all: List<Element> = when (type) {
                "children" -> el.children()
                "class" -> el.select("." + name.trim().split(Regex("\\s+")).joinToString("."))
                "tag" -> el.getElementsByTag(name)
                "id" -> listOfNotNull(el.getElementById(name))
                "text" -> el.getElementsContainingOwnText(name)
                else -> emptyList()
            }
            return applyIdx(all, idx)
        }
        val sel = runCatching { el.select(s).toList() }.getOrElse { AppLog.w("Rule", "无效选择器: $s"); emptyList() }
        return applyIdx(sel, idx)
    }

    private fun parseOldIdx(rest: String): Idx? {
        val exclude = rest.startsWith("!")
        val nums = rest.substring(1).split(":").mapNotNull { it.trim().toIntOrNull() }
        return if (nums.isEmpty()) null else Idx(exclude, nums.map { intArrayOf(it) })
    }

    private fun parseNewIdx(inner: String): Idx {
        var t = inner.trim(); val exclude = t.startsWith("!")
        if (exclude) t = t.substring(1)
        val items = t.split(",").mapNotNull { p ->
            val q = p.trim()
            if (q.contains(":")) {
                val a = q.split(":")
                intArrayOf(a[0].trim().toIntOrNull() ?: 0, a.getOrNull(1)?.trim()?.toIntOrNull() ?: -1,
                    a.getOrNull(2)?.trim()?.toIntOrNull() ?: 1)
            } else q.toIntOrNull()?.let { intArrayOf(it) }
        }
        return Idx(exclude, items)
    }

    private fun applyIdx(list: List<Element>, spec: Idx?): List<Element> {
        if (spec == null || list.isEmpty()) return list
        val n = list.size
        fun norm(i: Int) = if (i < 0) n + i else i
        val sel = LinkedHashSet<Int>()
        for (it in spec.items) {
            if (it.size == 1) sel += norm(it[0])
            else {
                val a = norm(it[0]); val b = norm(it[1]); val step = maxOf(1, kotlin.math.abs(it[2]))
                if (a <= b) (a..b step step).forEach(sel::add) else (a downTo b step step).forEach(sel::add)
            }
        }
        return if (spec.exclude) list.filterIndexed { i, _ -> i !in sel } else sel.filter { it in 0 until n }.map { list[it] }
    }

    private fun attrOf(e: Element, a: String): String = when (a) {
        "text" -> e.text()
        "textNodes" -> e.textNodes().map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        "ownText" -> e.ownText()
        "html" -> e.clone().also { it.select("script,style").remove() }.html()
        "all" -> e.outerHtml()
        else -> e.attr(a)
    }

    // ================= XPath =================

    private fun xpathNodes(x: String, ctx: Any?): List<JXNode> = runCatching {
        JXDocument.create(Elements(root(ctx))).selN(x)
    }.getOrElse { AppLog.w("Rule", "XPath 出错 $x: ${it.message}"); emptyList() }

    // ================= JSON =================

    private var lastJsonSrc: Any? = null
    private var lastJsonDoc: DocumentContext? = null

    private fun isJsonCtx(ctx: Any?): Boolean = when (ctx) {
        is Map<*, *>, is List<*> -> true
        is String -> ctx.trimStart().let { it.startsWith("{") || it.startsWith("[") }
        else -> false
    }

    private fun jsonDoc(ctx: Any?): DocumentContext {
        if (ctx != null && ctx === lastJsonSrc) lastJsonDoc?.let { return it }
        val d = when (ctx) {
            is String -> JsonPath.parse(ctx)
            is Element -> JsonPath.parse(ctx.text())
            null -> JsonPath.parse("{}")
            else -> JsonPath.parse(ctx)
        }
        lastJsonSrc = ctx; lastJsonDoc = d
        return d
    }

    private fun jsonRead(p: String, ctx: Any?): Any? {
        val path = p.trim().let { if (it.startsWith("$") || it.startsWith("@")) it else "$.$it" }
        return runCatching { jsonDoc(ctx).read<Any?>(path) }.getOrNull()
    }

    private fun jsonStrings(p: String, ctx: Any?): List<String> = when (val v = jsonRead(p, ctx)) {
        null -> emptyList()
        is List<*> -> v.filterNotNull().map { asText(it) }
        else -> listOf(asText(v))
    }

    private fun parseLooseList(s: String): List<Any> {
        val t = s.trim()
        if (t.startsWith("[") || t.startsWith("{")) {
            when (val v = runCatching { JSONValue.parseWithException(t) }.getOrNull()) {
                is List<*> -> return v.filterNotNull()
                is Map<*, *> -> return listOf(v)
            }
        }
        return Jsoup.parse(t, baseUrl).body().children().toList()
    }

    companion object {
        private val TYPES = setOf("class", "tag", "id", "text", "children")
        private val KNOWN_ATTRS = setOf("text", "textNodes", "ownText", "html", "all", "href", "src",
            "title", "alt", "value", "content", "onclick", "style")
        private val NEW_IDX = Regex("\\[(!?\\s*-?\\d+(?:\\s*[:,]\\s*-?\\d*)*)\\]$")
        private val RULE_LIKE = Regex("^(\\$\\.|\\$\\[|@css:|@json:|@XPath:|//)", RegexOption.IGNORE_CASE)
        private val JSON_TPL = Regex("\\{(\\$\\.[^{}]+)\\}")
        private val PUT = Regex("@put:(\\{[^}]+\\})", RegexOption.IGNORE_CASE)
        private val GET = Regex("@get:\\{([^}]+)\\}", RegexOption.IGNORE_CASE)
    }
}
