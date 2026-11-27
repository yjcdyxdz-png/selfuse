package com.example.mediahub.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtil {
    /** 手机在局域网里的 IPv4 地址：优先 Wi-Fi，其次热点；排除 VPN（tun）和移动数据 */
    fun lanIp(): String? = lanAddress()?.second?.hostAddress

    fun lanAddress(): Pair<NetworkInterface, Inet4Address>? {
        val list = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        val cands = list.flatMap { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { nif to it }
        }
        fun pick(test: (String) -> Boolean) = cands.firstOrNull { test(it.first.name.lowercase()) }
        return pick { it.startsWith("wlan") }
            ?: pick { it.startsWith("ap") || it.startsWith("swlan") || it.startsWith("softap") }
            ?: pick { it.startsWith("eth") }
            ?: pick { !it.startsWith("tun") && !it.startsWith("ppp") && !it.startsWith("rmnet") && !it.startsWith("ccmni") }
    }

    /** Wi-Fi 网络。DLNA 控制请求绑定到它，开着 VPN 也能直连局域网里的电脑 */
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return runCatching {
            cm.allNetworks.firstOrNull { n ->
                val c = cm.getNetworkCapabilities(n)
                c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }
        }.getOrNull()
    }
}
