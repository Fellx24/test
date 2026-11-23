package com.amurcanov.tgwsproxy.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.amurcanov.tgwsproxy.AppReleaseInfo
import com.amurcanov.tgwsproxy.AppUpdateException
import com.amurcanov.tgwsproxy.AppUpdateFailure
import com.amurcanov.tgwsproxy.R
import com.amurcanov.tgwsproxy.appUpdateInstallSettings
import com.amurcanov.tgwsproxy.canInstallAppUpdates
import com.amurcanov.tgwsproxy.launchAppUpdateInstaller
import com.amurcanov.tgwsproxy.prepareAppUpdate
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

@Composable
fun AppUpdateDialog(
    release: AppReleaseInfo,
    onPostpone: () -> Unit,
    onUpdate: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPreparing by remember(release.versionTag) { mutableStateOf(false) }
    var awaitingPermission by rememberSaveable(release.versionTag) { mutableStateOf<File?>(null) }
    var errorMessage by rememberSaveable(release.versionTag) { mutableStateOf<Int?>(null) }

    fun reportFailure(error: Exception) {
        val reason = (error as? AppUpdateException)?.reason
        errorMessage = when (reason) {
            AppUpdateFailure.DOWNLOAD -> R.string.update_download_failed
            AppUpdateFailure.INTEGRITY -> R.string.update_integrity_failed
            AppUpdateFailure.PACKAGE -> R.string.update_package_failed
            AppUpdateFailure.VERSION -> R.string.update_version_failed
            AppUpdateFailure.SIGNATURE -> R.string.update_signature_failed
            AppUpdateFailure.INSTALLER -> R.string.update_installer_failed
            null -> R.string.update_prepare_failed
        }
        Log.w("TgWsProxy", "Update failed: ${reason ?: error.javaClass.simpleName}")
    }

    fun openInstaller(apk: File) {
        launchAppUpdateInstaller(context, apk)
        onUpdate()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = awaitingPermission ?: return@rememberLauncherForActivityResult
        awaitingPermission = null
        try {
            if (canInstallAppUpdates(context)) {
                openInstaller(apk)
            } else {
                errorMessage = R.string.update_install_permission_required
            }
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Update,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.update_available_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = release.versionTag,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.update_available_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )

                if (isPreparing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.update_preparing),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                errorMessage?.let { message ->
                    Text(
                        text = stringResource(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.coroutineContext.cancelChildren()
                            onPostpone()
                        },
                        enabled = awaitingPermission == null,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.later),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            isPreparing = true
                            errorMessage = null
                            scope.launch {
                                try {
                                    val apk = prepareAppUpdate(context.applicationContext, release)
                                    if (canInstallAppUpdates(context)) {
                                        openInstaller(apk)
                                    } else {
                                        awaitingPermission = apk
                                        permissionLauncher.launch(appUpdateInstallSettings(context))
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    if (awaitingPermission != null) {
                                        awaitingPermission = null
                                        errorMessage = R.string.update_permission_settings_failed
                                    } else {
                                        reportFailure(error)
                                    }
                                } finally {
                                    isPreparing = false
                                }
                            }
                        },
                        enabled = !isPreparing && awaitingPermission == null,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(22.dp)
                    ) {
                        Text(
                            text = stringResource(
                                if (errorMessage == null) R.string.update_action else R.string.update_retry_action
                            ),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
