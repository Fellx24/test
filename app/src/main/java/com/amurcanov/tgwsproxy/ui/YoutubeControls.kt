package com.amurcanov.tgwsproxy.ui

import android.app.Activity
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@Composable
internal fun YoutubeControls() {
    val context = LocalContext.current
    val status by YoutubeVpnService.status.collectAsStateWithLifecycle()
    val settingsStore = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
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
    }
}
