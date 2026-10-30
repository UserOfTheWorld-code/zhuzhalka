package com.v2ray.ang.ui.shareapp

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.AndroidAppShareRepository
import com.v2ray.ang.handler.AppShareRepository
import com.v2ray.ang.handler.SplitApkSharingUnsupportedException
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.QRCodeDecoder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val APP_DOWNLOAD_URL = "https://github.com/UserOfTheWorld-code/zhuzhalka/releases"

enum class AppShareFailure { PREPARATION, SPLIT_INSTALLATION, SHARE_UNAVAILABLE }

data class AppShareState(
    val qrBitmap: Bitmap? = null,
    val qrLoading: Boolean = true,
    val qrFailed: Boolean = false,
    val preparingApk: Boolean = false,
    val externalActivityOpen: Boolean = false,
    val failure: AppShareFailure? = null,
)

sealed interface AppShareAction {
    data object ShareApk : AppShareAction
    data object ShareLink : AppShareAction
    data object CancelPreparation : AppShareAction
    data object RetryQr : AppShareAction
    data object ChooserClosed : AppShareAction
    data class LaunchFailed(val requestId: Long) : AppShareAction
    data class LaunchCancelled(val requestId: Long) : AppShareAction
}

sealed interface AppShareEffect {
    val requestId: Long
    data class SendApk(override val requestId: Long, val file: File) : AppShareEffect
    data class SendLink(override val requestId: Long) : AppShareEffect
}

/** Owns an app-sharing session; it never reads or exports the user's VPN configuration. */
class ShareAppViewModel internal constructor(
    application: Application,
    private val repository: AppShareRepository,
    private val createQr: suspend () -> Bitmap?,
    private val scopeOverride: CoroutineScope?,
    private val reportError: (String, Throwable) -> Unit,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        AndroidAppShareRepository(application),
        { withContext(Dispatchers.Default) { QRCodeDecoder.createQRCode(APP_DOWNLOAD_URL) } },
        null,
        { operation, error -> LogUtil.e(AppConfig.TAG, "App sharing: $operation", error) },
    )

    private val scope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(AppShareState())
    val state = _state.asStateFlow()
    private val _effects = Channel<AppShareEffect>(Channel.BUFFERED, onUndeliveredElement = { effect ->
        // A stopped collector can lose a received event before its body starts. Allow an explicit retry.
        scope.launch { onAction(AppShareAction.LaunchCancelled(effect.requestId)) }
    })
    val effects = _effects.receiveAsFlow()
    private var qrStarted = false
    private var preparation: Job? = null
    private var requestId = 0L

    fun initialize() {
        if (!qrStarted) loadQr()
    }

    fun onAction(action: AppShareAction) {
        when (action) {
            AppShareAction.ShareApk -> prepareApk()
            AppShareAction.ShareLink -> {
                if (_state.value.preparingApk || _state.value.externalActivityOpen) return
                val token = ++requestId
                _state.update { it.copy(externalActivityOpen = true, failure = null) }
                scope.launch { _effects.send(AppShareEffect.SendLink(token)) }
            }
            AppShareAction.CancelPreparation -> {
                if (!_state.value.preparingApk) return
                requestId++
                preparation?.cancel()
                _state.update { it.copy(preparingApk = false, failure = null) }
            }
            AppShareAction.RetryQr -> if (!_state.value.qrLoading) loadQr()
            AppShareAction.ChooserClosed -> _state.update { it.copy(externalActivityOpen = false) }
            is AppShareAction.LaunchFailed -> if (isCurrent(action.requestId)) {
                _state.update { it.copy(externalActivityOpen = false, failure = AppShareFailure.SHARE_UNAVAILABLE) }
            }
            is AppShareAction.LaunchCancelled -> if (isCurrent(action.requestId)) {
                _state.update { it.copy(externalActivityOpen = false) }
            }
        }
    }

    fun isCurrent(token: Long): Boolean = token == requestId && _state.value.externalActivityOpen

    private fun loadQr() {
        qrStarted = true
        _state.update { it.copy(qrLoading = true, qrFailed = false) }
        scope.launch {
            try {
                val bitmap = checkNotNull(createQr()) { "QR creation returned no bitmap" }
                _state.update { it.copy(qrBitmap = bitmap, qrLoading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError("create download QR failed", error)
                _state.update { it.copy(qrLoading = false, qrFailed = true) }
            }
        }
    }

    private fun prepareApk() {
        if (_state.value.preparingApk || _state.value.externalActivityOpen) return
        val token = ++requestId
        _state.update { it.copy(preparingApk = true, failure = null) }
        preparation = scope.launch {
            try {
                val file = repository.prepareApk()
                currentCoroutineContext().ensureActive()
                if (requestId != token) return@launch
                _state.update { it.copy(preparingApk = false, externalActivityOpen = true) }
                _effects.send(AppShareEffect.SendApk(token, file))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportError("prepare installed APK failed", error)
                val failure = if (error is SplitApkSharingUnsupportedException)
                    AppShareFailure.SPLIT_INSTALLATION else AppShareFailure.PREPARATION
                _state.update { if (requestId == token) it.copy(preparingApk = false, failure = failure) else it }
            }
        }
    }
}
