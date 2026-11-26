package com.example.mediahub.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class ImportResult(val imported: List<RuleEntity>, val errors: List<String>)

/** 只从本地文件（SAF Uri）导入：JS 模块 zip 或 Legado 书源 JSON。App 不内置任何规则。 */
class RuleRepository(private val ctx: Context, private val dao: RuleDao) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val modulesDir get() = File(ctx.filesDir, "modules").apply { mkdirs() }
    private val bookDir get() = File(ctx.filesDir, "booksources").apply { mkdirs() }

    fun observe() = dao.observeAll()
    suspend fun setEnabled(id: String, e: Boolean) = dao.setEnabled(id, e)

    suspend fun delete(rule: RuleEntity) = withContext(Dispatchers.IO) {
        EngineManager.invalidate(rule.id); File(rule.path).deleteRecursively(); dao.delete(rule.id)
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: uri.lastPathSegment.orEmpty()
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext ImportResult(emptyList(), listOf("无法读取文件：$name"))
        val isZip = bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        try {
            if (isZip) importModuleZip(bytes, name) else importBookSources(bytes.decodeToString(), name)
        } catch (t: Throwable) {
            AppLog.e("Import", "导入失败 $name", t)
            ImportResult(emptyList(), listOf("$name：${t.message}"))
        }
    }

    // ---------- JS 模块 zip ----------
    private suspend fun importModuleZip(bytes: ByteArray, name: String): ImportResult {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (!e.isDirectory) entries[e.name.replace('\\', '/')] = zis.readBytes()
            }
        }
        if (entries.containsKey("backup.json")) error("这是 MediaHub 备份文件，请到「设置 → 备份与恢复」里恢复")
        // 允许 manifest.json 位于一层子目录内
        val manifestKey = entries.keys.filter { it.endsWith("manifest.json") }.minByOrNull { it.count { c -> c == '/' } }
            ?: error("zip 中缺少 manifest.json")
        val prefix = manifestKey.removeSuffix("manifest.json")
        val mainJs = entries[prefix + "main.js"] ?: error("zip 中缺少 main.js")
        val manifest = json.parseToJsonElement(entries.getValue(manifestKey).decodeToString()).jsonObject
        val id = manifest.str("id") ?: manifest.str("name")?.let { "js_" + sha1(it) } ?: ("js_" + sha1(name))
        require("module.exports" in mainJs.decodeToString()) { "main.js 未使用 module.exports 导出" }

        EngineManager.invalidate(id)
        val dir = File(modulesDir, safe(id)).apply { deleteRecursively(); mkdirs() }
        entries.filterKeys { it.startsWith(prefix) }.forEach { (k, v) ->
            val out = File(dir, k.removePrefix(prefix)).canonicalFile
            require(out.path.startsWith(dir.canonicalPath)) { "非法路径：$k" }
            out.parentFile?.mkdirs(); out.writeBytes(v)
        }
        val rule = RuleEntity(
            id = id, kind = RuleKind.JS_MODULE,
            name = manifest.str("name") ?: id,
            version = manifest.str("version").orEmpty(),
            contentType = manifest.str("contentType") ?: "video",
            baseUrl = manifest.str("baseUrl") ?: manifest.str("site").orEmpty(),
            path = dir.absolutePath,
        )
        dao.upsert(rule)
        AppLog.i("Import", "导入模块 ${rule.name} (${rule.contentType})")
        return ImportResult(listOf(rule), emptyList())
    }

    // ---------- Legado 书源 JSON（单个对象或数组） ----------
    private suspend fun importBookSources(text: String, name: String): ImportResult {
        val root = json.parseToJsonElement(text.trim().removePrefix("\uFEFF"))
        val arr = when (root) {
            is JsonArray -> root
            is JsonObject -> JsonArray(listOf(root))
            else -> error("不是有效的书源 JSON")
        }
        val ok = mutableListOf<RuleEntity>(); val errs = mutableListOf<String>()
        arr.forEachIndexed { i, el ->
            val o = el as? JsonObject ?: run { errs += "$name[$i]：不是对象"; return@forEachIndexed }
            val url = o.str("bookSourceUrl")
            if (url.isNullOrBlank()) { errs += "$name[$i]：缺少 bookSourceUrl"; return@forEachIndexed }
            if (o["ruleSearch"] == null && o["searchUrl"] == null) {
                errs += "$name[$i]：缺少搜索规则"; return@forEachIndexed
            }
            val id = "bs_" + sha1(url)
            val f = File(bookDir, "$id.json"); f.writeText(o.toString())
            ok += RuleEntity(
                id = id, kind = RuleKind.LEGADO_BOOK,
                name = o.str("bookSourceName") ?: url,
                version = (o["lastUpdateTime"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                contentType = "novel", baseUrl = url,
                enabled = (o["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
                path = f.absolutePath,
            )
        }
        ok.forEach { EngineManager.invalidate(it.id) }
        dao.upsertAll(ok)
        AppLog.i("Import", "导入书源 ${ok.size} 个，失败 ${errs.size} 个（$name）")
        return ImportResult(ok, errs)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(16)
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_")
    private fun displayName(uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}
