package com.v2ray.ang.ui.checkupdate

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.ZhuzhalkaUpdateFailure
import com.v2ray.ang.ui.compose.AppTopBar

@Composable
fun CheckUpdateScreen(viewModel: CheckUpdateViewModel, onBackClick: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    UpdateContent(state, viewModel::onAction, onBackClick)
}

@Composable
internal fun UpdateContent(state: UpdateState, onAction: (UpdateAction) -> Unit, onBackClick: () -> Unit) {
    Scaffold(topBar = {
        AppTopBar(title = stringResource(R.string.buzz_updates), onBackClick = onBackClick, isLoading = state.busy)
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.buzz_update_installed, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.buzz_update_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val status = when (state.phase) {
                UpdatePhase.IDLE, UpdatePhase.CHECKING -> R.string.buzz_update_checking
                UpdatePhase.CURRENT -> R.string.buzz_update_current
                UpdatePhase.AVAILABLE -> R.string.buzz_update_available
                UpdatePhase.DOWNLOADING -> R.string.buzz_update_downloading
                UpdatePhase.VERIFYING -> R.string.buzz_update_verifying
                UpdatePhase.READY -> R.string.buzz_update_ready
                UpdatePhase.ERROR -> failureMessage(state.failure)
                UpdatePhase.CANCELLED -> R.string.buzz_update_cancelled
            }
            Text(stringResource(status), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            state.release?.let { release ->
                Text(stringResource(R.string.buzz_update_version_size, release.versionName,
                    (release.asset.size + 1_048_575) / 1_048_576),
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            }
            if (state.phase == UpdatePhase.DOWNLOADING) {
                val progress = state.progress
                if (progress != null && progress.totalBytes > 0) {
                    val fraction = (progress.bytesDownloaded.toDouble() / progress.totalBytes).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.buzz_update_progress, (fraction * 100).toInt()))
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.busy) {
                TextButton(onClick = { onAction(UpdateAction.Cancel) }) { Text(stringResource(R.string.action_cancel)) }
            } else if (state.phase == UpdatePhase.READY) {
                if (state.permissionRequired) {
                    Text(stringResource(R.string.buzz_update_permission_hint))
                    Button(onClick = { onAction(UpdateAction.AllowInstall) }, enabled = !state.externalActivityOpen,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.buzz_update_allow))
                    }
                } else {
                    Text(stringResource(R.string.buzz_update_install_hint))
                    Button(onClick = { onAction(UpdateAction.Install) }, enabled = !state.externalActivityOpen,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.buzz_update_install))
                    }
                }
            } else if (state.release != null) {
                Button(onClick = { onAction(UpdateAction.Download) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.buzz_update_download))
                }
            }
            if (state.installerFailed) Text(stringResource(R.string.buzz_update_installer_error), color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { onAction(UpdateAction.Check) }, enabled = !state.busy && !state.externalActivityOpen,
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.buzz_update_check))
            }
            state.release?.releaseNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(stringResource(R.string.buzz_update_notes), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                // Release notes are untrusted plain text, never HTML or executable links.
                Text(notes, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@StringRes
private fun failureMessage(failure: ZhuzhalkaUpdateFailure?): Int = when (failure) {
    ZhuzhalkaUpdateFailure.NETWORK -> R.string.buzz_update_network_error
    ZhuzhalkaUpdateFailure.NO_COMPATIBLE_ASSET -> R.string.buzz_update_unsupported
    ZhuzhalkaUpdateFailure.SIGNATURE_MISMATCH -> R.string.buzz_update_signature_error
    ZhuzhalkaUpdateFailure.STORAGE -> R.string.buzz_update_storage_error
    ZhuzhalkaUpdateFailure.NOT_NEWER -> R.string.buzz_update_not_newer
    else -> R.string.buzz_update_verification_error
}
