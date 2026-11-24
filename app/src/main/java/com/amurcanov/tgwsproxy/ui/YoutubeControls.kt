package com.amurcanov.tgwsproxy.ui

import android.app.Activity
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.YoutubeVpnService
import com.amurcanov.tgwsproxy.YoutubeVpnService.Status

@Composable
internal fun YoutubeControls() {
    val context = LocalContext.current
    val status by YoutubeVpnService.status.collectAsStateWithLifecycle()

    fun message(resource: Int) = Toast.makeText(context, resource, Toast.LENGTH_SHORT).show()

    fun start() {
        try {
            YoutubeVpnService.start(context)
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
