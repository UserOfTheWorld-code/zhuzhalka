package com.v2ray.ang.ui.shareapp

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareAppActivity : BaseComponentActivity() {
    private val viewModel: ShareAppViewModel by viewModels()
    private val chooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Returning from Android's chooser does not prove that a recipient received the file.
        viewModel.onAction(AppShareAction.ChooserClosed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    if (!viewModel.isCurrent(effect.requestId)) return@collect
                    try {
                        val send = when (effect) {
                            is AppShareEffect.SendApk -> {
                                val uri = withContext(Dispatchers.IO) {
                                    FileProvider.getUriForFile(this@ShareAppActivity, "$packageName.share", effect.file)
                                }
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/vnd.android.package-archive"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri(getString(R.string.app_name), uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                            }
                            is AppShareEffect.SendLink -> Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, APP_DOWNLOAD_URL)
                            }
                        }
                        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
                        if (viewModel.isCurrent(effect.requestId)) {
                            chooser.launch(Intent.createChooser(send, getString(R.string.buzz_share_app)))
                        }
                    } catch (cancelled: CancellationException) {
                        // Lifecycle cancellation before launch leaves the action available for an explicit retry.
                        viewModel.onAction(AppShareAction.LaunchCancelled(effect.requestId))
                        throw cancelled
                    } catch (error: Exception) {
                        LogUtil.e(AppConfig.TAG, "App sharing: open Android chooser failed", error)
                        viewModel.onAction(AppShareAction.LaunchFailed(effect.requestId))
                    }
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        ShareAppScreen(viewModel, onBackClick = { finish() })
    }
}
