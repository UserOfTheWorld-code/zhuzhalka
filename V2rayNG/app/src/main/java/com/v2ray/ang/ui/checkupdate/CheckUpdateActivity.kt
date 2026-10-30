package com.v2ray.ang.ui.checkupdate

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CheckUpdateActivity : BaseComponentActivity() {
    private val viewModel: CheckUpdateViewModel by viewModels()
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onAction(UpdateAction.RefreshPermission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    try {
                        when (effect) {
                            UpdateEffect.OpenInstallPermission -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    installer.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                                }
                            }
                            is UpdateEffect.OpenInstaller -> {
                                val uri = withContext(Dispatchers.IO) {
                                    FileProvider.getUriForFile(this@CheckUpdateActivity, "$packageName.updates", effect.file)
                                }
                                installer.launch(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/vnd.android.package-archive")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    clipData = ClipData.newRawUri("APK", uri)
                                })
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        // Rotation/backgrounding can cancel URI preparation before launch. No result callback
                        // will arrive in that case, so make the verified update available for an explicit retry.
                        viewModel.onAction(UpdateAction.InstallerLaunchCancelled)
                        throw cancelled
                    } catch (error: Exception) {
                        LogUtil.e(AppConfig.TAG, "Zhuzhalka updater installer launch failed", error)
                        viewModel.onAction(UpdateAction.InstallerFailed)
                    }
                }
            }
        }
    }

    @Composable
    override fun ScreenContent() {
        CheckUpdateScreen(viewModel, onBackClick = { finish() })
    }
}

/** A separate authority exposes only verified update files, never profile exports or other cache files. */
class ZhuzhalkaUpdateProvider : FileProvider()
