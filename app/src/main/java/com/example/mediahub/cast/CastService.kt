package com.example.mediahub.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 投屏期间的前台服务：保持 Wi-Fi 和 CPU 不休眠，手机锁屏后电脑也能继续从手机取视频。
 * 通知栏里有「停止投屏」按钮。
 */
class CastService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CastController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "投屏", NotificationManager.IMPORTANCE_LOW))
        }
        val flagsPi = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 1, Intent(this, CastService::class.java).setAction(ACTION_STOP), flagsPi)
        val openPi = packageManager.getLaunchIntentForPackage(packageName)?.let { PendingIntent.getActivity(this, 2, it, flagsPi) }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("正在投屏")
            .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: "MediaHub 投屏服务运行中")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, "停止投屏", stopPi)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIFY_ID, n, type)
        acquireLocks()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mediahub:cast").apply {
                setReferenceCounted(false); acquire()
            }
        }
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mediahub:cast").apply {
                setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onDestroy() {
        runCatching { wifiLock?.release() }
        runCatching { wakeLock?.release() }
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "cast"
        private const val NOTIFY_ID = 3001
        private const val ACTION_STOP = "com.example.mediahub.cast.STOP"
        private const val EXTRA_TEXT = "text"

        fun start(ctx: Context, text: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CastService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CastService::class.java))
        }
    }
}
