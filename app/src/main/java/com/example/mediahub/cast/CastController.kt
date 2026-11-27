package com.example.mediahub.cast

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.mediahub.App
import com.example.mediahub.engine.HostBridge
import com.example.mediahub.engine.MediaUrl
import com.example.mediahub.player.PlayerFactory
import com.example.mediahub.util.AppLog
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 投屏总控。手机上开一个小服务器（[CastServer]）：
 * - 转发视频：带上 Referer 等请求头去取源站的视频，m3u8 里的分片地址也改成经手机转发，
 *   所以电脑端不需要知道请求头，防盗链的源也能投
 * - 浏览器模式：电脑打开 http://手机IP:端口/ 播放，网页每秒和手机同步状态、接收暂停/跳转指令
 * - DLNA 模式：把转发地址发给 DLNA 播放器，再轮询播放进度
 */
object CastController {
    private val scope get() = App.instance.appScope
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    private var server: CastServer? = null
    private var pollJob: Job? = null
    private var pushJob: Job? = null
    private var mediaSeq = 0L
    private val medias = ConcurrentHashMap<Long, CastMedia>()

    // 浏览器模式的指令（暂停 / 播放 / 跳转），网页按 seq 判断是否是新指令
    private var cmdSeq = 0
    @Volatile private var command: JSONObject? = null
    @Volatile private var endedFor = -1L

    /** 停止投屏时的位置，回到手机播放时接着放 */
    @Volatile private var resumeAt: Pair<String, Long>? = null

    val active: Boolean get() = _state.value.target != null

    fun media(id: Long): CastMedia? = medias[id]

    @Synchronized
    private fun ensureServer(): CastServer {
        server?.let { if (it.isAlive) return it }
        var last: IOException? = null
        for (port in 8686..8699) {
            try {
                val s = CastServer(port)
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                server = s
                AppLog.i("Cast", "投屏服务已启动，端口 $port")
                return s
            } catch (e: IOException) {
                last = e
            }
        }
        throw IOException("无法启动投屏服务：端口 8686～8699 都被占用", last)
    }

    private fun baseUrl(): String {
        val ip = NetUtil.lanIp() ?: error("没有连接 Wi-Fi。手机和电脑要在同一个局域网里才能投屏")
        return "http://$ip:${ensureServer().listeningPort}"
    }

    fun startBrowser(ctx: Context): String {
        val url = baseUrl() + "/"
        switchTarget(ctx, CastTarget.Browser)
        _state.update { it.copy(pageUrl = url) }
        return url
    }

    fun startDlna(ctx: Context, device: DlnaDevice) {
        baseUrl()
        switchTarget(ctx, CastTarget.Dlna(device))
        startPolling(device)
    }

    private fun switchTarget(ctx: Context, target: CastTarget) {
        val old = _state.value
        pollJob?.cancel(); pushJob?.cancel()
        (old.target as? CastTarget.Dlna)?.let { t -> scope.launch { runCatching { Dlna.stop(t.device) } } }
        medias.clear()
        // 新目标从当前进度继续：由播放页重新调用 load
        _state.value = CastState(target = target)
        runCatching { CastService.start(ctx, "投屏到 ${_state.value.targetName}") }
            .onFailure { AppLog.w("Cast", "前台服务启动失败：${it.message}") }
    }

    /** 投一个视频。播放页在开始投屏、换集时调用 */
    fun load(m: MediaUrl, title: String, startMs: Long) {
        val target = _state.value.target ?: return
        val headers = linkedMapOf("User-Agent" to HostBridge.DEFAULT_UA)
        if (m.referer.isNotBlank()) {
            headers["Referer"] = m.referer
            originOf(m.referer)?.let { headers["Origin"] = it }
        }
        headers.putAll(m.headerMap())
        val cm = CastMedia(++mediaSeq, title, m.url, headers, PlayerFactory.isHls(m.url), startMs.coerceAtLeast(0))
        medias[cm.id] = cm
        medias.keys.sorted().dropLast(4).forEach { medias.remove(it) }
        _state.update { it.copy(media = cm, positionMs = cm.startMs, durationMs = 0, playing = false, error = null) }
        AppLog.i("Cast", "投屏 ${cm.title}：${cm.url}（从 ${cm.startMs / 1000} 秒开始）")
        runCatching { CastService.start(App.instance, "${_state.value.targetName}：$title") }
        if (target is CastTarget.Dlna) {
            pushJob?.cancel()
            pushJob = scope.launch { push(target.device, cm) }
        }
    }

