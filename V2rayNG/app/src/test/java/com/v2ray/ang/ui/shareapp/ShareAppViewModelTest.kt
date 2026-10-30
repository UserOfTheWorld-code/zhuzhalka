package com.v2ray.ang.ui.shareapp

import android.app.Application
import android.graphics.Bitmap
import com.v2ray.ang.handler.AppShareRepository
import com.v2ray.ang.handler.SplitApkSharingUnsupportedException
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ShareAppViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = FakeRepository()
    private val bitmap = mock<Bitmap>()
    private var qrResult: Bitmap? = bitmap
    private var qrFailure: Exception? = null
    private var qrCalls = 0
    private var qrGate: CompletableDeferred<Unit>? = null
    private val errors = mutableListOf<String>()
    private val model = ShareAppViewModel(mock<Application>(), repository, {
        qrCalls++
        qrGate?.await()
        qrFailure?.let { throw it }
        qrResult
    }, scope, { operation, _ -> errors += operation })

    @AfterEach
    fun close() { scope.cancel() }

    @Test
    fun initializationOnlyCreatesQrOnceAndDoesNotCopyAnApk() {
        assertEquals(AppShareState(), model.state.value)
        model.initialize()
        model.initialize()
        assertEquals(1, qrCalls)
        assertSame(bitmap, model.state.value.qrBitmap)
        assertFalse(model.state.value.qrLoading)
        assertEquals(0, repository.calls)
        assertFalse(model.state.value.externalActivityOpen)
    }

    @Test
    fun nullQrCanBeRetriedWithoutDuplicatingAnActiveGeneration() {
        qrResult = null
        model.initialize()
        assertTrue(model.state.value.qrFailed)
        assertFalse(model.state.value.qrLoading)
        assertEquals(1, errors.size)
        qrResult = bitmap
        qrGate = CompletableDeferred()
        model.onAction(AppShareAction.RetryQr)
        model.onAction(AppShareAction.RetryQr)
        assertEquals(2, qrCalls)
        assertTrue(model.state.value.qrLoading)
        qrGate!!.complete(Unit)
        assertSame(bitmap, model.state.value.qrBitmap)
        assertFalse(model.state.value.qrFailed)
    }

    @Test
    fun qrFailureDoesNotPreventSharingTheDownloadLink() = runBlocking {
        qrFailure = IllegalStateException("QR unavailable")
        model.initialize()
        assertTrue(model.state.value.qrFailed)
        val next = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(AppShareAction.ShareLink)
        assertInstanceOf(AppShareEffect.SendLink::class.java, next.await())
        assertEquals(0, repository.calls)
    }

    @Test
    fun apkIsOnlyExposedAfterPreparationAndRepeatedClicksAreIgnored() = runBlocking {
        repository.gate = CompletableDeferred()
        val next = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(AppShareAction.ShareApk)
        model.onAction(AppShareAction.ShareApk)
        model.onAction(AppShareAction.ShareLink)
        assertEquals(1, repository.calls)
        assertTrue(model.state.value.preparingApk)
        assertFalse(model.state.value.externalActivityOpen)
        assertFalse(next.isCompleted)
        repository.gate!!.complete(Unit)
        val effect = next.await() as AppShareEffect.SendApk
        assertEquals(repository.file, effect.file)
        assertTrue(model.isCurrent(effect.requestId))
        assertFalse(model.state.value.preparingApk)
        model.onAction(AppShareAction.ShareApk)
        assertEquals(1, repository.calls)
        model.onAction(AppShareAction.ChooserClosed)
        assertFalse(model.state.value.externalActivityOpen)
    }

    @Test
    fun cancelledPreparationCannotPublishItsFileOrOverwriteANewerRequest() {
        val received = mutableListOf<AppShareEffect>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { model.effects.collect { received += it } }
        val oldGate = CompletableDeferred<Unit>()
        repository.gate = oldGate
        repository.ignoreCancellation = true
        model.onAction(AppShareAction.ShareApk)
        model.onAction(AppShareAction.CancelPreparation)
        assertFalse(model.state.value.preparingApk)
        repository.gate = null
        model.onAction(AppShareAction.ShareApk)
        assertEquals(1, received.size)
        val current = received.single()
        oldGate.complete(Unit)
        assertEquals(listOf(current), received)
        assertTrue(model.isCurrent(current.requestId))
        assertNull(model.state.value.failure)
    }

    @Test
    fun storageFailureClearsBusyStateAndExplicitRetryCanSucceed() = runBlocking {
        repository.failure = IOException("No storage")
        model.onAction(AppShareAction.ShareApk)
        assertEquals(AppShareFailure.PREPARATION, model.state.value.failure)
        assertFalse(model.state.value.preparingApk)
        assertFalse(model.state.value.externalActivityOpen)
        assertEquals(1, errors.size)
        repository.failure = null
        val next = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(AppShareAction.ShareApk)
        assertInstanceOf(AppShareEffect.SendApk::class.java, next.await())
        assertNull(model.state.value.failure)
    }

    @Test
    fun splitInstallationShowsSpecificFailureAndAllowsLinkFallback() = runBlocking {
        repository.failure = SplitApkSharingUnsupportedException()
        model.onAction(AppShareAction.ShareApk)
        assertEquals(AppShareFailure.SPLIT_INSTALLATION, model.state.value.failure)
        assertFalse(model.state.value.externalActivityOpen)
        val next = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(AppShareAction.ShareLink)
        assertInstanceOf(AppShareEffect.SendLink::class.java, next.await())
        assertEquals(1, repository.calls)
    }

    @Test
    fun missingShareHandlerAllowsRetryAndStaleCallbacksCannotUnlockNewChooser() = runBlocking {
        val first = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(AppShareAction.ShareLink)
        val old = first.await()
        model.onAction(AppShareAction.LaunchFailed(old.requestId))
        assertEquals(AppShareFailure.SHARE_UNAVAILABLE, model.state.value.failure)
        assertFalse(model.state.value.externalActivityOpen)
        val second = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(AppShareAction.ShareLink)
        val current = second.await()
        model.onAction(AppShareAction.LaunchCancelled(old.requestId))
        assertTrue(model.isCurrent(current.requestId))
        assertNull(model.state.value.failure)
        model.onAction(AppShareAction.LaunchCancelled(current.requestId))
        assertFalse(model.state.value.externalActivityOpen)
    }

    @Test
    fun returningFromChooserDoesNotClaimDeliveryOrTriggerAnotherExport() = runBlocking {
        val next = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
        model.onAction(AppShareAction.ShareApk)
        next.await()
        model.onAction(AppShareAction.ChooserClosed)
        model.initialize()
        assertEquals(1, repository.calls)
        assertFalse(model.state.value.externalActivityOpen)
        assertFalse(model.state.value.preparingApk)
        assertNull(model.state.value.failure)
    }

    @Test
    fun stoppingCollectorAfterReceiveButBeforeProcessingUnlocksSharingForRetry() = runBlocking {
        val dispatcher = QueuedDispatcher()
        val collectorScope = CoroutineScope(SupervisorJob() + dispatcher)
        var processed = 0
        try {
            val collector = collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                model.effects.collect { processed++ }
            }
            model.onAction(AppShareAction.ShareLink)
            assertTrue(model.state.value.externalActivityOpen)
            collector.cancel()
            dispatcher.runPending()
            assertEquals(0, processed)
            assertFalse(model.state.value.externalActivityOpen)
            val retry = async(start = CoroutineStart.UNDISPATCHED) { model.effects.first() }
            model.onAction(AppShareAction.ShareLink)
            assertTrue(model.isCurrent(retry.await().requestId))
        } finally {
            collectorScope.cancel()
            dispatcher.runPending()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun runPending() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    private class FakeRepository : AppShareRepository {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var ignoreCancellation = false
        val file = File("shared-installed-app.apk")

        override suspend fun prepareApk(): File {
            calls++
            val wait = gate
            val error = failure
            if (ignoreCancellation) withContext(NonCancellable) { wait?.await() } else wait?.await()
            error?.let { throw it }
            return file
        }
    }
}
