package com.example.mediahub.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 内存日志，供日志页面展示 */
object AppLog {
    data class Entry(val time: Long, val level: Char, val tag: String, val msg: String) {
        override fun toString() =
            "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(time))} $level/$tag: $msg"
    }
    private const val MAX = 1000
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private fun add(level: Char, tag: String, msg: String) {
        _entries.update { (it + Entry(System.currentTimeMillis(), level, tag, msg)).takeLast(MAX) }
    }
    fun i(tag: String, msg: String) { Log.i(tag, msg); add('I', tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); add('W', tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t); add('E', tag, msg + (t?.let { "\n" + it.stackTraceToString() } ?: ""))
    }
    fun clear() { _entries.value = emptyList() }
}
