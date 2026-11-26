package com.example.mediahub.util

import com.example.mediahub.App
import java.io.File
import java.security.MessageDigest

/**
 * 简单的磁盘缓存：cacheDir/mh_cache/<分类>/<sha1(key)>。
 * 放在 cacheDir 下，系统空间不足时可能被清理，所以只存可以重新获取的内容。
 */
object DiskCache {
    private val root by lazy { File(App.instance.cacheDir, "mh_cache").apply { mkdirs() } }

    private fun file(ns: String, key: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(root, ns).apply { mkdirs() }, h)
    }

    fun read(ns: String, key: String): String? =
        runCatching { file(ns, key).takeIf { it.isFile }?.readText() }.getOrNull()

    /** 距上次写入的毫秒数；没有缓存时返回 Long.MAX_VALUE */
    fun ageMs(ns: String, key: String): Long {
        val f = file(ns, key)
        return if (f.isFile) System.currentTimeMillis() - f.lastModified() else Long.MAX_VALUE
    }

    fun write(ns: String, key: String, text: String) {
        runCatching {
            val f = file(ns, key)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { AppLog.w("Cache", "写入缓存失败: ${it.message}") }
    }

    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() { root.listFiles()?.forEach { it.deleteRecursively() } }
}
