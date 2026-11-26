package com.example.mediahub.ui.common

/** 「刚刚」「5 分钟前」「3 小时前」「2 天前」 */
fun relTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return "从未"
    val s = (now - at).coerceAtLeast(0) / 1000
    return when {
        s < 60 -> "刚刚"
        s < 3600 -> "${s / 60} 分钟前"
        s < 86400 -> "${s / 3600} 小时前"
        else -> "${s / 86400} 天前"
    }
}

fun fmtDuration(ms: Long): String = if (ms < 1000) "$ms ms" else "%.1f 秒".format(ms / 1000.0)
