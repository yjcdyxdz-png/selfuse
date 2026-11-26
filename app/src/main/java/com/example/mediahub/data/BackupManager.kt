package com.example.mediahub.data

import android.content.Context
import android.net.Uri
import com.example.mediahub.App
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.net.CookieStore
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份 / 恢复。备份是一个 zip：
 *   backup.json                 说明（版本、时间、数量）
 *   rules.json                  规则列表；rules/modules/<id>/…  rules/booksources/<id>.json  规则文件
 *   shelf.json  progress.json   书架和阅读进度
 *   settings/app.json  settings/reader.json      App 设置和阅读器设置
 *   prefs/*.json                规则检测结果、片头片尾、更新记录、Cookie（可选）
 * 不包含缓存。恢复是合并：同一个规则 / 作品以备份为准，本机多出来的不删除，阅读进度保留较新的。
 */
object BackupManager {
    private const val FORMAT = 1
    private const val MAX_ENTRY = 64L * 1024 * 1024
    private const val MAX_TOTAL = 300L * 1024 * 1024
    private const val MAX_ENTRIES = 20_000

    private const val P_HEALTH = "rule_health"
    private const val P_SKIP = "skip_intro_outro"
    private const val P_UPDATES = "shelf_updates"
    private const val P_COOKIES = "cookies"

    data class Options(
        val rules: Boolean = true,
        val library: Boolean = true,
        val settings: Boolean = true,
        val cookies: Boolean = false,
    ) {
        val any get() = rules || library || settings || cookies
    }

    data class Summary(
        val createdAt: Long,
        val appVersion: String,
        val rules: Int,
        val shelf: Int,
        val progress: Int,
        val hasSettings: Boolean,
        val hasCookies: Boolean,
    )

    data class RestoreResult(
        val rules: Int,
        val shelf: Int,
        val progress: Int,
        /** 本机进度比备份新而保留的条数 */
        val progressKept: Int,
        val settings: Boolean,
        val cookies: Boolean,
        val errors: List<String>,
    )

    fun defaultFileName(): String =
        "MediaHub-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".zip"

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun prefs(ctx: Context, name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ========================= 导出 =========================

    suspend fun export(ctx: Context, uri: Uri, opt: Options): Summary = withContext(Dispatchers.IO) {
        val db = App.instance.db
        val rules = if (opt.rules) db.ruleDao().observeAll().first() else emptyList()
        val shelf = if (opt.library) db.libraryDao().observeShelf().first() else emptyList()
        val progress = if (opt.library) db.libraryDao().observeProgress().first() else emptyList()
        val appSettings = if (opt.settings) AppSettings.exportJson(ctx) else null
        val readerSettings = if (opt.settings) ReaderPrefs.exportJson(ctx) else null
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val now = System.currentTimeMillis()
        var ruleCount = 0

        val out = runCatching { ctx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: ctx.contentResolver.openOutputStream(uri)
            ?: error("无法写入所选文件")
        ZipOutputStream(out.buffered()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            fun entryText(name: String, text: String) = entry(name, text.toByteArray(Charsets.UTF_8))

            if (opt.rules) {
                val arr = JSONArray()
                for (r in rules) {
                    val src = File(r.path)
                    val file: String
                    if (r.kind == RuleKind.JS_MODULE) {
                        if (!src.isDirectory) { AppLog.w("Backup", "跳过 ${r.name}：模块文件不存在"); continue }
                        file = "rules/modules/${safe(r.id)}/"
                        src.walkTopDown().filter { it.isFile }.forEach { f ->
                            entry(file + f.relativeTo(src).invariantSeparatorsPath, f.readBytes())
                        }
                    } else {
                        if (!src.isFile) { AppLog.w("Backup", "跳过 ${r.name}：书源文件不存在"); continue }
                        file = "rules/booksources/${safe(r.id)}.json"
                        entry(file, src.readBytes())
                    }
                    arr.put(JSONObject().put("id", r.id).put("kind", r.kind.name).put("name", r.name)
                        .put("version", r.version).put("contentType", r.contentType).put("baseUrl", r.baseUrl)
                        .put("enabled", r.enabled).put("importedAt", r.importedAt).put("file", file))
                    ruleCount++
                }
                entryText("rules.json", arr.toString(2))
                entryText("prefs/$P_HEALTH.json", PrefsIO.fromShared(prefs(ctx, P_HEALTH)).toString())
            }

            if (opt.library) {
                val sa = JSONArray()
                shelf.forEach { s ->
                    sa.put(JSONObject().put("key", s.key).put("ruleId", s.ruleId).put("itemId", s.itemId)
                        .put("contentType", s.contentType).put("title", s.title).put("cover", s.cover)
                        .put("author", s.author).put("addedAt", s.addedAt))
                }
                entryText("shelf.json", sa.toString(2))
                val pa = JSONArray()
                progress.forEach { p ->
                    pa.put(JSONObject().put("key", p.key).put("chapterIndex", p.chapterIndex)
                        .put("chapterTitle", p.chapterTitle).put("position", p.position)
                        .put("extra", p.extra).put("updatedAt", p.updatedAt))
                }
                entryText("progress.json", pa.toString(2))
                entryText("prefs/$P_SKIP.json", PrefsIO.fromShared(prefs(ctx, P_SKIP)).toString())
                entryText("prefs/$P_UPDATES.json", PrefsIO.fromShared(prefs(ctx, P_UPDATES)).toString())
            }

            if (appSettings != null) entryText("settings/app.json", appSettings.toString(2))
            if (readerSettings != null) entryText("settings/reader.json", readerSettings.toString(2))
            if (opt.cookies) entryText("prefs/$P_COOKIES.json", PrefsIO.fromShared(prefs(ctx, P_COOKIES)).toString())

            entryText("backup.json", JSONObject()
                .put("app", "MediaHub").put("format", FORMAT).put("appVersion", version).put("createdAt", now)
                .put("rules", ruleCount).put("shelf", shelf.size).put("progress", progress.size)
                .put("settings", opt.settings).put("cookies", opt.cookies)
                .toString(2))
        }
        AppLog.i("Backup", "导出备份：规则 $ruleCount，书架 ${shelf.size}，进度 ${progress.size}")
        Summary(now, version, ruleCount, shelf.size, progress.size, opt.settings, opt.cookies)
    }

    // ========================= 读取 =========================

    private class Archive(val files: Map<String, ByteArray>) {
        fun text(name: String): String? = files[name]?.toString(Charsets.UTF_8)
    }

    private fun readArchive(ctx: Context, uri: Uri): Archive {
        val input = ctx.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
        val map = HashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input.buffered()).use { zis ->
            val tmp = ByteArray(64 * 1024)
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." }) error("备份文件包含非法路径：$name")
                if (map.size >= MAX_ENTRIES) error("备份文件条目过多")
                val buf = ByteArrayOutputStream()
                while (true) {
                    val n = zis.read(tmp)
                    if (n < 0) break
                    buf.write(tmp, 0, n)
                    total += n
                    if (buf.size() > MAX_ENTRY || total > MAX_TOTAL) error("备份文件过大")
                }
                map[name] = buf.toByteArray()
            }
        }
        return Archive(map)
    }

