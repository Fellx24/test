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
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

class YoutubeVpnService : VpnService() {
    enum class Status { Off, Starting, Testing, On, Stopping, Failed }

    private data class BypassProfile(val id: Int, val name: String)
    private data class ProbeEndpoint(val host: String, val httpPath: String? = null)
    private data class ServerProbe(
        val host: String,
        val reachable: Boolean,
        val tlsHandshakeCompleted: Boolean,
        val elapsedMs: Long,
        val tlsHandshakeElapsedMs: Long? = null,
        val httpStatus: Int? = null,
        val error: String? = null
    )
    private data class ProfileProbe(
        val profile: BypassProfile,
        val reachableCount: Int,
        val tlsHandshakeCount: Int
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: Job? = null
    private var destroyed = false
    private var previousTunnelStats: LongArray? = null
    private var lastTunnelStatsLogAtMs = 0L
    private var tunnelStatsWarningLogged = false

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
        val requestedProfileId = intent.getIntExtra(
            EXTRA_BYPASS_PROFILE,
            SettingsStore.AUTO_YOUTUBE_BYPASS_PROFILE
        ).let { profileId ->
            if (profileId == SettingsStore.AUTO_YOUTUBE_BYPASS_PROFILE || BYPASS_PROFILES.any { it.id == profileId }) {
                profileId
            } else {
                SettingsStore.AUTO_YOUTUBE_BYPASS_PROFILE
            }
        }

        Log.i(TAG, "YouTube local VPN start requested")
        updateForeground(R.string.status_connecting)
        _status.value = Status.Starting
        session = scope.launch {
            var failed = false
            try {
                engineLock.withLock { runSession(requestedProfileId) }
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
        Log.w(TAG, "Android revoked the YouTube local VPN")
        Handler(Looper.getMainLooper()).post { if (!destroyed) stopSession() }
    }

    override fun onDestroy() {
        Log.i(TAG, "YouTube local VPN service destroyed")
        destroyed = true
        scope.cancel()
        if (_status.value != Status.Failed) _status.value = Status.Off
        super.onDestroy()
    }

    private fun stopSession() {
        Log.i(TAG, "YouTube local VPN stop requested")
        val activeSession = session
        if (activeSession == null) {
            stopSelf()
        } else {
            _status.value = Status.Stopping
            activeSession.cancel()
        }
    }

    private suspend fun runSession(requestedProfileId: Int) {
        check(prepare(this) == null) { "Android VPN authorization was revoked" }
        val profile = selectBypassProfile(requestedProfileId)
        coroutineContext.ensureActive()
        Log.i(TAG, "YouTube VPN selected ${profile.name}")

        val builder = Builder()
            .setSession(getString(R.string.youtube_title))
            .setMtu(MTU)
            .addAddress(IPV4, 32)
            .addAddress(IPV6, 128)
            .addDnsServer(DNS_IPV4)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDisallowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val port = YoutubeDpi.start(profile.id)
        check(port > 0) { "Could not start the local YouTube proxy" }
        try {
            Log.i(TAG, "YouTube local proxy started with ${profile.name}")
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
                    mapdns:
                      address: $DNS_IPV4
                      port: 53
                      network: '100.64.0.0'
                      netmask: '255.192.0.0'
                      cache-size: 10000
                    misc:
                      log-level: error
                    """.trimIndent()
                )
                val tun = checkNotNull(builder.establish()) { "Could not establish the YouTube tunnel" }
                tun.use {
                    Log.i(TAG, "YouTube local VPN interface established")
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

    private suspend fun selectBypassProfile(requestedProfileId: Int): BypassProfile {
        withContext(Dispatchers.Main.immediate) {
            updateForeground(R.string.youtube_testing)
            _status.value = Status.Testing
        }
        val selectedProfile = BYPASS_PROFILES.firstOrNull { it.id == requestedProfileId }
        val profiles = selectedProfile?.let { listOf(it) } ?: BYPASS_PROFILES
        val selectionMode = if (selectedProfile == null) "automatic" else "manual"
        Log.i(TAG, "YouTube server check started ($selectionMode, ${PROBE_ENDPOINTS.size} endpoints)")

        var best: ProfileProbe? = null
        for (profile in profiles) {
            coroutineContext.ensureActive()
            val result = probeProfile(profile)
            val previousBest = best
            if (
                previousBest == null ||
                result.reachableCount > previousBest.reachableCount ||
                (result.reachableCount == previousBest.reachableCount &&
                    result.tlsHandshakeCount > previousBest.tlsHandshakeCount)
            ) {
                best = result
            }
            if (result.reachableCount == PROBE_ENDPOINTS.size) {
                Log.i(TAG, "${profile.name} reached all ${PROBE_ENDPOINTS.size} YouTube endpoints")
                return profile
            }
        }

        val chosen = checkNotNull(best) { "No YouTube bypass profile could be started" }
        if (selectedProfile != null) {
            Log.w(
                TAG,
                "Keeping manually selected ${selectedProfile.name} despite " +
                    "${chosen.reachableCount}/${PROBE_ENDPOINTS.size} complete probes and " +
                    "${chosen.tlsHandshakeCount}/${PROBE_ENDPOINTS.size} completed TLS handshakes"
            )
            return selectedProfile
        }
        if (chosen.reachableCount == 0) {
            Log.w(
                TAG,
                "No bypass profile completed an HTTP probe; using ${chosen.profile.name} " +
                    "(${chosen.tlsHandshakeCount}/${PROBE_ENDPOINTS.size} TLS handshakes completed)"
            )
        } else {
            Log.w(
                TAG,
                "No profile reached every endpoint; using ${chosen.profile.name} " +
                    "(${chosen.reachableCount}/${PROBE_ENDPOINTS.size} complete probes, " +
                    "${chosen.tlsHandshakeCount}/${PROBE_ENDPOINTS.size} TLS handshakes)"
            )
        }
        return chosen.profile
    }

    private suspend fun probeProfile(profile: BypassProfile): ProfileProbe {
        coroutineContext.ensureActive()
        val port = YoutubeDpi.start(profile.id)
        if (port <= 0) {
            Log.e(TAG, "Could not start YouTube bypass profile ${profile.name}")
            return ProfileProbe(profile, reachableCount = 0, tlsHandshakeCount = 0)
        }

        try {
            val results = coroutineScope {
                PROBE_ENDPOINTS.map { endpoint ->
                    async(Dispatchers.IO) { probeServer(port, endpoint) }
                }.awaitAll()
            }
            results.forEach { result ->
                if (result.reachable) {
                    val response = result.httpStatus?.let { "; HTTP headers available (status $it)" }.orEmpty()
                    Log.i(
                        TAG,
                        "YouTube server ${result.host}: TLS handshake completed in " +
                            "${result.tlsHandshakeElapsedMs} ms$response (total ${result.elapsedMs} ms)"
                    )
                } else if (result.tlsHandshakeCompleted) {
                    Log.w(
                        TAG,
                        "YouTube server ${result.host}: TLS handshake completed in " +
                            "${result.tlsHandshakeElapsedMs} ms; ${result.error ?: "HTTP probe failed"}"
                    )
                } else {
                    Log.w(TAG, "YouTube server ${result.host}: ${result.error ?: "unavailable"}")
                }
            }
            val count = results.count { it.reachable }
            val handshakeCount = results.count { it.tlsHandshakeCompleted }
            Log.i(
                TAG,
                "${profile.name}: $handshakeCount/${PROBE_ENDPOINTS.size} TLS handshakes completed; " +
                    "$count/${PROBE_ENDPOINTS.size} TLS/certificate probes completed"
            )
            return ProfileProbe(profile, count, handshakeCount)
        } finally {
            YoutubeDpi.stop()
        }
    }

    private fun probeServer(port: Int, endpoint: ProbeEndpoint): ServerProbe {
        val startedAt = System.nanoTime()
        var tlsSocket: SSLSocket? = null
        var tlsHandshakeCompleted = false
        var tlsHandshakeElapsedMs: Long? = null
        val socket = Socket()
        val host = endpoint.host
        var stage = "local proxy connection"
        return try {
            socket.connect(InetSocketAddress(IPV4_LOOPBACK, port), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            stage = "SOCKS5 connect"
            connectSocks5(socket, host)

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = factory.createSocket(socket, host, HTTPS_PORT, true) as SSLSocket
            tlsSocket = tls
            tls.soTimeout = PROBE_TIMEOUT_MS
            stage = "TLS handshake"
            val handshakeStartedAt = System.nanoTime()
            try {
                tls.startHandshake()
            } finally {
                tlsHandshakeElapsedMs = elapsedMillis(handshakeStartedAt)
            }
            tlsHandshakeCompleted = true
            stage = "TLS certificate validation"
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                throw SSLPeerUnverifiedException("certificate name does not match")
            }

            stage = "HTTP response"
            val httpStatus = endpoint.httpPath?.let { probeHttpStatus(tls, host, it) }
            ServerProbe(
                host = host,
                reachable = true,
                tlsHandshakeCompleted = tlsHandshakeCompleted,
                elapsedMs = elapsedMillis(startedAt),
                tlsHandshakeElapsedMs = tlsHandshakeElapsedMs,
                httpStatus = httpStatus
            )
        } catch (error: Exception) {
            val probeFailure = probeError(error, stage).let { message ->
                if (stage == "TLS handshake" && tlsHandshakeElapsedMs != null) {
                    "$message after ${tlsHandshakeElapsedMs} ms"
                } else {
                    message
                }
            }
            ServerProbe(
                host = host,
                reachable = false,
                tlsHandshakeCompleted = tlsHandshakeCompleted,
                elapsedMs = elapsedMillis(startedAt),
                tlsHandshakeElapsedMs = tlsHandshakeElapsedMs,
                error = probeFailure
            )
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { socket.close() }
        }
    }

    private fun probeHttpStatus(tls: SSLSocket, host: String, path: String): Int {
        val request = "HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
        tls.outputStream.write(request.toByteArray(Charsets.US_ASCII))
        tls.outputStream.flush()

        val statusLine = tls.inputStream.bufferedReader(Charsets.US_ASCII).readLine()
            ?: throw EOFException("HTTP connection closed before response headers")
        return HttpStatusLine.parse(statusLine)
    }

    private fun connectSocks5(socket: Socket, host: String) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        if (!readExactly(input, 2).contentEquals(byteArrayOf(0x05, 0x00))) {
            throw IOException("SOCKS5 authentication negotiation failed")
        }

        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        if (hostBytes.isEmpty() || hostBytes.size > 255) throw IOException("Invalid server name")
        val request = ByteArray(hostBytes.size + 7)
        request[0] = 0x05
        request[1] = 0x01
        request[2] = 0x00
        request[3] = 0x03
        request[4] = hostBytes.size.toByte()
        hostBytes.copyInto(request, 5)
        request[request.lastIndex - 1] = (HTTPS_PORT shr 8).toByte()
        request[request.lastIndex] = HTTPS_PORT.toByte()
        output.write(request)
        output.flush()

        val reply = readExactly(input, 4)
        val status = reply[1].toInt() and 0xff
        if (reply[0].toInt() != 0x05 || status != 0) {
            throw IOException("SOCKS5 connect failed ($status)")
        }
        val addressLength = when (reply[3].toInt() and 0xff) {
            0x01 -> 4
            0x03 -> readExactly(input, 1)[0].toInt() and 0xff
            0x04 -> 16
            else -> throw IOException("SOCKS5 returned an invalid address")
        }
        readExactly(input, addressLength + 2)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("SOCKS5 closed the connection")
            offset += read
        }
        return result
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private fun probeError(error: Exception, stage: String): String {
        if (findCause<SocketTimeoutException>(error) != null) {
            return when (stage) {
                "TLS handshake" -> "TLS handshake timeout"
                "TLS certificate validation" -> "TLS certificate validation timeout"
                "HTTP response" -> "HTTP response timeout after successful TLS handshake"
                else -> "$stage timeout"
            }
        }
        if (findCause<UnknownHostException>(error) != null) return "DNS failed during $stage"
        if (findCause<SSLPeerUnverifiedException>(error) != null) return "TLS certificate check failed"
        if (stage == "TLS handshake" && findCause<SSLHandshakeException>(error) != null) {
            val detail = error.message?.lineSequence()?.firstOrNull()?.take(60)
            return if (detail.isNullOrBlank()) "TLS handshake failed" else "TLS handshake failed: $detail"
        }
        val detail = error.message?.lineSequence()?.firstOrNull()?.take(60)
            ?: error.javaClass.simpleName
        return "$stage: $detail"
    }

    private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 16) {
            if (cause is T) return cause
            cause = cause?.cause
            depth++
        }
        return null
    }

    private fun checkEngines() {
        if (!YoutubeDpi.isRunning() || !YoutubeTunnel.TProxyIsRunning()) {
            Log.e(TAG, "YouTube local VPN engine stopped unexpectedly")
        }
        check(YoutubeDpi.isRunning() && YoutubeTunnel.TProxyIsRunning()) {
            "A YouTube networking component stopped"
        }

        val stats = runCatching { YoutubeTunnel.TProxyGetStats() }.getOrNull()
        if (stats == null || stats.size != TUNNEL_STATS_FIELDS) {
            if (!tunnelStatsWarningLogged) {
                Log.w(TAG, "TUN packet counters are unavailable; playback flow cannot be diagnosed")
                tunnelStatsWarningLogged = true
            }
            previousTunnelStats = null
            return
        }

        tunnelStatsWarningLogged = false
        val now = SystemClock.elapsedRealtime()
        val previous = previousTunnelStats
        if (previous == null) {
            previousTunnelStats = stats.copyOf()
            lastTunnelStatsLogAtMs = now
            Log.i(TAG, "TUN packet counters initialized: TX ${stats[0]} packets/${stats[1]} bytes, " +
                "RX ${stats[2]} packets/${stats[3]} bytes")
            return
        }

        val elapsedMs = now - lastTunnelStatsLogAtMs
        if (elapsedMs >= TUNNEL_STATS_LOG_INTERVAL_MS) {
            val delta = LongArray(TUNNEL_STATS_FIELDS) { index ->
                (stats[index] - previous[index]).coerceAtLeast(0L)
            }
            val seconds = elapsedMs / 1_000L
            Log.i(TAG, "TUN traffic +${seconds}s: TX ${delta[0]} packets/${delta[1]} bytes, " +
                "RX ${delta[2]} packets/${delta[3]} bytes")
            previousTunnelStats = stats.copyOf()
            lastTunnelStatsLogAtMs = now
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
        private const val EXTRA_BYPASS_PROFILE = "com.amurcanov.tgwsproxy.youtube.BYPASS_PROFILE"
        private const val TAG = "YoutubeVpnService"
        private const val CHANNEL = "youtube_vpn"
        private const val NOTIFICATION_ID = 102
        private const val MTU = 1500
        private const val IPV4 = "198.18.0.1"
        private const val IPV4_LOOPBACK = "127.0.0.1"
        private const val DNS_IPV4 = "198.18.0.2"
        private const val IPV6 = "fd00:7467:7773::1"
        private const val HTTPS_PORT = 443
        private const val PROBE_TIMEOUT_MS = 2_500
        private const val TUNNEL_STATS_FIELDS = 4
        private const val TUNNEL_STATS_LOG_INTERVAL_MS = 10_000L
        private val PROBE_ENDPOINTS = listOf(
            ProbeEndpoint("www.youtube.com"),
            ProbeEndpoint("redirector.googlevideo.com", "/videoplayback"),
            ProbeEndpoint("youtubei.googleapis.com"),
            ProbeEndpoint("jnn-pa.googleapis.com"),
            ProbeEndpoint("i.ytimg.com"),
            ProbeEndpoint("www.youtubekids.com"),
            ProbeEndpoint("music.youtube.com")
        )
        private val BYPASS_PROFILES = listOf(
            BypassProfile(0, "Разделение SNI и TLS-записи"),
            BypassProfile(1, "Разделение и переупорядочивание TLS-пакетов"),
            BypassProfile(2, "Разделение TLS-записи внутри SNI"),
            BypassProfile(3, "Fake TLS (TTL 8)")
        )

        private val engineLock = Mutex()
        private val _status = MutableStateFlow(Status.Off)
        val status = _status.asStateFlow()

        internal fun start(
            context: Context,
            profileId: Int = SettingsStore.AUTO_YOUTUBE_BYPASS_PROFILE
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, YoutubeVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_BYPASS_PROFILE, profileId)
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

    external fun start(profile: Int): Int
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
