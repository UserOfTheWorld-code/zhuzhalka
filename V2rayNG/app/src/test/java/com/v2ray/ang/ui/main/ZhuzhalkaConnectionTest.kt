package com.v2ray.ang.ui.main

import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ZhuzhalkaConnectionTest {
    @Test fun stoppedVpnDoesNotSendAnUnnecessaryStop() = runBlocking {
        assertTrue(awaitAutoPickDisconnect(false, false, MutableStateFlow(false),
            hasVpnRoute = { false }, requestStop = { fail("A stopped VPN needs no command") }))
    }

    @Test fun runningVpnCannotBeInterruptedWithoutTheConfirmedAction() = runBlocking {
        assertFalse(awaitAutoPickDisconnect(true, false, MutableStateFlow(true),
            hasVpnRoute = { false }, requestStop = { fail("Confirmation is required before stopping") }))
    }

    @Test fun sendingStopDoesNotStartSearchBeforeDaemonConfirmsShutdown() = runBlocking {
        val running = MutableStateFlow(true)
        var commands = 0
        val preparation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitAutoPickDisconnect(true, true, running, hasVpnRoute = { false }, requestStop = { commands++ })
        }
        yield()
        assertEquals(1, commands)
        assertFalse(preparation.isCompleted)
        running.value = false
        assertTrue(preparation.await())
    }

    @Test fun shutdownAcknowledgedDuringCommandDispatchIsNotLost() = runBlocking {
        val running = MutableStateFlow(true)
        assertTrue(awaitAutoPickDisconnect(true, true, running, hasVpnRoute = { false }, requestStop = { running.value = false }))
    }

    @Test fun missingShutdownAcknowledgementTimesOutWithoutPretendingVpnStopped() = runBlocking {
        val running = MutableStateFlow(true)
        assertFalse(awaitAutoPickDisconnect(true, true, running, hasVpnRoute = { false }, requestStop = {}, timeoutMillis = 25L))
        assertTrue(running.value)
    }

    @Test fun cancellingShutdownWaitDoesNotContinueSearchOrSendMoreCommands() = runBlocking {
        var commands = 0
        var continued = false
        val preparation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitAutoPickDisconnect(true, true, MutableStateFlow(true), hasVpnRoute = { false }, requestStop = { commands++ })
            continued = true
        }
        preparation.cancelAndJoin()
        assertTrue(preparation.isCancelled)
        assertFalse(continued)
        assertEquals(1, commands)
    }

    @Test fun failedStopDispatchPropagatesWithoutStartingSearch() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                awaitAutoPickDisconnect(true, true, MutableStateFlow(true),
                    hasVpnRoute = { false }, requestStop = { throw IllegalStateException("Dispatch failed") })
            }
        }
    }

    @Test fun earlyDaemonStopEventStillWaitsForAndroidToReleaseVpnRoute() = runBlocking {
        val running = MutableStateFlow(true)
        val routeGone = CompletableDeferred<Boolean>()
        val preparation = async(start = CoroutineStart.UNDISPATCHED) {
            awaitAutoPickDisconnect(true, true, running,
                requestStop = { running.value = false }, hasVpnRoute = { routeGone.await() })
        }
        assertFalse(preparation.isCompleted)
        routeGone.complete(false)
        assertTrue(preparation.await())
    }

    @Test fun lingeringVpnRouteTimesOutEvenAfterDaemonStopEvent() = runBlocking {
        assertFalse(awaitAutoPickDisconnect(true, true, MutableStateFlow(false),
            requestStop = {}, hasVpnRoute = { true }, timeoutMillis = 25L))
    }

    @Test fun recentlyStoppedUiStillChecksForLingeringVpnRoute() = runBlocking {
        assertFalse(awaitAutoPickDisconnect(false, false, MutableStateFlow(false),
            requestStop = { fail("Do not stop another VPN") }, hasVpnRoute = { true }, timeoutMillis = 25L))
    }

    @Test fun disabledCertificateVerificationProducesAnActionableErrorBeforeStart() {
        val source = source(ProfileItem(configType = EConfigType.VLESS, subscriptionId = "group", insecure = true))
        assertEquals(R.string.buzz_profile_insecure, simpleConnectionIssue(source, "server"))
    }

    @Test fun indexedSupportedProfileAndExplicitPinPassTheCompatibilityGate() {
        val profile = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "group")
        assertNull(simpleConnectionIssue(source(profile), "server"))
        assertNull(simpleConnectionIssue(source(profile.copy(insecure = true, pinnedCA256 = "provider-supplied-pin")), "server"))
    }

    @Test fun removedDisabledOrUnindexedProfileCannotStartFromStaleUiSelection() {
        val profile = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "group")
        val removed = source(profile)
        whenever(removed.decodeServerConfig("server")).thenReturn(null)
        assertEquals(R.string.buzz_profile_unsupported, simpleConnectionIssue(removed, "server"))
        assertEquals(R.string.buzz_profile_unsupported, simpleConnectionIssue(source(profile), null))
        val disabled = source(profile)
        whenever(disabled.getSubscriptionItem("group")).thenReturn(SubscriptionItem(enabled = false))
        assertEquals(R.string.buzz_profile_unsupported, simpleConnectionIssue(disabled, "server"))
        val unindexed = source(profile)
        whenever(unindexed.getServerGuidList("group")).thenReturn(emptyList())
        assertEquals(R.string.buzz_profile_unsupported, simpleConnectionIssue(unindexed, "server"))
    }

    @Test fun arbitraryRoutingConfigurationsAreNotPresentedAsCheckedSimpleProfiles() {
        for (type in listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN, EConfigType.HTTP, EConfigType.SOCKS)) {
            val profile = ProfileItem(configType = type, subscriptionId = "group")
            assertEquals(R.string.buzz_profile_unsupported, simpleConnectionIssue(source(profile), "server"))
        }
    }

    private fun source(profile: ProfileItem): MainDataSource = mock<MainDataSource>().also {
        whenever(it.decodeServerConfig("server")).thenReturn(profile)
        whenever(it.getSubscriptionItem("group")).thenReturn(SubscriptionItem(enabled = true))
        whenever(it.getServerGuidList("group")).thenReturn(listOf("server"))
    }

    @Test fun aReusedGuidCannotPublishResultsForChangedConnectionSettings() {
        val tested = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "group", server = "old.example")
        assertNull(resolveTestedAutoCandidate(source(tested.copy(server = "new.example")), "server", tested))
        assertNull(resolveTestedAutoCandidate(source(tested.copy(insecure = true)), "server", tested))
        val removed = source(tested)
        whenever(removed.decodeServerConfig("server")).thenReturn(null)
        assertNull(resolveTestedAutoCandidate(removed, "server", tested))
    }

    @Test fun aLabelChangeDoesNotInvalidateTheSameTestedConnection() {
        val tested = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "group", server = "same.example")
        val renamed = tested.copy(remarks = "Renamed", addedTime = 123L)
        assertEquals(AutoCandidate("group", "server"), resolveTestedAutoCandidate(source(renamed), "server", tested))
    }
}
