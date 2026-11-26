package com.example.mediahub.net

import android.content.Context
import com.example.mediahub.App
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 持久化 Cookie。OkHttp 自动保存服务器下发的 Cookie、请求时自动带上；
 * JS 模块通过 host.cookie、书源通过 cookie.getCookie / setCookie 读写同一份数据。
 */
object CookieStore : CookieJar {
    private val prefs by lazy { App.instance.getSharedPreferences("cookies", Context.MODE_PRIVATE) }
    private val lock = Any()
    private var loaded = false
    private val list = mutableListOf<Cookie>()
    private val ATTRS = setOf("path", "domain", "expires", "max-age", "secure", "httponly", "samesite")

    private fun ensure() {
        if (loaded) return
        loaded = true
        runCatching {
            val arr = JSONArray(prefs.getString("all", "[]"))
            for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))?.let(list::add)
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(lock) {
            ensure()
            val now = System.currentTimeMillis()
            for (c in cookies) {
                list.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                if (c.expiresAt > now) list += c
            }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(lock) {
        ensure()
        val now = System.currentTimeMillis()
        if (list.removeAll { it.expiresAt <= now }) persist()
        list.filter { it.matches(url) }
    }

    /** 某个网址会带上的 Cookie，格式 "a=1; b=2" */
    fun header(url: String): String {
        val u = url.toHttpUrlOrNull() ?: return ""
        return loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 写入 "a=1; b=2" 形式的 Cookie，作用于该网址的域名，一年有效 */
    fun set(url: String, header: String) {
        val u = url.toHttpUrlOrNull() ?: return
        val exp = System.currentTimeMillis() + 365L * 24 * 3600 * 1000
        val cs = header.split(';').mapNotNull { part ->
            val e = part.indexOf('=')
            if (e <= 0) return@mapNotNull null
            val name = part.substring(0, e).trim()
            val value = part.substring(e + 1).trim()
            if (name.isEmpty() || name.lowercase() in ATTRS) return@mapNotNull null
            runCatching { Cookie.Builder().name(name).value(value).domain(u.host).path("/").expiresAt(exp).build() }.getOrNull()
        }
        saveFromResponse(u, cs)
    }

    /** 删除会发往该网址的全部 Cookie */
    fun remove(url: String) {
        val u = url.toHttpUrlOrNull() ?: return
        synchronized(lock) {
            ensure()
            if (list.removeAll { it.matches(u) || u.host.endsWith(it.domain) }) persist()
        }
    }

    fun clear() = synchronized(lock) { ensure(); list.clear(); persist() }

    /** 从存储重新读取（恢复备份后调用） */
    fun reload() = synchronized(lock) { loaded = false; list.clear(); ensure() }

    fun count(): Int = synchronized(lock) { ensure(); list.size }

    private fun persist() {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("n", c.name).put("v", c.value).put("d", c.domain).put("p", c.path)
                .put("e", c.expiresAt).put("s", c.secure).put("h", c.httpOnly)
                .put("o", c.hostOnly).put("x", c.persistent))
        }
        prefs.edit().putString("all", arr.toString()).apply()
    }

    private fun fromJson(o: JSONObject): Cookie? = runCatching {
        Cookie.Builder().name(o.getString("n")).value(o.getString("v")).apply {
            if (o.optBoolean("o")) hostOnlyDomain(o.getString("d")) else domain(o.getString("d"))
            path(o.optString("p", "/"))
            if (o.optBoolean("x")) expiresAt(o.getLong("e"))
            if (o.optBoolean("s")) secure()
            if (o.optBoolean("h")) httpOnly()
        }.build()
    }.getOrNull()
}