    private suspend fun push(d: DlnaDevice, cm: CastMedia) {
        _state.update { it.copy(sending = true, error = null) }
        try {
            val url = baseUrl() + proxyPath(cm)
            val mime = if (cm.hls) "application/vnd.apple.mpegurl" else "video/mp4"
            runCatching { Dlna.stop(d) }
            Dlna.setUri(d, url, Dlna.didl(cm.title, url, mime))
            Dlna.play(d)
            if (cm.startMs > 3000) {
                // 等设备真正开始播放后再跳到断点（有的设备在缓冲时不接受 Seek）
                for (k in 0 until 40) {
                    delay(500)
                    if (runCatching { Dlna.state(d) }.getOrNull() == "PLAYING") break
                }
                runCatching { Dlna.seek(d, cm.startMs) }.onFailure { AppLog.w("Cast", "跳转到断点失败：${it.message}") }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            AppLog.e("Cast", "投屏到 ${d.name} 失败", e)
            _state.update { it.copy(error = "投屏失败：${e.message}") }
        } finally {
            _state.update { it.copy(sending = false) }
        }
    }

    private fun startPolling(d: DlnaDevice) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var wasPlaying = false
            var lastPos = 0L
            var lastDur = 0L
            var fails = 0
            while (isActive) {
                delay(1000)
                val s = _state.value
                val cm = s.media ?: continue
                if (s.sending) { wasPlaying = false; continue }
                try {
                    val (pos, dur) = Dlna.position(d)
                    val st = Dlna.state(d)
                    fails = 0
                    val playing = st == "PLAYING"
                    if (pos > 0) lastPos = pos
                    if (dur > 0) lastDur = dur
                    _state.update {
                        it.copy(playing = playing || st == "TRANSITIONING",
                            positionMs = if (pos > 0 || playing) pos else it.positionMs,
                            durationMs = if (dur > 0) dur else it.durationMs, error = null)
                    }
                    // 播放中变成停止，并且停在接近结尾：这一集播完了
                    if (wasPlaying && st == "STOPPED" && lastDur > 0 && lastPos >= lastDur - 10_000 && endedFor != cm.id) {
                        endedFor = cm.id
                        _events.tryEmit(CastEvent.Ended(cm.id))
                    }
                    wasPlaying = playing
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    if (++fails >= 5) _state.update { it.copy(error = "和 ${d.name} 的连接中断：${e.message}") }
                }
            }
        }
    }

    fun togglePlay() {
        val s = _state.value
        val playing = s.playing
        _state.update { it.copy(playing = !playing) }
        when (val t = s.target) {
            CastTarget.Browser -> sendCommand(if (playing) "pause" else "play", 0)
            is CastTarget.Dlna -> scope.launch {
                runCatching { if (playing) Dlna.pause(t.device) else Dlna.play(t.device) }
                    .onFailure { e -> _state.update { it.copy(error = e.message) } }
            }
            null -> Unit
        }
    }

    fun seek(ms: Long) {
        _state.update { it.copy(positionMs = ms) }
        when (val t = _state.value.target) {
            CastTarget.Browser -> sendCommand("seek", ms)
            is CastTarget.Dlna -> scope.launch {
                runCatching { Dlna.seek(t.device, ms) }.onFailure { e -> _state.update { it.copy(error = e.message) } }
            }
            null -> Unit
        }
    }

    @Synchronized
    private fun sendCommand(action: String, value: Long) {
        cmdSeq++
        command = JSONObject().put("seq", cmdSeq).put("action", action).put("value", value)
    }

    fun stop() {
        val s = _state.value
        val t = s.target ?: return
        s.media?.let { resumeAt = it.url to s.positionMs }
        pollJob?.cancel(); pushJob?.cancel()
        if (t is CastTarget.Dlna) scope.launch { runCatching { Dlna.stop(t.device) } }
        medias.clear()
        command = null
        _state.value = CastState()
        runCatching { CastService.stop(App.instance) }
        AppLog.i("Cast", "已停止投屏")
        // 服务器再留一会儿，让电脑网页看到「已停止」
        scope.launch {
            delay(15_000)
            if (!active) synchronized(this@CastController) { server?.stop(); server = null }
        }
    }

    /** 停止投屏后回到手机播放：同一个视频返回投屏时的位置（只取一次） */
    fun takeResumePosition(url: String): Long? {
        val r = resumeAt ?: return null
        resumeAt = null
        return if (r.first == url && r.second > 0) r.second else null
    }

    // ---------------- 给 CastServer 用 ----------------

    fun proxyPath(cm: CastMedia): String = proxyPath(cm.id, cm.url, cm.hls)

    fun proxyPath(mediaId: Long, url: String, playlist: Boolean): String {
        val b64 = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "/s/$mediaId/$b64/" + fileName(url, playlist)
    }

    private fun fileName(url: String, playlist: Boolean): String {
        val last = runCatching { Uri.parse(url).lastPathSegment }.getOrNull().orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        return when {
            playlist && !last.lowercase().endsWith(".m3u8") -> (last.ifBlank { "index" }) + ".m3u8"
            last.isBlank() -> "media"
            else -> last
        }
    }

    /** 浏览器网页轮询的状态 */
    fun stateJson(): String {
        val s = _state.value
        val o = JSONObject()
        val m = s.media.takeIf { s.target == CastTarget.Browser }
        o.put("active", s.target == CastTarget.Browser)
        o.put("media", m?.let {
            JSONObject().put("id", it.id).put("title", it.title).put("url", proxyPath(it))
                .put("hls", it.hls).put("start", it.startMs / 1000.0)
        } ?: JSONObject.NULL)
        command?.let { o.put("cmd", it) }
        return o.toString()
    }

    /** 浏览器网页每秒回报一次播放状态 */
    fun onReport(p: Map<String, String>) {
        val mid = p["mid"]?.toLongOrNull()
        val cur = _state.value.media
        if (_state.value.target != CastTarget.Browser) return
        _state.update { it.copy(seenAt = System.currentTimeMillis()) }
        if (cur == null || mid != cur.id) return
        val pos = p["pos"]?.toLongOrNull() ?: 0L
        val dur = p["dur"]?.toLongOrNull() ?: 0L
        val playing = p["playing"] == "1"
        _state.update { it.copy(positionMs = pos, durationMs = if (dur > 0) dur else it.durationMs, playing = playing, error = p["err"]?.takeIf { e -> e.isNotBlank() }) }
        if (p["ended"] == "1" && endedFor != cur.id) {
            endedFor = cur.id
            _events.tryEmit(CastEvent.Ended(cur.id))
        }
    }

    private fun originOf(url: String): String? = runCatching {
        val u = Uri.parse(url)
        if (u.scheme == null || u.host == null) null
        else "${u.scheme}://${u.host}" + if (u.port > 0) ":${u.port}" else ""
    }.getOrNull()
}
