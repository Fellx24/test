package com.amurcanov.tgwsproxy.ui

import android.app.Activity
import android.os.Build
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.SettingsStore
import com.amurcanov.tgwsproxy.YoutubeVpnService
import com.amurcanov.tgwsproxy.YoutubeVpnService.Status
import com.amurcanov.tgwsproxy.YouTubeConnectionDiagnostic
import com.amurcanov.tgwsproxy.YouTubeEndpointCheck
import com.amurcanov.tgwsproxy.YouTubeProbeIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun YoutubeControls() {
    val context = LocalContext.current
    val status by YoutubeVpnService.status.collectAsStateWithLifecycle()
    val settingsStore = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val connectionChecks = remember { mutableStateListOf<YouTubeEndpointCheck>() }
    var checkingConnection by remember { mutableStateOf(false) }
    var connectionCheckNotice by remember { mutableStateOf<String?>(null) }
    val selectedProfile by settingsStore.youtubeBypassProfile.collectAsStateWithLifecycle(
        initialValue = SettingsStore.AUTO_YOUTUBE_BYPASS_PROFILE
    )
    val methodOptions = listOf(
        SettingsStore.AUTO_YOUTUBE_BYPASS_PROFILE to R.string.youtube_method_auto,
        0 to R.string.youtube_method_split_tls,
        1 to R.string.youtube_method_disorder_tls,
        2 to R.string.youtube_method_tls_sni,
        3 to R.string.youtube_method_fake_tls,
        4 to R.string.youtube_method_multi_split,
        5 to R.string.youtube_method_multi_disorder,
        6 to R.string.youtube_method_fake_tls_sni
    )
    val canChangeMethod = status == Status.Off || status == Status.Failed

    fun message(resource: Int) = Toast.makeText(context, resource, Toast.LENGTH_SHORT).show()

    fun start() {
        try {
            YoutubeVpnService.start(context, selectedProfile)
        } catch (error: IllegalStateException) {
            message(R.string.youtube_start_failed)
        } catch (error: SecurityException) {
            message(R.string.youtube_start_failed)
        }
    }

    val requestVpn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) start() else message(R.string.youtube_permission_denied)
    }

    AppSectionCard {
        val colors = MaterialTheme.colorScheme
        Text(
            text = stringResource(R.string.youtube_title),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = stringResource(R.string.youtube_summary),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(R.string.youtube_method_title),
            color = colors.onSurface,
            style = MaterialTheme.typography.titleSmall
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            methodOptions.forEach { (profileId, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedProfile == profileId,
                            enabled = canChangeMethod,
                            role = Role.RadioButton
                        ) {
                            scope.launch { settingsStore.saveYoutubeBypassProfile(profileId) }
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedProfile == profileId,
                        onClick = null,
                        enabled = canChangeMethod
                    )
                    Text(
                        text = stringResource(label),
                        color = if (canChangeMethod) colors.onSurface else colors.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        Text(
            text = stringResource(
                when (selectedProfile) {
                    3 -> R.string.youtube_fake_tls_note
                    6 -> R.string.youtube_fake_tls_sni_note
                    else -> R.string.youtube_method_note
                }
            ),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        if (status == Status.Failed) {
            Text(stringResource(R.string.youtube_start_failed), color = colors.error)
        }
        Button(
            onClick = {
                if (status == Status.On) {
                    YoutubeVpnService.stop(context)
                } else {
                    val permission = VpnService.prepare(context)
                    if (permission == null) start() else requestVpn.launch(permission)
                }
            },
            enabled = status != Status.Starting && status != Status.Testing && status != Status.Stopping,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(when (status) {
                Status.On -> R.string.youtube_disable
                Status.Testing -> R.string.youtube_testing
                Status.Starting -> R.string.status_connecting
                Status.Stopping -> R.string.youtube_stopping
                else -> R.string.youtube_enable
            }))
        }
        Button(
            onClick = {
                if (!checkingConnection) {
                    checkingConnection = true
                    connectionCheckNotice = null
                    connectionChecks.clear()
                    scope.launch {
                        try {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                connectionCheckNotice = context.getString(R.string.youtube_connection_android_10_required)
                            } else {
                                coroutineScope {
                                    CONNECTION_CHECK_ENDPOINTS.forEach { (host, path) ->
                                        launch {
                                            val result = try {
                                                withContext(Dispatchers.IO) {
                                                    YouTubeConnectionDiagnostic.check(host, path)
                                                }
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (error: Exception) {
                                                YouTubeEndpointCheck(
                                                    host = host,
                                                    endpointIssue = YouTubeProbeIssue.ENDPOINT_FAILED,
                                                    endpointDetail = error.message?.lineSequence()?.firstOrNull()?.take(100)
                                                )
                                            }
                                            connectionChecks.add(result)
                                        }
                                    }
                                }
                            }
                        } finally {
                            checkingConnection = false
                        }
                    }
                }
            },
            enabled = !checkingConnection,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (checkingConnection) R.string.youtube_connection_checking else R.string.youtube_connection_check))
        }
        Text(
            stringResource(R.string.youtube_connection_direct_note),
            color = colors.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        connectionCheckNotice?.let {
            Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        connectionChecks.forEach { result ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(result.host, color = colors.onSurface, style = MaterialTheme.typography.titleSmall)
                result.endpointIssue?.let { issue ->
                    issueLabel(issue, result.endpointDetail)?.let { label ->
                        Text(label, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    stringResource(R.string.youtube_connection_dns, result.addresses.joinToString().ifBlank {
                        issueLabel(result.dnsIssue, result.dnsDetail)
                            ?: stringResource(R.string.youtube_connection_not_tested)
                    }),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.youtube_connection_tcp, result.tcpAddress?.let {
                        stringResource(R.string.youtube_connection_connected_to, it)
                    } ?: issueLabel(result.tcpIssue, result.tcpDetail)
                        ?: stringResource(R.string.youtube_connection_not_tested)),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.youtube_connection_tls, when {
                        result.tlsIssue != null -> issueLabel(result.tlsIssue, result.tlsDetail)
                            ?: stringResource(R.string.youtube_connection_not_tested)
                        result.tcpAddress != null -> stringResource(R.string.youtube_connection_verified)
                        else -> stringResource(R.string.youtube_connection_not_tested)
                    }),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.youtube_connection_http, result.httpStatus?.let {
                        stringResource(R.string.youtube_connection_status, it)
                    } ?: issueLabel(result.httpIssue, result.httpDetail)
                        ?: stringResource(R.string.youtube_connection_not_tested)),
                    color = colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private val CONNECTION_CHECK_ENDPOINTS = listOf(
    "www.youtube.com" to "/",
    "youtubei.googleapis.com" to "/",
    "redirector.googlevideo.com" to "/videoplayback",
    "i.ytimg.com" to "/"
)

@Composable
private fun issueLabel(issue: YouTubeProbeIssue?, detail: String?): String? {
    if (issue == null) return null
    val label = stringResource(when (issue) {
        YouTubeProbeIssue.ANDROID_VERSION_UNSUPPORTED -> R.string.youtube_connection_android_10_required
        YouTubeProbeIssue.DNS_TIMEOUT -> R.string.youtube_connection_dns_timeout
        YouTubeProbeIssue.DNS_NO_RECORDS -> R.string.youtube_connection_dns_no_records
        YouTubeProbeIssue.DNS_FAILED -> R.string.youtube_connection_dns_failed
        YouTubeProbeIssue.TCP_TIMEOUT -> R.string.youtube_connection_tcp_timeout
        YouTubeProbeIssue.TCP_FAILED -> R.string.youtube_connection_tcp_failed
        YouTubeProbeIssue.TLS_TIMEOUT -> R.string.youtube_connection_tls_timeout
        YouTubeProbeIssue.TLS_FAILED -> R.string.youtube_connection_tls_failed
        YouTubeProbeIssue.HTTP_TIMEOUT -> R.string.youtube_connection_http_timeout
        YouTubeProbeIssue.HTTP_FAILED -> R.string.youtube_connection_http_failed
        YouTubeProbeIssue.ENDPOINT_FAILED -> R.string.youtube_connection_endpoint_failed
    })
    return if (detail.isNullOrBlank()) label else stringResource(R.string.youtube_connection_issue_detail, label, detail)
}
