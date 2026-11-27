package com.example.mediahub.cast

import android.util.Base64
import com.example.mediahub.App
import com.example.mediahub.engine.EngineManager
import com.example.mediahub.util.AppLog
import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.util.concurrent.TimeUnit

/**
 * 手机上的投屏服务器：
 *   /            电脑浏览器播放页（assets/cast/index.html）
 *   /hls.min.js  播放 m3u8 用的 hls.js
 *   /api/state   当前投屏的视频和指令（网页轮询）
 *   /api/report  网页回报播放进度
 *   /s/<视频>/<base64 地址>/<文件名>  转发源站视频，m3u8 会改写成继续经过这里
 */
class CastServer(port: Int) : NanoHTTPD(port) {
    private val http = EngineManager.http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val r = try {
            when {
                uri == "/" || uri == "/index.html" -> asset("cast/index.html", "text/html; charset=utf-8")
                uri == "/hls.min.js" -> asset("cast/hls.min.js", "application/javascript; charset=utf-8")
                uri == "/api/state" -> json(CastController.stateJson())
                uri == "/api/report" -> {
                    CastController.onReport(session.parameters.mapValues { it.value.firstOrNull().orEmpty() })
                    json("{}")
                }
                uri.startsWith("/s/") -> proxy(session)
                else -> text(Response.Status.NOT_FOUND, "not found")
            }
        } catch (t: Throwable) {
            AppLog.e("Cast", "处理 $uri 失败：${t.message}", t)
            text(Response.Status.INTERNAL_ERROR, t.message ?: "error")
        }
        r.addHeader("Access-Control-Allow-Origin", "*")
        r.addHeader("Cache-Control", "no-cache")
        return r
    }

    private fun asset(path: String, mime: String): Response = try {
        val bytes = App.instance.assets.open(path).use { it.readBytes() }
        newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
    } catch (e: FileNotFoundException) {
        text(Response.Status.NOT_FOUND, "missing $path")
    }

    private fun json(s: String): Response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s)

    private fun text(st: Response.IStatus, s: String): Response = newFixedLengthResponse(st, "text/plain; charset=utf-8", s)

    private fun status(code: Int): Response.IStatus =
        Response.Status.values().firstOrNull { it.requestStatus == code } ?: object : Response.IStatus {
            override fun getDescription(): String = "$code Upstream"
            override fun getRequestStatus(): Int = code
        }

    private fun proxy(session: IHTTPSession): Response {
        val parts = session.uri.removePrefix("/s/").split('/')
        if (parts.size < 2) return text(Response.Status.BAD_REQUEST, "bad path")
        val mid = parts[0].toLongOrNull() ?: return text(Response.Status.BAD_REQUEST, "bad id")
        val media = CastController.media(mid) ?: return text(Response.Status.NOT_FOUND, "投屏已结束")
        val target = runCatching {
            String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return text(Response.Status.BAD_REQUEST, "bad url")

        val b = Request.Builder().url(target)
        media.headers.forEach { (k, v) -> b.header(k, v) }
        session.headers["range"]?.let { b.header("Range", it) }
        val resp = http.newCall(b.build()).execute()
        val body = resp.body ?: run { resp.close(); return text(status(502), "empty") }
        val ctype = resp.header("Content-Type").orEmpty()
        val path = target.substringBefore('?').lowercase()

        val playlist = ctype.contains("mpegurl", true) || path.endsWith(".m3u8") ||
            (!ctype.startsWith("video/") && !ctype.startsWith("audio/") && !ctype.startsWith("image/") &&
                !path.endsWith(".ts") && !path.endsWith(".mp4") && peekM3u8(body))
        if (playlist) {
            val listText = resp.use { body.string() }
            if (!resp.isSuccessful) return text(status(resp.code), listText.take(500))
            val out = M3u8.rewrite(listText, resp.request.url.toString(), mid)
            return newFixedLengthResponse(Response.Status.OK, "application/vnd.apple.mpegurl", out)
        }

        val mime = ctype.ifBlank {
            when {
                path.endsWith(".ts") -> "video/mp2t"
                path.endsWith(".mp4") -> "video/mp4"
                else -> "application/octet-stream"
            }
        }
        val len = body.contentLength()
        val r = if (len >= 0) newFixedLengthResponse(status(resp.code), mime, body.byteStream(), len)
        else newChunkedResponse(status(resp.code), mime, body.byteStream())
        resp.header("Content-Range")?.let { r.addHeader("Content-Range", it) }
        r.addHeader("Accept-Ranges", resp.header("Accept-Ranges") ?: "bytes")
        // 部分电视 / 播放器要这两个 DLNA 头才肯播
        r.addHeader("transferMode.dlna.org", "Streaming")
        r.addHeader("contentFeatures.dlna.org", "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000")
        return r
    }

    private fun peekM3u8(body: okhttp3.ResponseBody): Boolean =
        runCatching { body.source().peek().readUtf8(7) == "#EXTM3U" }.getOrDefault(false)
}

/** 把 m3u8 里的地址（分片、子列表、密钥、初始化分片）改成经手机转发 */
object M3u8 {
    private val URI_ATTR = Regex("URI=\"([^\"]+)\"")

    fun rewrite(text: String, base: String, mid: Long): String {
        val baseUrl = base.toHttpUrlOrNull()
        fun abs(ref: String): String? {
            val r = ref.trim()
            if (r.startsWith("data:") || r.startsWith("skd:")) return null
            return baseUrl?.resolve(r)?.toString() ?: r.takeIf { it.startsWith("http") }
        }
        fun proxied(ref: String, playlistHint: Boolean): String {
            val a = abs(ref) ?: return ref
            val isList = playlistHint || a.substringBefore('?').lowercase().endsWith(".m3u8")
            return CastController.proxyPath(mid, a, isList)
        }
        var nextIsPlaylist = false
        return text.lineSequence().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> {
                    // EXT-X-STREAM-INF 的下一行是子列表
                    if (t.startsWith("#EXT-X-STREAM-INF")) nextIsPlaylist = true
                    val listAttr = t.startsWith("#EXT-X-MEDIA") || t.startsWith("#EXT-X-I-FRAME-STREAM-INF")
                    URI_ATTR.replace(line) { m -> "URI=\"" + proxied(m.groupValues[1], listAttr) + "\"" }
                }
                else -> proxied(t, nextIsPlaylist).also { nextIsPlaylist = false }
            }
        }
    }
}