    private fun arrayLen(a: Archive, name: String): Int =
        a.text(name)?.let { runCatching { JSONArray(it).length() }.getOrNull() } ?: 0

    private fun summaryOf(a: Archive): Summary {
        val meta = a.text("backup.json")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (meta == null || meta.optString("app") != "MediaHub") {
            if (a.files.keys.any { it.endsWith("manifest.json") }) error("这是 JS 模块 zip，不是备份文件，请到「规则」页导入")
            error("不是 MediaHub 备份文件")
        }
        if (meta.optInt("format", 1) > FORMAT) error("这个备份来自更新版本的 App，请先升级")
        return Summary(
            createdAt = meta.optLong("createdAt"),
            appVersion = meta.optString("appVersion"),
            rules = arrayLen(a, "rules.json"),
            shelf = arrayLen(a, "shelf.json"),
            progress = arrayLen(a, "progress.json"),
            hasSettings = a.files.containsKey("settings/app.json") || a.files.containsKey("settings/reader.json"),
            hasCookies = a.files.containsKey("prefs/$P_COOKIES.json"),
        )
    }

    /** 选好文件后先读一遍，告诉用户里面有什么 */
    suspend fun inspect(ctx: Context, uri: Uri): Summary = withContext(Dispatchers.IO) { summaryOf(readArchive(ctx, uri)) }

    // ========================= 恢复 =========================

