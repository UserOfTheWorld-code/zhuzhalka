package com.v2ray.ang.ui.shareapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.AppTopBar

@Composable
fun ShareAppScreen(viewModel: ShareAppViewModel, onBackClick: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShareAppContent(state, viewModel::onAction, onBackClick)
}

@Composable
internal fun ShareAppContent(state: AppShareState, onAction: (AppShareAction) -> Unit, onBackClick: () -> Unit) {
    Scaffold(topBar = {
        AppTopBar(title = stringResource(R.string.buzz_share_app), onBackClick = onBackClick)
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.buzz_share_app_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.buzz_share_app_qr), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            if (state.qrLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.qrBitmap?.let { bitmap ->
                Image(bitmap.asImageBitmap(), stringResource(R.string.buzz_share_app_qr_description),
                    Modifier.align(Alignment.CenterHorizontally).widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
            }
            if (state.qrFailed) {
                Text(stringResource(R.string.buzz_share_app_qr_failed),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { onAction(AppShareAction.RetryQr) }) {
                    Text(stringResource(R.string.buzz_share_app_retry))
                }
            }
            Text(stringResource(R.string.buzz_share_app_qr_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { onAction(AppShareAction.ShareApk) },
                enabled = !state.preparingApk && !state.externalActivityOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.buzz_share_app_apk))
            }
            Text(stringResource(R.string.buzz_share_app_apk_hint), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.buzz_share_app_compat), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.preparingApk) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.buzz_share_app_busy),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                TextButton(onClick = { onAction(AppShareAction.CancelPreparation) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
            state.failure?.let { failure ->
                val message = when (failure) {
                    AppShareFailure.PREPARATION -> R.string.buzz_share_app_failed
                    AppShareFailure.SPLIT_INSTALLATION -> R.string.buzz_share_app_split
                    AppShareFailure.SHARE_UNAVAILABLE -> R.string.buzz_share_app_no_handler
                }
                Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            OutlinedButton(onClick = { onAction(AppShareAction.ShareLink) },
                enabled = !state.preparingApk && !state.externalActivityOpen,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.buzz_share_app_link))
            }
        }
    }
}
