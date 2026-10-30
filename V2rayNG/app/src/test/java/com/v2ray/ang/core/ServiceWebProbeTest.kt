package com.v2ray.ang.core

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ServiceWebProbeTest {
    @Test fun allThreeSitesStartBeforeAnyCompletesAndEachRunsOnce() = runBlocking {
        withTimeout(5_000) {
            val started = mutableListOf<ProbeService>()
            val allStarted = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val result = async {
                probeServicesConcurrently(Long.MAX_VALUE, { 0L }, Semaphore(3)) { service ->
                    started += service
                    if (started.size == 3) allStarted.complete(Unit)
                    finish.await()
                    service.ordinal.toLong()
                }
            }
            // A sequential implementation cannot reach this barrier: its first request
            // cannot finish until all three independent requests have started.
            allStarted.await()
            assertEquals(ProbeService.entries.toSet(), started.toSet())
            assertEquals(3, started.size)
            assertFalse(result.isCompleted)
            finish.complete(Unit)
            assertEquals(ProbeService.entries.associateWith { it.ordinal.toLong() }, result.await().delays)
            assertTrue(result.await().passed)
        }
    }

    @Test fun everySiteMustPassEvenWhenOtherSitesFinishSuccessfully() = runBlocking {
        for (failed in ProbeService.entries) {
            val result = probeServicesConcurrently(Long.MAX_VALUE, { 0L }, Semaphore(3)) {
                if (it == failed) -1L else 20L
            }
            assertEquals(3, result.delays.size)
            assertFalse(result.passed)
        }
    }

    @Test fun expiredBudgetStartsNoNativeMeasurements() = runBlocking {
        val result = probeServicesConcurrently(100L, { 100L }, Semaphore(3)) {
            fail("Expired work must not enter native code")
        }
        assertTrue(result.delays.isEmpty())
        assertFalse(result.passed)
    }

    @Test fun deadlineIsCheckedAgainAfterWaitingForAPermit() = runBlocking {
        withTimeout(5_000) {
            val permits = Semaphore(1)
            permits.acquire()
            var clock = 0L
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                probeServicesConcurrently(100L, { clock }, permits) {
                    fail("Queued work must not start after the deadline")
                }
            }
            yield()
            clock = 100L
            permits.release()
            assertTrue(result.await().delays.isEmpty())
            assertEquals(1, permits.availablePermits)
        }
    }

    @Test fun aSharedLimitBoundsOverlappingBatches() = runBlocking {
        withTimeout(5_000) {
            val permits = Semaphore(3)
            val firstThreeStarted = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            var running = 0
            var maxRunning = 0
            var totalStarted = 0
            val batches = List(2) {
                async {
                    probeServicesConcurrently(Long.MAX_VALUE, { 0L }, permits) {
                        running++
                        totalStarted++
                        maxRunning = maxOf(maxRunning, running)
                        if (totalStarted == 3) firstThreeStarted.complete(Unit)
                        try {
                            finish.await()
                            1L
                        } finally {
                            running--
                        }
                    }
                }
            }
            firstThreeStarted.await()
            yield()
            assertEquals(3, totalStarted)
            assertEquals(3, maxRunning)
            finish.complete(Unit)
            assertTrue(batches.awaitAll().all { it.passed })
            assertEquals(6, totalStarted)
            assertEquals(3, maxRunning)
            assertEquals(3, permits.availablePermits)
        }
    }

    @Test fun cancellationJoinsEveryStartedChildAndReleasesAllPermits() = runBlocking {
        withTimeout(5_000) {
            val permits = Semaphore(3)
            val started = mutableSetOf<ProbeService>()
            val finished = mutableSetOf<ProbeService>()
            val allStarted = CompletableDeferred<Unit>()
            val work = launch {
                probeServicesConcurrently(Long.MAX_VALUE, { 0L }, permits) { service ->
                    started += service
                    if (started.size == 3) allStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        finished += service
                    }
                }
                fail("Cancelled work must not publish a result")
            }
            allStarted.await()
            work.cancelAndJoin()
            assertEquals(ProbeService.entries.toSet(), finished)
            assertEquals(3, permits.availablePermits)
        }
    }

    @Test fun cancellationWhileQueuedNeverEntersTheMeasurement() = runBlocking {
        val permits = Semaphore(1)
        permits.acquire()
        val work = launch(start = CoroutineStart.UNDISPATCHED) {
            probeServicesConcurrently(Long.MAX_VALUE, { 0L }, permits) {
                fail("Cancelled queued work must not enter native code")
            }
        }
        yield()
        work.cancelAndJoin()
        assertEquals(0, permits.availablePermits)
        permits.release()
        assertEquals(1, permits.availablePermits)
    }

    @Test fun nonInterruptibleMeasurementKeepsItsPermitUntilItActuallyFinishes() = runBlocking {
        withTimeout(5_000) {
            val permits = Semaphore(1)
            val started = CompletableDeferred<Unit>()
            val nativeFinished = CompletableDeferred<Unit>()
            var calls = 0
            val work = launch {
                probeServicesConcurrently(Long.MAX_VALUE, { 0L }, permits) {
                    calls++
                    started.complete(Unit)
                    // Model JNI that keeps running after its coroutine has been cancelled.
                    withContext(NonCancellable) { nativeFinished.await() }
                    1L
                }
                fail("Cancelled work must not return a successful result")
            }
            started.await()
            work.cancel()
            yield()
            assertFalse(work.isCompleted)
            assertEquals(0, permits.availablePermits)
            assertEquals(1, calls)
            nativeFinished.complete(Unit)
            work.join()
            assertEquals(1, permits.availablePermits)
            assertEquals(1, calls)
        }
    }

    @Test fun unsupportedOrUnverifiedTlsProfilesRemainIneligible() {
        val profile = ProfileItem(configType = EConfigType.VLESS, insecure = true)
        assertFalse(ServiceWebProbe.canProbeProfile(profile))
        assertTrue(ServiceWebProbe.canProbeProfile(profile.copy(insecure = false)))
        assertTrue(ServiceWebProbe.canProbeProfile(profile.copy(pinnedCA256 = "ab".repeat(32))))
        for (type in listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN)) {
            assertFalse(ServiceWebProbe.canProbeProfile(profile.copy(configType = type, insecure = false)))
        }
    }
}