    suspend fun restore(ctx: Context, uri: Uri, opt: Options): RestoreResult = withContext(Dispatchers.IO) {
        val a = readArchive(ctx, uri)
        summaryOf(a)
        val db = App.instance.db
        val errors = mutableListOf<String>()
        var rules = 0
        var shelf = 0
        var progress = 0
        var kept = 0
        var settings = false
        var cookies = false

        if (opt.rules) {
            val arr = a.text("rules.json")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("name").ifBlank { o.optString("id", "?") }
                val r = runCatching {
                    val id = o.getString("id").also { require(it.isNotBlank()) { "缺少 id" } }
                    val kind = RuleKind.valueOf(o.getString("kind"))
                    val file = o.getString("file")
                    EngineManager.invalidate(id)
                    val path = if (kind == RuleKind.JS_MODULE) {
                        val prefix = if (file.endsWith("/")) file else "$file/"
                        val items = a.files.filterKeys { it.startsWith(prefix) }
                        require(items.containsKey(prefix + "main.js")) { "缺少 main.js" }
                        val dir = File(ctx.filesDir, "modules/" + safe(id))
                        dir.deleteRecursively()
                        dir.mkdirs()
                        val root = dir.canonicalPath + File.separator
                        items.forEach { (k, v) ->
                            val out = File(dir, k.removePrefix(prefix)).canonicalFile
                            require(out.path.startsWith(root)) { "非法路径：$k" }
                            out.parentFile?.mkdirs()
                            out.writeBytes(v)
                        }
                        dir.absolutePath
                    } else {
                        val bytes = a.files[file] ?: error("缺少书源文件")
                        val f = File(ctx.filesDir, "booksources/" + safe(id) + ".json")
                        f.parentFile?.mkdirs()
                        f.writeBytes(bytes)
                        f.absolutePath
                    }
                    db.ruleDao().upsert(RuleEntity(
                        id = id, kind = kind,
                        name = o.optString("name").ifBlank { id },
                        version = o.optString("version"),
                        contentType = o.optString("contentType").ifBlank { "video" },
                        baseUrl = o.optString("baseUrl"),
                        enabled = o.optBoolean("enabled", true),
                        path = path,
                        importedAt = o.optLong("importedAt", System.currentTimeMillis()),
                    ))
                }
                if (r.isSuccess) rules++ else { errors += "规则「$label」：${r.exceptionOrNull()?.message}" }
            }
            a.text("prefs/$P_HEALTH.json")?.let { t -> runCatching { PrefsIO.applyToShared(prefs(ctx, P_HEALTH), JSONObject(t)) } }
            RuleHealth.reload()
        }

        if (opt.library) {
            val dao = db.libraryDao()
            val now = System.currentTimeMillis()
            val sa = a.text("shelf.json")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            for (i in 0 until sa.length()) {
                val o = sa.optJSONObject(i) ?: continue
                val r = runCatching {
                    dao.upsertShelf(ShelfItem(
                        key = o.getString("key"), ruleId = o.getString("ruleId"), itemId = o.getString("itemId"),
                        contentType = o.optString("contentType").ifBlank { "video" },
                        title = o.optString("title"), cover = o.optString("cover"), author = o.optString("author"),
                        addedAt = o.optLong("addedAt", now),
                    ))
                }
                if (r.isSuccess) shelf++ else { errors += "书架「${o.optString("title")}」：${r.exceptionOrNull()?.message}" }
            }
            val pa = a.text("progress.json")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            for (i in 0 until pa.length()) {
                val o = pa.optJSONObject(i) ?: continue
                runCatching {
                    val p = Progress(
                        key = o.getString("key"), chapterIndex = o.optInt("chapterIndex"),
                        chapterTitle = o.optString("chapterTitle"), position = o.optLong("position"),
                        extra = o.optString("extra"), updatedAt = o.optLong("updatedAt", 0L),
                    )
                    // 本机进度更新的话保留本机的
                    val cur = dao.progress(p.key)
                    if (cur == null || cur.updatedAt <= p.updatedAt) { dao.upsertProgress(p); progress++ } else kept++
                }
            }
            a.text("prefs/$P_SKIP.json")?.let { t -> runCatching { PrefsIO.applyToShared(prefs(ctx, P_SKIP), JSONObject(t)) } }
            a.text("prefs/$P_UPDATES.json")?.let { t -> runCatching { PrefsIO.applyToShared(prefs(ctx, P_UPDATES), JSONObject(t)) } }
            UpdateTracker.reload()
        }

        if (opt.settings) {
            a.text("settings/app.json")?.let { t ->
                runCatching { AppSettings.importJson(ctx, JSONObject(t)); settings = true }
                    .onFailure { errors += "App 设置：${it.message}" }
            }
            a.text("settings/reader.json")?.let { t ->
                runCatching { ReaderPrefs.importJson(ctx, JSONObject(t)); settings = true }
                    .onFailure { errors += "阅读设置：${it.message}" }
            }
        }

        if (opt.cookies) {
            a.text("prefs/$P_COOKIES.json")?.let { t ->
                runCatching { PrefsIO.applyToShared(prefs(ctx, P_COOKIES), JSONObject(t)); CookieStore.reload(); cookies = true }
                    .onFailure { errors += "Cookie：${it.message}" }
            }
        }

        AppLog.i("Backup", "恢复备份：规则 $rules，书架 $shelf，进度 $progress（保留本机 $kept），错误 ${errors.size}")
        RestoreResult(rules, shelf, progress, kept, settings, cookies, errors)
    }
}
