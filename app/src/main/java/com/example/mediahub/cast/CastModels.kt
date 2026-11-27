package com.example.mediahub.cast

/** 正在投屏的一个视频 */
data class CastMedia(
    val id: Long,
    val title: String,
    /** 原始地址 */
    val url: String,
    /** 转发时带上的请求头（Referer、UA 等） */
    val headers: Map<String, String>,
    val hls: Boolean,
    val startMs: Long,
)

sealed interface CastTarget {
    /** 电脑浏览器打开手机上的网页播放 */
    data object Browser : CastTarget
    /** DLNA 播放器（电脑上的 Macast、电视、盒子等） */
    data class Dlna(val device: DlnaDevice) : CastTarget
}

data class CastState(
    val target: CastTarget? = null,
    val media: CastMedia? = null,
    /** 浏览器模式：电脑要打开的地址 */
    val pageUrl: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 浏览器模式：电脑最近一次回报的时间 */
    val seenAt: Long = 0,
    /** 正在把视频发给设备 */
    val sending: Boolean = false,
    val error: String? = null,
) {
    val targetName: String
        get() = when (val t = target) {
            null -> ""
            CastTarget.Browser -> "电脑浏览器"
            is CastTarget.Dlna -> t.device.name
        }
}

sealed interface CastEvent {
    /** 投屏端播完了这一集 */
    data class Ended(val mediaId: Long) : CastEvent
}
