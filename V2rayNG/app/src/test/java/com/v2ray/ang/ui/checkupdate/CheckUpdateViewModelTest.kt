package com.v2ray.ang.ui.checkupdate

import android.app.Application
import com.v2ray.ang.handler.ZhuzhalkaDownloadProgress
import com.v2ray.ang.handler.ZhuzhalkaRelease
import com.v2ray.ang.handler.ZhuzhalkaReleaseAsset
import com.v2ray.ang.handler.ZhuzhalkaUpdateException
import com.v2ray.ang.handler.ZhuzhalkaUpdateFailure
import com.v2ray.ang.handler.ZhuzhalkaVerifiedUpdate
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class CheckUpdateViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = FakeRepository()
    private val model = CheckUpdateViewModel(mock<Application>(), repository, scope)

    @AfterEach
    fun close() { scope.cancel() }

    @Test
    fun initialStateHasNoReleaseOrPermissionRequest() {
        assertEquals(UpdateState(), model.state.value)
        model.onAction(UpdateAction.Download)
        model.onAction(UpdateAction.Install)
        model.onAction(UpdateAction.AllowInstall)
        assertEquals(UpdateState(), model.state.value)
        assertEquals(0, repository.downloads)
    }

    @Test
    fun initializeChecksOnceAndRetainsResultAcrossRecreation() {
        model.initialize()
        model.initialize()
        assertEquals(1, repository.checks)
        assertEquals(UpdatePhase.AVAILABLE, model.state.value.phase)
        assertEquals(release, model.state.value.release)
    }

    @Test
    fun emptyReleaseListShowsCurrentVersion() {
        repository.available = null
        model.initialize()
        assertEquals(UpdatePhase.CURRENT, model.state.value.phase)
        assertNull(model.state.value.release)
    }

    @Test
    fun pendingCheckIsCancellableAndCanBeRetried() {
        repository.checkGate = CompletableDeferred()
        model.initialize()
        assertEquals(UpdatePhase.CHECKING, model.state.value.phase)
        model.onAction(UpdateAction.Check)
        assertEquals(1, repository.checks)
        model.onAction(UpdateAction.Cancel)
        repository.checkGate!!.complete(Unit)
        assertEquals(UpdatePhase.CANCELLED, model.state.value.phase)
        repository.checkGate = null
        model.onAction(UpdateAction.Check)
        assertEquals(UpdatePhase.AVAILABLE, model.state.value.phase)
    }

    @Test
    fun checkFailureCanRecoverAndHasNoStaleInstallFile() {
        repository.failure = ZhuzhalkaUpdateFailure.NETWORK
        model.initialize()
        assertEquals(UpdatePhase.ERROR, model.state.value.phase)
        assertEquals(ZhuzhalkaUpdateFailure.NETWORK, model.state.value.failure)
        assertNull(model.state.value.verified)
        repository.failure = null
        model.onAction(UpdateAction.Check)
        assertEquals(UpdatePhase.AVAILABLE, model.state.value.phase)
        assertNull(model.state.value.failure)
    }

    @Test
    fun downloadPublishesProgressAndCancellationKeepsReleaseForRetry() {
        model.initialize()
        repository.downloadGate = CompletableDeferred()
        model.onAction(UpdateAction.Download)
        assertEquals(UpdatePhase.DOWNLOADING, model.state.value.phase)
        assertEquals(ZhuzhalkaDownloadProgress(25, 100), model.state.value.progress)
        model.onAction(UpdateAction.Download)
        assertEquals(1, repository.downloads)
        model.onAction(UpdateAction.Cancel)
        repository.downloadGate!!.complete(Unit)
        assertEquals(UpdatePhase.CANCELLED, model.state.value.phase)
        assertEquals(release, model.state.value.release)
        assertNull(model.state.value.verified)
        repository.downloadGate = null
        model.onAction(UpdateAction.Download)
        assertEquals(UpdatePhase.READY, model.state.value.phase)
    }

    @Test
    fun deniedPermissionRequiresExplicitSettingsAndInstallActions() = runBlocking {
        repository.permission = false
        model.initialize()
        model.onAction(UpdateAction.Download)
        assertTrue(model.state.value.permissionRequired)
        val effect = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(UpdateAction.AllowInstall)
        assertEquals(UpdateEffect.OpenInstallPermission, effect.await())
        repository.permission = true
        model.onAction(UpdateAction.RefreshPermission)
        assertFalse(model.state.value.permissionRequired)
        assertEquals(0, repository.verifications)
        val install = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(UpdateAction.Install)
        assertEquals(UpdateEffect.OpenInstaller(verified.file), install.await())
        assertEquals(1, repository.verifications)
    }

    @Test
    fun permissionRevokedBeforeInstallDoesNotOpenInstaller() {
        model.initialize()
        model.onAction(UpdateAction.Download)
        repository.permission = false
        model.onAction(UpdateAction.Install)
        assertTrue(model.state.value.permissionRequired)
        assertEquals(UpdatePhase.READY, model.state.value.phase)
        assertEquals(1, repository.verifications)
    }

    @Test
    fun oldProgressCannotOverwriteANewDownload() {
        model.initialize()
        repository.downloadGate = CompletableDeferred()
        model.onAction(UpdateAction.Download)
        val oldProgress = repository.progressCallback!!
        model.onAction(UpdateAction.Cancel)
        repository.downloadGate = CompletableDeferred()
        model.onAction(UpdateAction.Download)
        oldProgress(ZhuzhalkaDownloadProgress(99, 100))
        assertEquals(ZhuzhalkaDownloadProgress(25, 100), model.state.value.progress)
    }

    @Test
    fun repeatedPermissionAndInstallTapsDoNotQueueDuplicateActivities() = runBlocking {
        repository.permission = false
        model.initialize()
        model.onAction(UpdateAction.Download)
        model.onAction(UpdateAction.AllowInstall)
        model.onAction(UpdateAction.AllowInstall)
        assertTrue(model.state.value.externalActivityOpen)
        assertEquals(UpdateEffect.OpenInstallPermission, model.effects.first())
        repository.permission = true
        model.onAction(UpdateAction.RefreshPermission)
        model.onAction(UpdateAction.Install)
        model.onAction(UpdateAction.Install)
        assertEquals(1, repository.verifications)
        assertEquals(UpdateEffect.OpenInstaller(verified.file), model.effects.first())
    }

    @Test
    fun modifiedApkIsRejectedAgainImmediatelyBeforeInstall() {
        model.initialize()
        model.onAction(UpdateAction.Download)
        repository.failure = ZhuzhalkaUpdateFailure.CHECKSUM_MISMATCH
        model.onAction(UpdateAction.Install)
        assertEquals(UpdatePhase.ERROR, model.state.value.phase)
        assertEquals(ZhuzhalkaUpdateFailure.CHECKSUM_MISMATCH, model.state.value.failure)
        assertNull(model.state.value.verified)
    }

    @Test
    fun allDownloadRejectionsBlockInstallerAndRemainRetryable() {
        ZhuzhalkaUpdateFailure.entries.forEach { failure ->
            repository.failure = null
            model.onAction(UpdateAction.Check)
            repository.failure = failure
            model.onAction(UpdateAction.Download)
            assertEquals(UpdatePhase.ERROR, model.state.value.phase)
            assertEquals(failure, model.state.value.failure)
            assertNull(model.state.value.verified)
            model.onAction(UpdateAction.Install)
            assertEquals(0, repository.verifications)
        }
    }

    @Test
    fun installerFailureKeepsVerifiedFileForRetry() = runBlocking {
        model.initialize()
        model.onAction(UpdateAction.Download)
        model.onAction(UpdateAction.InstallerFailed)
        assertTrue(model.state.value.installerFailed)
        assertEquals(verified, model.state.value.verified)
        val effect = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(UpdateAction.Install)
        assertEquals(UpdateEffect.OpenInstaller(verified.file), effect.await())
        assertFalse(model.state.value.installerFailed)
    }

    @Test
    fun lifecycleCancellationBeforeInstallerLaunchRestoresExplicitRetry() = runBlocking {
        model.initialize()
        model.onAction(UpdateAction.Download)
        model.onAction(UpdateAction.Install)
        assertEquals(UpdateEffect.OpenInstaller(verified.file), model.effects.first())
        assertTrue(model.state.value.externalActivityOpen)
        model.onAction(UpdateAction.InstallerLaunchCancelled)
        assertFalse(model.state.value.externalActivityOpen)
        assertFalse(model.state.value.installerFailed)
        assertEquals(verified, model.state.value.verified)
        model.onAction(UpdateAction.Install)
        assertEquals(UpdateEffect.OpenInstaller(verified.file), model.effects.first())
    }

    private class FakeRepository : UpdateRepository {
        var available: ZhuzhalkaRelease? = release
        var permission = true
        var failure: ZhuzhalkaUpdateFailure? = null
        var checkGate: CompletableDeferred<Unit>? = null
        var downloadGate: CompletableDeferred<Unit>? = null
        var checks = 0
        var downloads = 0
        var verifications = 0
        var progressCallback: ((ZhuzhalkaDownloadProgress) -> Unit)? = null
        override suspend fun check(): ZhuzhalkaRelease? {
            checks++
            checkGate?.await()
            failure?.let { throw ZhuzhalkaUpdateException(it) }
            return available
        }
        override suspend fun download(release: ZhuzhalkaRelease, progress: (ZhuzhalkaDownloadProgress) -> Unit): ZhuzhalkaVerifiedUpdate {
            downloads++
            progressCallback = progress
            progress(ZhuzhalkaDownloadProgress(25, 100))
            downloadGate?.await()
            failure?.let { throw ZhuzhalkaUpdateException(it) }
            return verified
        }
        override suspend fun verify(release: ZhuzhalkaRelease, file: File): ZhuzhalkaVerifiedUpdate {
            verifications++
            failure?.let { throw ZhuzhalkaUpdateException(it) }
            return verified
        }
        override suspend fun canInstall() = permission
    }

    private companion object {
        val release = ZhuzhalkaRelease("v0.3.0", "0.3.0", "Notes", false,
            ZhuzhalkaReleaseAsset(1, "Zhuzhalka_0.3.0_universal.apk",
                "https://github.com/UserOfTheWorld-code/zhuzhalka/releases/download/v0.3.0/Zhuzhalka_0.3.0_universal.apk",
                100, "a".repeat(64)), null)
        val verified = ZhuzhalkaVerifiedUpdate(File("update.apk"), "0.3.0", 5_000_300)
    }
}
