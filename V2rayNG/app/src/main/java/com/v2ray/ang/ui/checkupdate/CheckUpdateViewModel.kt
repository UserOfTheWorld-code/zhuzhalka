package com.v2ray.ang.ui.checkupdate

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.ZhuzhalkaDownloadProgress
import com.v2ray.ang.handler.ZhuzhalkaRelease
import com.v2ray.ang.handler.ZhuzhalkaUpdateException
import com.v2ray.ang.handler.ZhuzhalkaUpdateFailure
import com.v2ray.ang.handler.ZhuzhalkaUpdateManager
import com.v2ray.ang.handler.ZhuzhalkaVerifiedUpdate
import com.v2ray.ang.util.LogUtil
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR, CANCELLED }

data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val release: ZhuzhalkaRelease? = null,
    val progress: ZhuzhalkaDownloadProgress? = null,
    val verified: ZhuzhalkaVerifiedUpdate? = null,
    val failure: ZhuzhalkaUpdateFailure? = null,
    val installerFailed: Boolean = false,
    val permissionRequired: Boolean = false,
    val externalActivityOpen: Boolean = false,
) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
}

sealed interface UpdateAction {
    data object Check : UpdateAction
    data object Download : UpdateAction
    data object Cancel : UpdateAction
    data object Install : UpdateAction
    data object AllowInstall : UpdateAction
    data object RefreshPermission : UpdateAction
    data object InstallerFailed : UpdateAction
    data object InstallerLaunchCancelled : UpdateAction
}

sealed interface UpdateEffect {
    data object OpenInstallPermission : UpdateEffect
    data class OpenInstaller(val file: File) : UpdateEffect
}

internal interface UpdateRepository {
    suspend fun check(): ZhuzhalkaRelease?
    suspend fun download(release: ZhuzhalkaRelease, progress: (ZhuzhalkaDownloadProgress) -> Unit): ZhuzhalkaVerifiedUpdate
    suspend fun verify(release: ZhuzhalkaRelease, file: File): ZhuzhalkaVerifiedUpdate
    suspend fun canInstall(): Boolean
}

private class AndroidUpdateRepository(private val application: Application) : UpdateRepository {
    override suspend fun check() = ZhuzhalkaUpdateManager.checkForUpdate(application)
    override suspend fun download(release: ZhuzhalkaRelease, progress: (ZhuzhalkaDownloadProgress) -> Unit) =
        ZhuzhalkaUpdateManager.downloadAndVerify(application, release, progress)
    override suspend fun verify(release: ZhuzhalkaRelease, file: File) =
        ZhuzhalkaUpdateManager.verifyDownloaded(application, release, file)

    override suspend fun canInstall(): Boolean = withContext(Dispatchers.IO) {
        // Android 8 introduced per-source installation permission; Android 7 uses its system installer setting.
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || application.packageManager.canRequestPackageInstalls()
    }
}

/** Owns one foreground update session. Leaving this activity cancels its work. */
class CheckUpdateViewModel internal constructor(
    application: Application,
    private val repository: UpdateRepository,
    private val scopeOverride: CoroutineScope?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AndroidUpdateRepository(application), null)

    private val scope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()
    private val _effects = Channel<UpdateEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    private var operation: Job? = null
    private val generation = AtomicLong()

    fun initialize() {
        if (_state.value.phase == UpdatePhase.IDLE) onAction(UpdateAction.Check)
    }

    fun onAction(action: UpdateAction) {
        when (action) {
            UpdateAction.Check -> if (!_state.value.busy && !_state.value.externalActivityOpen) perform(UpdatePhase.CHECKING) { token ->
                _state.value = UpdateState(phase = UpdatePhase.CHECKING)
                val release = repository.check()
                publish(token, UpdateState(if (release == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, release))
            }
            UpdateAction.Download -> {
                val release = _state.value.release ?: return
                if (_state.value.busy) return
                perform(UpdatePhase.DOWNLOADING) { token ->
                    val verified = repository.download(release) { progress ->
                        _state.update {
                            if (generation.get() == token && it.phase == UpdatePhase.DOWNLOADING) it.copy(progress = progress) else it
                        }
                    }
                    val permissionRequired = !repository.canInstall()
                    publish(token, UpdateState(UpdatePhase.READY, release, verified = verified, permissionRequired = permissionRequired))
                }
            }
            UpdateAction.Cancel -> {
                if (!_state.value.busy) return
                generation.incrementAndGet()
                operation?.cancel()
                _state.update { it.copy(phase = UpdatePhase.CANCELLED, progress = null) }
            }
            UpdateAction.Install -> {
                val before = _state.value
                val release = before.release ?: return
                val verified = before.verified ?: return
                if (before.busy || before.externalActivityOpen) return
                perform(UpdatePhase.VERIFYING) { token ->
                    // Recheck bytes and signature after returning from Settings, immediately before granting access.
                    val checked = repository.verify(release, verified.file)
                    val allowed = repository.canInstall()
                    publish(token, UpdateState(UpdatePhase.READY, release, verified = checked,
                        permissionRequired = !allowed, externalActivityOpen = allowed))
                    if (allowed && generation.get() == token) _effects.send(UpdateEffect.OpenInstaller(checked.file))
                }
            }
            UpdateAction.AllowInstall -> if (_state.value.phase == UpdatePhase.READY &&
                _state.value.permissionRequired && !_state.value.externalActivityOpen) {
                _state.update { it.copy(externalActivityOpen = true) }
                scope.launch { _effects.send(UpdateEffect.OpenInstallPermission) }
            }
            UpdateAction.RefreshPermission -> if (_state.value.phase == UpdatePhase.READY) {
                val token = generation.get()
                _state.update { it.copy(externalActivityOpen = false) }
                scope.launch {
                    try {
                        val allowed = repository.canInstall()
                        _state.update {
                            if (generation.get() == token && it.phase == UpdatePhase.READY) it.copy(permissionRequired = !allowed) else it
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        LogUtil.e(AppConfig.TAG, "Zhuzhalka updater permission check failed", error)
                        _state.update {
                            if (generation.get() == token && it.phase == UpdatePhase.READY) it.copy(installerFailed = true) else it
                        }
                    }
                }
            }
            UpdateAction.InstallerFailed -> _state.update { it.copy(installerFailed = true, externalActivityOpen = false) }
            UpdateAction.InstallerLaunchCancelled -> _state.update { it.copy(externalActivityOpen = false) }
        }
    }

    private fun publish(token: Long, state: UpdateState) {
        _state.update { if (generation.get() == token) state else it }
    }

    private fun perform(phase: UpdatePhase, block: suspend (Long) -> Unit) {
        val token = generation.incrementAndGet()
        operation?.cancel()
        _state.update { it.copy(phase = phase, failure = null, installerFailed = false, progress = null) }
        operation = scope.launch {
            try {
                block(token)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ZhuzhalkaUpdateException) {
                _state.update {
                    if (generation.get() == token) it.copy(phase = UpdatePhase.ERROR, failure = error.reason, verified = null) else it
                }
            } catch (error: Exception) {
                LogUtil.e(AppConfig.TAG, "Zhuzhalka updater screen operation failed", error)
                _state.update {
                    if (generation.get() == token) it.copy(phase = UpdatePhase.ERROR, failure = ZhuzhalkaUpdateFailure.STORAGE, verified = null) else it
                }
            }
        }
    }
}
