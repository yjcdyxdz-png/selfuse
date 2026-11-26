package com.example.mediahub.novel

import com.example.mediahub.data.RuleEntity
import com.example.mediahub.data.RuleKind
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 按“搜索 → 书籍信息 → 目录 → 正文”流程执行一个书源 */
class WebBook(val source: BookSource) : AutoCloseable {
    private val vars = ConcurrentHashMap<String, String>()
    private val js = LegadoJs(source.name.ifBlank { source.url }, vars) { url ->
        runBlocking { AnalyzeUrl.fetch(AnalyzeUrl(source, null).build(url)).body }
    }
    private val urls = AnalyzeUrl(source, js)

    /** 书籍信息页的响应，目录地址相同时直接复用 */
    @Volatile private var infoPage: Pair<String, StrResponse>? = null

    private fun rule(base: String, key: String = "", page: Int = 1) = AnalyzeRule(js, base, vars, key, page)

    private suspend fun get(url: String, key: String = "", page: Int = 1): StrResponse {
        val req = urls.build(url, key, page)
        return withContext(Dispatchers.IO) { AnalyzeUrl.fetch(req) }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    suspend fun search(key: String, page: Int = 1): List<SearchBook> {
        val su = source.searchUrl.ifBlank { error("书源未配置 searchUrl") }
        return parseList(get(su, key, page), source.ruleSearch, key, page, allowDetail = true)
            .also { AppLog.i("Book", "${source.name} 搜索「$key」得到 ${it.size} 条") }
    }

    /** 发现分类：支持 `名称::url`（换行或 && 分隔）、JSON 数组 [{title,url}]、@js: / <js> 生成 */
    suspend fun exploreKinds(): List<Pair<String, String>> {
        var s = source.exploreUrl.trim()
        if (s.isEmpty()) return emptyList()
        val binds = mapOf("baseUrl" to source.url, "result" to "")
        if (s.startsWith("@js:", true)) s = asText(js.eval(s.substring(4), binds)).trim()
        else if (s.startsWith("<js>", true)) s = asText(js.eval(s.substring(4).substringBeforeLast("</js>"), binds)).trim()
        if (s.startsWith("[")) {
            val arr = runCatching { net.minidev.json.JSONValue.parseWithException(s) as? List<*> }.getOrNull()
            if (arr != null) return arr.mapNotNull { o ->
                val m = o as? Map<*, *> ?: return@mapNotNull null
                val t = m["title"]?.toString().orEmpty(); val u = m["url"]?.toString().orEmpty()
                if (t.isBlank() || u.isBlank()) null else t to u
            }
        }
        return s.split(Regex("&&|\\n")).mapNotNull { line ->
            val i = line.indexOf("::")
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 2).trim()
        }.filter { it.second.isNotBlank() }
    }

    suspend fun explore(url: String, page: Int): List<SearchBook> {
        val r = if (source.ruleExplore["bookList"].isNullOrBlank()) source.ruleSearch else source.ruleExplore
        return parseList(get(url, "", page), r, "", page, allowDetail = false)
    }

    private suspend fun parseList(
        resp: StrResponse, r: Map<String, String>, key: String, page: Int, allowDetail: Boolean,
    ): List<SearchBook> {
        val ar = rule(resp.url, key, page).apply { content = resp.body }
        val els = ar.getElements(r["bookList"])
        if (els.isEmpty()) {
            // 搜索结果直接跳到详情页的情况
            if (!allowDetail || source.ruleBookInfo["name"].isNullOrBlank()) return emptyList()
            val info = parseInfo(resp, null)
            return if (info.name.isBlank()) emptyList() else listOf(info.toSearch())
        }
        return els.mapNotNull { el ->
            val name = clean(ar.getString(r["name"], el))
            if (name.isBlank()) return@mapNotNull null
            SearchBook(
                name = name,
                author = clean(ar.getString(r["author"], el)),
                kind = clean(ar.getString(r["kind"], el)),
                lastChapter = clean(ar.getString(r["lastChapter"], el)),
                intro = ar.getString(r["intro"], el).trim(),
                coverUrl = ar.getString(r["coverUrl"], el, isUrl = true),
                bookUrl = ar.getString(r["bookUrl"], el, isUrl = true).ifBlank { resp.url },
                wordCount = clean(ar.getString(r["wordCount"], el)),
            )
        }
    }

    suspend fun bookInfo(bookUrl: String, hit: SearchBook?): BookInfo {
        if (source.ruleBookInfo.values.all { it.isBlank() }) {
            return BookInfo(hit?.name ?: "", hit?.author ?: "", hit?.kind ?: "", hit?.lastChapter ?: "",
                hit?.intro ?: "", hit?.coverUrl ?: "", hit?.wordCount ?: "", tocUrl = bookUrl, bookUrl = bookUrl)
        }
        val resp = get(bookUrl)
        infoPage = bookUrl to resp
        return parseInfo(resp, hit).copy(bookUrl = bookUrl)
    }

