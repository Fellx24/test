package com.amurcanov.tgwsproxy

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.asExecutor
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class YouTubeEndpointCheck(
    val host: String,
    val addresses: List<String> = emptyList(),
    val dnsIssue: YouTubeProbeIssue? = null,
    val dnsDetail: String? = null,
    val tcpAddress: String? = null,
    val tcpIssue: YouTubeProbeIssue? = null,
    val tcpDetail: String? = null,
    val tlsIssue: YouTubeProbeIssue? = null,
    val tlsDetail: String? = null,
    val httpStatus: Int? = null,
    val httpIssue: YouTubeProbeIssue? = null,
    val httpDetail: String? = null,
    val endpointIssue: YouTubeProbeIssue? = null,
    val endpointDetail: String? = null
)

internal enum class YouTubeProbeIssue {
    ANDROID_VERSION_UNSUPPORTED, DNS_TIMEOUT, DNS_NO_RECORDS, DNS_FAILED,
    TCP_TIMEOUT, TCP_FAILED, TLS_TIMEOUT, TLS_FAILED, HTTP_TIMEOUT, HTTP_FAILED, ENDPOINT_FAILED
}

/** Direct app traffic is excluded from this app's own VPN, so these checks exercise the device network. */
internal object YouTubeConnectionDiagnostic {
    private data class FamilyResult(
        val type: Int,
        val addresses: List<InetAddress>,
        val timedOut: Boolean,
        val error: String? = null
    )
    private enum class Stage { DNS, TCP, TLS, HTTP }

    private const val PORT = 443
    private const val DNS_TIMEOUT_MS = 2_000L
    private const val HOST_TIMEOUT_MS = 8_000L
    private const val TCP_TIMEOUT_MS = 2_500L
    private const val CONNECT_TIMEOUT_MS = 1_500
    private const val IO_TIMEOUT_MS = 1_500
    private const val MAX_TCP_CANDIDATES = 4

    suspend fun check(host: String, path: String): YouTubeEndpointCheck {
        val currentStage = AtomicReference(Stage.DNS)
        val resolvedAddresses = AtomicReference(emptyList<String>())
        val tcpAddress = AtomicReference<String?>(null)
        return withTimeoutOrNull(HOST_TIMEOUT_MS) {
            checkWithinDeadline(host, path, currentStage, resolvedAddresses, tcpAddress)
        }
            ?: when (currentStage.get()) {
                Stage.DNS -> YouTubeEndpointCheck(host, dnsIssue = YouTubeProbeIssue.DNS_TIMEOUT)
                Stage.TCP -> YouTubeEndpointCheck(
                    host, addresses = resolvedAddresses.get(), tcpIssue = YouTubeProbeIssue.TCP_TIMEOUT
                )
                Stage.TLS -> YouTubeEndpointCheck(
                    host, addresses = resolvedAddresses.get(), tcpAddress = tcpAddress.get(),
                    tlsIssue = YouTubeProbeIssue.TLS_TIMEOUT
                )
                Stage.HTTP -> YouTubeEndpointCheck(
                    host, addresses = resolvedAddresses.get(), tcpAddress = tcpAddress.get(),
                    httpIssue = YouTubeProbeIssue.HTTP_TIMEOUT
                )
            }
    }

    private suspend fun checkWithinDeadline(
        host: String,
        path: String,
        currentStage: AtomicReference<Stage>,
        resolvedAddresses: AtomicReference<List<String>>,
        tcpAddress: AtomicReference<String?>
    ): YouTubeEndpointCheck {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return YouTubeEndpointCheck(host, dnsIssue = YouTubeProbeIssue.ANDROID_VERSION_UNSUPPORTED)
        }

