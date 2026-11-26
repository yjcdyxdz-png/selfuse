package com.example.mediahub.novel

import net.minidev.json.JSONValue
import org.jsoup.nodes.Element
import org.seimicrawler.xpath.JXNode
import java.net.URL
import java.nio.charset.Charset

internal val TPL = Regex("\\{\\{([\\s\\S]*?)\\}\\}")

/** 带 suspend 回调的正则替换 */
internal suspend fun replaceAllSuspend(input: String, re: Regex, f: suspend (String) -> String): String {
    val sb = StringBuilder(); var last = 0
    for (m in re.findAll(input)) {
        sb.append(input, last, m.range.first)
        sb.append(f(m.groupValues[1]))
        last = m.range.last + 1
    }
    sb.append(input, last, input.length)
    return sb.toString()
}

/** 任意规则结果转字符串 */
internal fun asText(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Element -> v.outerHtml()
    is JXNode -> if (v.isElement) v.asElement().outerHtml() else v.asString()
    is Map<*, *>, is List<*> -> JSONValue.toJSONString(v)
    is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    is Float -> if (v % 1f == 0f) v.toLong().toString() else v.toString()
    else -> v.toString()
}

/** 拆分 Legado URL 选项：`url,{"method":"POST",...}` */
object UrlOptions {
    fun split(u: String): Pair<String, String?> {
        var i = u.indexOf(",{")
        while (i >= 0) {
            val tail = u.substring(i + 1).trim()
            val ok = runCatching { JSONValue.parseWithException(tail) is Map<*, *> }.getOrDefault(false)
            if (ok) return u.substring(0, i).trim() to tail
            i = u.indexOf(",{", i + 1)
        }
        return u to null
    }

    fun parse(opt: String?): Map<*, *> =
        opt?.let { runCatching { JSONValue.parseWithException(it) as? Map<*, *> }.getOrNull() } ?: emptyMap<Any, Any>()
}

/** 相对地址转绝对地址，保留 URL 选项部分 */
fun resolveUrl(u: String, base: String): String {
    if (u.isBlank()) return ""
    val (path, opt) = UrlOptions.split(u.trim())
    val p = path.trim()
    val baseUrl = UrlOptions.split(base).first
    val abs = when {
        p.startsWith("http://", true) || p.startsWith("https://", true) || p.startsWith("data:") -> p
        p.startsWith("//") -> (runCatching { URL(baseUrl).protocol }.getOrDefault("https")) + ":" + p
        else -> runCatching { URL(URL(baseUrl), p).toString() }.getOrDefault(p)
    }
    return if (opt != null) "$abs,$opt" else abs
}

object TextDecode {
    fun sniff(bytes: ByteArray): Charset {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val m = Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(head)
        return m?.groupValues?.get(1)?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }
}

/** `##正则##替换` / `##正则##替换###`（仅取第一个匹配） */
internal class Replace(val body: String, val regex: String?, val repl: String, val first: Boolean) {
    fun apply(s: String): String {
        val r = regex ?: return s
        if (r.isEmpty()) return s
        val re = runCatching { Regex(r) }.getOrNull()
            ?: return if (first) (if (s.contains(r)) repl else "") else s.replace(r, repl)
        return if (first) {
            val m = re.find(s) ?: return ""
            runCatching { m.value.replace(re, repl) }.getOrDefault(repl)
        } else runCatching { s.replace(re, repl) }.getOrElse { re.replace(s) { repl } }
    }

    companion object {
        fun parse(rule: String): Replace {
            val idx = rule.indexOf("##")
            if (idx < 0) return Replace(rule, null, "", false)
            var rest = rule.substring(idx + 2)
            var first = false
            if (rest.endsWith("###")) { first = true; rest = rest.removeSuffix("###") }
            val p = rest.split("##", limit = 2)
            return Replace(rule.substring(0, idx), p[0], p.getOrElse(1) { "" }, first)
        }
    }
}

/** 顶层拆分 && || %%（忽略括号和引号内部），只按第一种出现的运算符拆 */
internal fun splitOps(rule: String): Pair<List<String>, String?> {
    var depth = 0; var quote: Char? = null; var op: String? = null
    val parts = mutableListOf<String>(); var start = 0; var i = 0
    // 正则替换部分不参与拆分
    val stop = rule.indexOf("##").let { if (it < 0) rule.length else it }
    while (i < stop) {
        val ch = rule[i]
        if (quote != null) {
            if (ch == '\\') i++ else if (ch == quote) quote = null
        } else when (ch) {
            '\'', '"' -> quote = ch
            '(', '[', '{' -> depth++
            ')', ']', '}' -> if (depth > 0) depth--
            '&', '|', '%' -> if (depth == 0 && i + 1 < stop && rule[i + 1] == ch) {
                val cand = "$ch$ch"
                if (op == null || op == cand) {
                    op = cand; parts += rule.substring(start, i); i += 2; start = i; continue
                }
            }
        }
        i++
    }
    if (op == null) return listOf(rule) to null
    parts += rule.substring(start)
    return parts.map { it.trim() }.filter { it.isNotEmpty() } to op
}

internal fun <T> interleave(lists: List<List<T>>): List<T> {
    val out = mutableListOf<T>()
    val max = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
    return out
}
