package com.example.mediahub.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.example.mediahub.App
import com.example.mediahub.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** 局域网里的 DLNA 播放器（MediaRenderer） */
data class DlnaDevice(
    val name: String,
    val udn: String,
    val location: String,
    val avTransportUrl: String,
    val avTransportType: String,
    val host: String,
)

/** 最小可用的 DLNA 控制端：SSDP 搜索 + AVTransport 的 SOAP 调用 */
object Dlna {
    private val base = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
    private val LOCATION = Regex("(?im)^location:\\s*(\\S+)")

    private fun client(): OkHttpClient {
        val net = NetUtil.wifiNetwork(App.instance) ?: return base
        return base.newBuilder().socketFactory(net.socketFactory).build()
    }

    suspend fun discover(ctx: Context, timeoutMs: Long = 4000): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val (nif, addr) = NetUtil.lanAddress() ?: error("没有连接 Wi-Fi")
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("mediahub-dlna").apply { setReferenceCounted(false); acquire() }
        val locations = LinkedHashSet<String>()
        try {
            MulticastSocket(InetSocketAddress(addr, 0)).use { sock ->
                runCatching { NetUtil.wifiNetwork(ctx)?.bindSocket(sock) }
                runCatching { sock.networkInterface = nif }
                sock.timeToLive = 4
                sock.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                val targets = listOf(
                    "urn:schemas-upnp-org:device:MediaRenderer:1",
                    "urn:schemas-upnp-org:service:AVTransport:1",
                    "ssdp:all",
                )
                repeat(2) {
                    for (st in targets) {
                        val msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                            "MX: 2\r\nST: $st\r\nUSER-AGENT: Android UPnP/1.0 MediaHub/1.0\r\n\r\n").toByteArray()
                        runCatching { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
                    }
                }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(8192)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (e: SocketTimeoutException) { continue }
                    val text = String(p.data, 0, p.length)
                    LOCATION.find(text)?.groupValues?.get(1)?.trim()?.let(locations::add)
                }
            }
        } finally {
            runCatching { lock.release() }
        }
        AppLog.i("Cast", "SSDP 收到 ${locations.size} 个设备描述地址")
        coroutineScope {
            locations.map { loc -> async { runCatching { describe(loc) }.onFailure { AppLog.w("Cast", "读取 $loc 失败：${it.message}") }.getOrNull() } }
                .awaitAll()
        }.filterNotNull().distinctBy { it.udn.ifBlank { it.location } }
    }

    private fun describe(location: String): DlnaDevice? {
        val xml = client().newCall(Request.Builder().url(location).build()).execute().use { it.body?.string() } ?: return null
        if (!xml.contains("AVTransport")) return null
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false; isExpandEntityReferences = false }
        val doc = f.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        fun first(tag: String) = doc.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
        val base = first("URLBase").ifBlank { location }
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val e = services.item(i) as? Element ?: continue
            val type = e.getElementsByTagName("serviceType").item(0)?.textContent?.trim().orEmpty()
            val ctrl = e.getElementsByTagName("controlURL").item(0)?.textContent?.trim().orEmpty()
            if (!type.contains(":AVTransport:") || ctrl.isEmpty()) continue
            val abs = base.toHttpUrlOrNull()?.resolve(ctrl)?.toString() ?: continue
            val host = location.toHttpUrlOrNull()?.host.orEmpty()
            return DlnaDevice(first("friendlyName").ifBlank { host }, first("UDN"), location, abs, type, host)
        }
        return null
    }

    // ---------------- SOAP ----------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private suspend fun call(d: DlnaDevice, action: String, args: List<Pair<String, String>>): String = withContext(Dispatchers.IO) {
        val argXml = args.joinToString("") { (k, v) -> "<$k>${esc(v)}</$k>" }
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:$action xmlns:u=\"${d.avTransportType}\">$argXml</u:$action></s:Body></s:Envelope>"
        val req = Request.Builder().url(d.avTransportUrl)
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .header("SOAPACTION", "\"${d.avTransportType}#$action\"")
            .build()
        client().newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val code = tag(t, "errorCode"); val desc = tag(t, "errorDescription")
                error("$action 失败：HTTP ${r.code}" + (if (code.isNotEmpty()) " $code $desc" else ""))
            }
            t
        }
    }

    private fun tag(xml: String, name: String): String =
        Regex("<(?:[\\w-]+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:[\\w-]+:)?$name>").find(xml)?.groupValues?.get(1)?.trim().orEmpty()

    private val ID = listOf("InstanceID" to "0")

    fun didl(title: String, url: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"><item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${esc(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">${esc(url)}</res></item></DIDL-Lite>"

    suspend fun setUri(d: DlnaDevice, url: String, meta: String) {
        call(d, "SetAVTransportURI", ID + listOf("CurrentURI" to url, "CurrentURIMetaData" to meta))
    }
    suspend fun play(d: DlnaDevice) { call(d, "Play", ID + listOf("Speed" to "1")) }
    suspend fun pause(d: DlnaDevice) { call(d, "Pause", ID) }
    suspend fun stop(d: DlnaDevice) { call(d, "Stop", ID) }
    suspend fun seek(d: DlnaDevice, ms: Long) { call(d, "Seek", ID + listOf("Unit" to "REL_TIME", "Target" to fmt(ms))) }

    /** (当前位置, 总时长)，毫秒 */
    suspend fun position(d: DlnaDevice): Pair<Long, Long> {
        val t = call(d, "GetPositionInfo", ID)
        return parse(tag(t, "RelTime")) to parse(tag(t, "TrackDuration"))
    }

    /** PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING / NO_MEDIA_PRESENT */
    suspend fun state(d: DlnaDevice): String = tag(call(d, "GetTransportInfo", ID), "CurrentTransportState")

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    private fun parse(t: String): Long {
        val m = Regex("^(\\d+):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d+))?").find(t.trim()) ?: return 0L
        val (h, mi, s) = m.destructured
        return (h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000
    }
}
