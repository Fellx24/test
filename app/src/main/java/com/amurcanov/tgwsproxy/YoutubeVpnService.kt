package com.amurcanov.tgwsproxy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class YoutubeVpnService : VpnService() {
    enum class Status { Off, Starting, On, Stopping, Failed }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Job? = null
    private var destroyed = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) {
            if (session == null) stopSelf()
            return START_NOT_STICKY
        }
        if (session != null) return START_NOT_STICKY

        updateForeground(R.string.status_connecting)
        _status.value = Status.Starting
        session = scope.launch {
            var failed = false
            try {
                engineLock.withLock { runSession() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "YouTube VPN failed", error)
                failed = true
            } catch (error: LinkageError) {
                Log.e(TAG, "YouTube VPN library failed to load", error)
                failed = true
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    if (!destroyed) {
                        if (failed) _status.value = Status.Failed
                        else _status.value = Status.Stopping
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        Handler(Looper.getMainLooper()).post { if (!destroyed) stopSession() }
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        if (_status.value != Status.Failed) _status.value = Status.Off
        super.onDestroy()
    }

    private fun stopSession() {
        val activeSession = session
        if (activeSession == null) {
            stopSelf()
        } else {
            _status.value = Status.Stopping
            activeSession.cancel()
        }
    }

    private suspend fun runSession() {
        check(prepare(this) == null) { "Android VPN authorization was revoked" }
        val builder = Builder()
            .setSession(getString(R.string.youtube_title))
            .setMtu(MTU)
            .addAddress(IPV4, 32)
            .addAddress(IPV6, 128)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val port = YoutubeDpi.start()
        check(port > 0) { "Could not start the local YouTube proxy" }
        try {
            coroutineContext.ensureActive()
            val config = File.createTempFile("youtube-vpn-", ".yml", cacheDir)
            try {
                config.writeText(
                    """
                    tunnel:
                      mtu: $MTU
                      ipv4: $IPV4
                      ipv6: '$IPV6'
                    socks5:
                      address: 127.0.0.1
                      port: $port
                      udp: 'udp'
                    misc:
                      log-level: error
                    """.trimIndent()
                )
                val tun = checkNotNull(builder.establish()) { "Could not establish the YouTube tunnel" }
                tun.use {
                    check(YoutubeTunnel.TProxyStartService(config.absolutePath, tun.fd)) {
                        "Could not start the YouTube packet relay"
                    }
                    try {
                        delay(250)
                        checkEngines()
                        withContext(Dispatchers.Main.immediate) {
                            updateForeground(R.string.youtube_vpn_running)
                            _status.value = Status.On
                        }
                        while (true) {
                            delay(1_000)
                            checkEngines()
                        }
                    } finally {
                        YoutubeTunnel.TProxyStopService()
                    }
                }
            } finally {
                config.delete()
            }
        } finally {
            YoutubeDpi.stop()
        }
    }

    private fun checkEngines() {
        check(YoutubeDpi.isRunning() && YoutubeTunnel.TProxyIsRunning()) {
            "A YouTube networking component stopped"
        }
    }

    private fun updateForeground(message: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.youtube_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, NOTIFICATION_ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, NOTIFICATION_ID, Intent(this, YoutubeVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(getString(R.string.youtube_title))
            .setContentText(getString(message))
            .setContentIntent(open)
            .addAction(0, getString(R.string.youtube_disable), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val ACTION_START = "com.amurcanov.tgwsproxy.youtube.START"
        private const val ACTION_STOP = "com.amurcanov.tgwsproxy.youtube.STOP"
        private const val TAG = "YoutubeVpnService"
        private const val CHANNEL = "youtube_vpn"
        private const val NOTIFICATION_ID = 102
        private const val MTU = 1500
        private const val IPV4 = "198.18.0.1"
        private const val IPV6 = "fd00:7467:7773::1"

        private val engineLock = Mutex()
        private val _status = MutableStateFlow(Status.Off)
        val status = _status.asStateFlow()

        internal fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, YoutubeVpnService::class.java).setAction(ACTION_START)
            )
        }

        internal fun stop(context: Context) {
            context.startService(Intent(context, YoutubeVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}

@Keep
internal object YoutubeDpi {
    init { System.loadLibrary("youtube-dpi") }

    external fun start(): Int
    external fun isRunning(): Boolean
    external fun stop()
}

@Keep
internal object YoutubeTunnel {
    init { System.loadLibrary("hev-socks5-tunnel") }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray
}