    private suspend fun parseInfo(resp: StrResponse, hit: SearchBook?): BookInfo {
        val ar = rule(resp.url).apply { content = resp.body }
        val r = source.ruleBookInfo
        r["init"]?.takeIf { it.isNotBlank() }?.let { init -> ar.getElements(init).firstOrNull()?.let { ar.content = it } }
        fun pick(a: String, b: String?) = a.ifBlank { b.orEmpty() }
        return BookInfo(
            name = pick(clean(ar.getString(r["name"])), hit?.name),
            author = pick(clean(ar.getString(r["author"])), hit?.author),
            kind = pick(clean(ar.getString(r["kind"])), hit?.kind),
            lastChapter = pick(clean(ar.getString(r["lastChapter"])), hit?.lastChapter),
            intro = pick(ar.getString(r["intro"]).trim(), hit?.intro),
            coverUrl = pick(ar.getString(r["coverUrl"], isUrl = true), hit?.coverUrl),
            wordCount = pick(clean(ar.getString(r["wordCount"])), hit?.wordCount),
            tocUrl = ar.getString(r["tocUrl"], isUrl = true).ifBlank { resp.url },
            bookUrl = hit?.bookUrl ?: resp.url,
        )
    }

    suspend fun toc(info: BookInfo): List<BookChapter> {
        val r = source.ruleToc
        val out = mutableListOf<BookChapter>()
        val queue = ArrayDeque<String>().apply { add(info.tocUrl) }
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty() && seen.size < 200) {
            val url = queue.removeFirst()
            if (!seen.add(url)) continue
            val cached = infoPage
            val resp = if (cached != null && (cached.first == url || cached.second.url == url)) cached.second else get(url)
            val ar = rule(resp.url).apply { content = resp.body }
            for (el in ar.getElements(r["chapterList"])) {
                val title = clean(ar.getString(r["chapterName"], el))
                if (title.isBlank()) continue
                val u = ar.getString(r["chapterUrl"], el, isUrl = true).ifBlank { resp.url }
                val vip = ar.getString(r["isVip"], el).let { it.isNotBlank() && it != "false" && it != "0" && it != "null" }
                out += BookChapter(out.size, title, u, vip)
            }
            ar.getStringList(r["nextTocUrl"], isUrl = true).forEach { if (it !in seen) queue.add(it) }
        }
        infoPage = null
        AppLog.i("Book", "${info.name} 目录 ${out.size} 章")
        return out
    }

    suspend fun content(ch: BookChapter, nextChapterUrl: String?): String {
        val r = source.ruleContent
        val rule = r["content"]?.takeIf { it.isNotBlank() } ?: error("书源未配置正文规则")
        val parts = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val nextBase = nextChapterUrl?.let { UrlOptions.split(it).first }
        var url: String? = ch.url
        while (url != null && seen.size < 30 && seen.add(url)) {
            val resp = get(url)
            val ar = rule(resp.url).apply { content = resp.body }
            parts += ar.getString(rule)
            url = ar.getStringList(r["nextContentUrl"], isUrl = true)
                .firstOrNull { UrlOptions.split(it).first != nextBase && it !in seen }
        }
        return formatContent(parts.joinToString("\n"), r["replaceRegex"])
    }

    private suspend fun formatContent(raw: String, replaceRegex: String?): String {
        var s = raw
        if (Regex("<[a-zA-Z/!][^>]*>").containsMatchIn(s)) {
            s = s.replace(Regex("(?i)<br\\s*/?>|</p>|<p[^>]*>|</div>|<div[^>]*>"), "\n")
            s = Jsoup.parseBodyFragment(s).body().wholeText()
        } else s = org.jsoup.parser.Parser.unescapeEntities(s, false)
        s = s.lines().map { it.replace('\u00a0', ' ').replace('\u3000', ' ').trim() }
            .filter { it.isNotEmpty() }.joinToString("\n")
        val rr = replaceRegex?.trim().orEmpty()
        if (rr.isNotEmpty()) {
            s = when {
                rr.startsWith("@js:", true) -> asText(js.eval(rr.substring(4), mapOf("result" to s, "baseUrl" to source.url)))
                rr.startsWith("<js>", true) -> asText(js.eval(rr.substring(4).substringBeforeLast("</js>"), mapOf("result" to s)))
                else -> Replace.parse(if (rr.startsWith("##")) rr else "##$rr").apply(s)
            }
        }
        return s.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    override fun close() = js.close()
}

object BookEngines {
    private val map = ConcurrentHashMap<String, WebBook>()

    fun get(rule: RuleEntity): WebBook {
        require(rule.kind == RuleKind.LEGADO_BOOK) { "${rule.name} 不是书源" }
        return map.getOrPut(rule.id) { WebBook(BookSource.load(File(rule.path))) }
    }

    fun invalidate(id: String) { map.remove(id)?.close() }
}

/** 内存缓存：搜索结果、书籍信息+目录、正文 */
object NovelCache {
    val hits = ConcurrentHashMap<String, SearchBook>()
    val books = ConcurrentHashMap<String, BookData>()
    private val contents = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 30
    }
    fun key(ruleId: String, bookUrl: String) = "$ruleId|$bookUrl"
    fun content(k: String, i: Int): String? = synchronized(contents) { contents["$k#$i"] }
    fun putContent(k: String, i: Int, s: String) = synchronized(contents) { contents["$k#$i"] = s }
}
