package com.example.mediahub.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.engine.HostBridge
import com.example.mediahub.engine.MediaUrl

@OptIn(UnstableApi::class)
object PlayerFactory {
    fun create(ctx: Context): ExoPlayer = ExoPlayer.Builder(ctx).build()

    /** Referer / UA 放在 OkHttp DataSource 默认请求头里，m3u8 子清单和分片也会带上 */
    fun mediaSource(m: MediaUrl, extraHeaders: Map<String, String> = emptyMap()): MediaSource {
        val headers = buildMap {
            put("User-Agent", HostBridge.DEFAULT_UA)
            if (m.referer.isNotBlank()) {
                put("Referer", m.referer)
                originOf(m.referer)?.let { put("Origin", it) }
            }
            putAll(extraHeaders)
        }
        val ds = OkHttpDataSource.Factory(EngineManager.http).setDefaultRequestProperties(headers)
        val hls = isHls(m.url)
        val item = MediaItem.Builder().setUri(m.url).apply { if (hls) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
        return if (hls) HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true).createMediaSource(item)
        else DefaultMediaSourceFactory(ds).createMediaSource(item)
    }

    fun isHls(url: String) = url.contains("m3u8", ignoreCase = true)

    private fun originOf(url: String): String? = runCatching {
        val u = Uri.parse(url)
        if (u.scheme == null || u.host == null) null
        else "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrNull()
}
