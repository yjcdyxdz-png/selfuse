package com.example.mediahub.util

import com.example.mediahub.App

/** app/src/main/assets/js/ 下的内置 JS（放在文件里，避免在 Kotlin 字符串里转义） */
object JsAssets {
    private fun read(name: String): String =
        App.instance.assets.open("js/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** setTimeout / setInterval / atob / btoa 等常用全局函数（引擎已有的不覆盖） */
    val polyfill: String by lazy { read("polyfill.js") }

    /** 纯 JS 迷你 Jsoup，兼容书源里 org.jsoup.Jsoup.parse 等 Java 写法 */
    val miniJsoup: String by lazy { read("minijsoup.js") }
}