        val familyResults = try {
            withContext(Dispatchers.IO) {
                listOf(DnsResolver.TYPE_A, DnsResolver.TYPE_AAAA).map { type ->
                    async {
                        try {
                            val answers = withTimeoutOrNull(DNS_TIMEOUT_MS) { query(host, type) }
                            FamilyResult(type, answers.orEmpty(), answers == null)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            FamilyResult(type, emptyList(), timedOut = false, error = shortError(error))
                        }
                    }
                }.awaitAll()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            return YouTubeEndpointCheck(host, dnsIssue = YouTubeProbeIssue.DNS_FAILED, dnsDetail = shortError(error))
        }
        val dnsAnswers = interleaveFamilies(familyResults)
        if (dnsAnswers.isEmpty()) {
            val issue = when {
                familyResults.any(FamilyResult::timedOut) -> YouTubeProbeIssue.DNS_TIMEOUT
                familyResults.any { it.error != null } -> YouTubeProbeIssue.DNS_FAILED
                else -> YouTubeProbeIssue.DNS_NO_RECORDS
            }
            val detail = familyResults.mapNotNull(FamilyResult::error).distinct().joinToString("; ").ifBlank { null }
            return YouTubeEndpointCheck(host, dnsIssue = issue, dnsDetail = detail)
        }

        val addresses = dnsAnswers.mapNotNull(InetAddress::getHostAddress)
        resolvedAddresses.set(addresses)
        val deadlineNanos = System.nanoTime() + TCP_TIMEOUT_MS * 1_000_000L
        var lastTcpIssue: YouTubeProbeIssue? = null
        var lastTcpDetail: String? = null
        currentStage.set(Stage.TCP)
        for (address in dnsAnswers.take(MAX_TCP_CANDIDATES)) {
            val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMs <= 0) {
                lastTcpIssue = YouTubeProbeIssue.TCP_TIMEOUT
                break
            }
            val remainingCandidates = minOf(MAX_TCP_CANDIDATES, dnsAnswers.size) -
                dnsAnswers.take(MAX_TCP_CANDIDATES).indexOf(address)
            val perCandidateTimeout = (remainingMs / remainingCandidates.coerceAtLeast(1)).coerceAtLeast(1)
            val result = probeAddress(host, path, address, perCandidateTimeout, currentStage, tcpAddress)
            if (result.tcpAddress != null) return result.copy(addresses = addresses)
            lastTcpIssue = result.tcpIssue
            lastTcpDetail = result.tcpDetail
            if (System.nanoTime() >= deadlineNanos) {
                lastTcpIssue = YouTubeProbeIssue.TCP_TIMEOUT
                lastTcpDetail = null
                break
            }
        }
        return YouTubeEndpointCheck(
            host = host,
            addresses = addresses,
            tcpIssue = lastTcpIssue ?: YouTubeProbeIssue.TCP_FAILED,
            tcpDetail = lastTcpDetail
        )
    }

    private fun interleaveFamilies(families: List<FamilyResult>): List<InetAddress> {
        val byType = families.associate { it.type to it.addresses }
        val ipv4 = byType[DnsResolver.TYPE_A].orEmpty()
        val ipv6 = byType[DnsResolver.TYPE_AAAA].orEmpty()
        val interleaved = buildList {
            repeat(maxOf(ipv4.size, ipv6.size)) { index ->
                ipv4.getOrNull(index)?.let(::add)
                ipv6.getOrNull(index)?.let(::add)
            }
        }
        return interleaved.distinct()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun query(host: String, type: Int): List<InetAddress> =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                DnsResolver.getInstance().query(
                    null,
                    host,
                    type,
                    DnsResolver.FLAG_EMPTY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    object : DnsResolver.Callback<List<InetAddress>> {
                        override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                            if (!continuation.isActive) return
                            if (rcode == 0) continuation.resume(answer)
                            else continuation.resumeWithException(UnknownHostException("DNS response code $rcode"))
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    UnknownHostException(error.message ?: "DNS query failed")
                                )
                            }
                        }
                    }
                )
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private suspend fun probeAddress(
        host: String,
        path: String,
        address: InetAddress,
        timeoutMs: Int,
        currentStage: AtomicReference<Stage>,
        tcpAddress: AtomicReference<String?>
    ): YouTubeEndpointCheck = suspendCancellableCoroutine { continuation ->
        val socket = Socket()
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
            var tlsSocket: SSLSocket? = null
            var result: YouTubeEndpointCheck? = null
            try {
                socket.connect(InetSocketAddress(address, PORT), timeoutMs.coerceAtMost(CONNECT_TIMEOUT_MS))
                val ip = address.hostAddress ?: address.toString()
                tcpAddress.set(ip)
                currentStage.set(Stage.TLS)
                socket.soTimeout = IO_TIMEOUT_MS
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(socket, host, PORT, true) as SSLSocket
                tlsSocket = tls
                tls.soTimeout = IO_TIMEOUT_MS
                tls.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                    throw SSLPeerUnverifiedException("certificate name does not match")
                }

                currentStage.set(Stage.HTTP)
                result = try {
                    tls.outputStream.write(
                        "HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                            .toByteArray(Charsets.US_ASCII)
                    )
                    tls.outputStream.flush()
                    val status = HttpStatusLine.parse(readStatusLine(tls))
                    YouTubeEndpointCheck(host = host, tcpAddress = ip, httpStatus = status)
                } catch (error: Exception) {
                    YouTubeEndpointCheck(
                        host = host,
                        tcpAddress = ip,
                        httpIssue = if (error is SocketTimeoutException) YouTubeProbeIssue.HTTP_TIMEOUT
                        else YouTubeProbeIssue.HTTP_FAILED,
                        httpDetail = if (error is SocketTimeoutException) null else shortError(error)
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                result = if (!socket.isConnected) {
                    YouTubeEndpointCheck(
                        host = host,
                        tcpIssue = if (error is SocketTimeoutException) YouTubeProbeIssue.TCP_TIMEOUT
                        else YouTubeProbeIssue.TCP_FAILED,
                        tcpDetail = if (error is SocketTimeoutException) null else shortError(error)
                    )
                } else {
                    YouTubeEndpointCheck(
                        host = host,
                        tcpAddress = address.hostAddress,
                        tlsIssue = if (error is SocketTimeoutException) YouTubeProbeIssue.TLS_TIMEOUT
                        else YouTubeProbeIssue.TLS_FAILED,
                        tlsDetail = if (error is SocketTimeoutException) null else shortError(error)
                    )
                }
            } finally {
                runCatching { socket.close() }
                runCatching { tlsSocket?.close() }
            }
            result?.let {
                if (continuation.isActive) continuation.resume(it)
            }
        }
    }

    private fun shortError(error: Exception): String =
        error.message?.lineSequence()?.firstOrNull()?.take(100) ?: error.javaClass.simpleName

    private fun readStatusLine(socket: SSLSocket): String {
        val output = java.io.ByteArrayOutputStream()
        val input = socket.inputStream
        while (output.size() < MAX_STATUS_LINE_BYTES) {
            val value = input.read()
            if (value < 0) throw EOFException("HTTP connection closed before response")
            if (value == '\n'.code) {
                val bytes = output.toByteArray().let { line ->
                    if (line.lastOrNull() == '\r'.code.toByte()) line.copyOf(line.size - 1) else line
                }
                return bytes.toString(Charsets.US_ASCII)
            }
            output.write(value)
        }
        throw java.io.IOException("HTTP status line is too long")
    }

    private const val MAX_STATUS_LINE_BYTES = 4_096
}
