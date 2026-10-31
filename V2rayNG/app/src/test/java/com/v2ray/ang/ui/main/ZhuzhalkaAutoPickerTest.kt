package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ZhuzhalkaAutoPickerTest {
    @Test fun subscriptionChainsAreExcludedFromBothResolutionAndSnapshots() {
        val source = source()
        for (subscription in listOf(SubscriptionItem(prevProfile = "before"), SubscriptionItem(nextProfile = "after"))) {
            whenever(source.getSubscriptionItem("catalog")).thenReturn(subscription)
            whenever(source.getSubscriptions()).thenReturn(listOf(SubscriptionCache("catalog", subscription)))
            assertNull(resolveAutoCandidate(source, "known"))
            assertTrue(loadEligibleAutoCandidates(source).isEmpty())
        }
    }

    @Test fun strictAndLegacyFlagsForSameEndpointAreNotProbedTwice() = runBlocking {
        val scenario = Scenario()
        val strict = scenario.add("strict")
        val legacy = scenario.add("legacy")
        scenario.profiles[legacy.guid] = scenario.profiles.getValue(strict.guid).copy(insecure = true)
        scenario.saved = listOf(strict, legacy)
        assertFalse(scenario.run())
        assertEquals(listOf("saved", "probe:strict"), scenario.events)
    }

    @Test fun successfulRecentCandidateAvoidsAllCatalogDownloads() = runBlocking {
        val scenario = Scenario()
        scenario.recent = listOf(scenario.add("known"), scenario.add("spare"))
        scenario.success = "known"
        assertTrue(scenario.run())
        assertEquals(listOf("probe:known"), scenario.events)
        assertEquals(listOf(1 to 2), scenario.progress)
        assertEquals(listOf(scenario.deadline), scenario.attemptDeadlines)
    }

    @Test fun savedCandidateSucceedsBeforeAnyDownload() = runBlocking {
        val scenario = Scenario()
        scenario.recent = listOf(scenario.add("known"))
        scenario.saved = listOf(scenario.add("saved"))
        scenario.sources["unused"] = listOf(scenario.add("new"))
        scenario.success = "saved"
        assertTrue(scenario.run())
        assertEquals(listOf("probe:known", "saved", "probe:saved"), scenario.events)
    }

    @Test fun eachSourceIsProbedBeforeNextDownloadAndSuccessStopsFurtherDownloads() = runBlocking {
        val scenario = Scenario()
        scenario.sources["one"] = listOf(scenario.add("bad"))
        scenario.sources["two"] = listOf(scenario.add("good"))
        scenario.sources["unused"] = listOf(scenario.add("unused"))
        scenario.success = "good"
        assertTrue(scenario.run())
        assertEquals(listOf("saved", "refresh:one", "probe:bad", "refresh:two", "probe:good"), scenario.events)
    }

    @Test fun renamedConfigurationWithNewGuidIsNotRetestedAcrossPhases() = runBlocking {
        val scenario = Scenario()
        val old = scenario.add("old")
        val duplicate = scenario.add("renamed", "other")
        scenario.profiles[duplicate.guid] = scenario.profiles.getValue(old.guid).copy(
            subscriptionId = "other", remarks = "New label", addedTime = 999L, description = "Different metadata")
        scenario.recent = listOf(old)
        scenario.saved = listOf(duplicate)
        scenario.sources["one"] = listOf(duplicate, scenario.add("good"))
        scenario.success = "good"
        assertTrue(scenario.run())
        assertEquals(listOf("probe:old", "saved", "refresh:one", "probe:good"), scenario.events)
    }

    @Test fun differentAuthenticationSniAndTransportRemainDifferentCandidates() = runBlocking {
        val scenario = Scenario()
        val base = scenario.add("base")
        val password = scenario.add("password")
        val sni = scenario.add("sni")
        val transport = scenario.add("transport")
        scenario.profiles[sni.guid] = scenario.profiles.getValue(base.guid).copy(sni = "other.example")
        scenario.profiles[transport.guid] = scenario.profiles.getValue(base.guid).copy(network = "ws")
        scenario.saved = listOf(base, password, sni, transport)
        assertFalse(scenario.run())
        assertEquals(listOf("saved", "probe:base", "probe:password", "probe:sni", "probe:transport"), scenario.events)
    }

    @Test fun changedConnectionUnderSameGuidCanBeRetestedAfterRefresh() = runBlocking {
        val scenario = Scenario()
        val candidate = scenario.add("same")
        scenario.recent = listOf(candidate)
        scenario.sources["one"] = listOf(candidate)
        scenario.onRefresh = {
            scenario.profiles[candidate.guid] = scenario.profiles.getValue(candidate.guid).copy(password = "new-auth")
            scenario.success = candidate.guid
            listOf(candidate)
        }
        assertTrue(scenario.run())
        assertEquals(listOf("probe:same", "saved", "refresh:one", "probe:same"), scenario.events)
    }

    @Test fun missingUnsupportedAndMovedProfilesSkipWhileLegacyFlagReceivesStrictRetry() = runBlocking {
        val scenario = Scenario()
        val missing = scenario.add("missing")
        scenario.profiles.remove(missing.guid)
        val unsupported = scenario.add("custom")
        scenario.profiles[unsupported.guid] = scenario.profiles.getValue(unsupported.guid).copy(configType = EConfigType.CUSTOM)
        val insecure = scenario.add("insecure")
        scenario.profiles[insecure.guid] = scenario.profiles.getValue(insecure.guid).copy(insecure = true)
        val moved = scenario.add("moved")
        scenario.profiles[moved.guid] = scenario.profiles.getValue(moved.guid).copy(subscriptionId = "different-group")
        scenario.recent = listOf(missing, unsupported, insecure, moved)
        scenario.saved = listOf(scenario.add("good"))
        scenario.success = "good"
        assertTrue(scenario.run())
        assertEquals(listOf("probe:insecure", "saved", "probe:good"), scenario.events)
    }

    @Test fun emptySourcesAndAllFailedCandidatesNeverInventSuccess() = runBlocking {
        val scenario = Scenario()
        scenario.sources["empty"] = emptyList()
        scenario.sources["failed"] = listOf(scenario.add("one"), scenario.add("two"))
        assertFalse(scenario.run())
        assertEquals(listOf("saved", "refresh:empty", "refresh:failed", "probe:one", "probe:two"), scenario.events)
    }

    @Test fun recentAndSavedAttemptLimitsLeaveTimeForFreshSource() = runBlocking {
        val scenario = Scenario()
        scenario.recent = listOf(scenario.add("recent1"), scenario.add("recent2"))
        scenario.saved = listOf(scenario.add("saved1"), scenario.add("saved2"))
        scenario.sources["one"] = listOf(scenario.add("fresh"))
        scenario.success = "fresh"
        assertTrue(scenario.run(AutoPickLimits(recentAttempts = 1, savedAttempts = 1)))
        assertEquals(listOf("probe:recent1", "saved", "probe:saved1", "refresh:one", "probe:fresh"), scenario.events)
    }

    @Test fun elapsedPhaseBudgetsAdvanceEvenWhenCandidateCountLimitRemains() = runBlocking {
        val scenario = Scenario()
        scenario.recent = listOf(scenario.add("recent1"), scenario.add("recent2"))
        scenario.saved = listOf(scenario.add("saved1"), scenario.add("saved2"))
        scenario.sources["one"] = listOf(scenario.add("fresh"))
        scenario.probeMillis = 21_000L
        scenario.success = "fresh"
        assertTrue(scenario.run())
        assertEquals(listOf("probe:recent1", "saved", "probe:saved1", "refresh:one", "probe:fresh"), scenario.events)
    }

    @Test fun slowResolutionStillAllowsFirstProbeWithinTheWholeDeadline() = runBlocking {
        val scenario = Scenario()
        scenario.recent = listOf(scenario.add("known"))
        scenario.success = "known"
        scenario.onResolve = { item -> scenario.clock += 25_000L; scenario.profiles[item.guid] }
        assertTrue(scenario.run())
        assertEquals(listOf(scenario.deadline), scenario.attemptDeadlines)
    }

    @Test fun candidateAdmittedNearPhaseEndCanStillCheckAllServicesAfterConfigSetup() = runBlocking {
        val scenario = Scenario()
        scenario.sources["one"] = listOf(scenario.add("failed"), scenario.add("good"))
        scenario.sources["unused"] = listOf(scenario.add("unused"))
        val checkedServices = mutableListOf<Int>()
        scenario.onAttempt = { candidate ->
            if (candidate.guid == "failed") {
                scenario.clock += 9_999L
                false
            } else {
                // Configuration setup crosses the source phase boundary before service admission.
                scenario.clock += 2L
                repeat(3) { service ->
                    if (scenario.clock < scenario.attemptDeadlines.last()) checkedServices += service
                    scenario.clock++
                }
                checkedServices.size == 3
            }
        }
        assertTrue(scenario.run())
        assertEquals(listOf(0, 1, 2), checkedServices)
        assertEquals(listOf("saved", "refresh:one", "probe:failed", "probe:good"), scenario.events)
    }

    @Test fun slowFreshBatchDoesNotConsumeEveryLaterSourceOpportunity() = runBlocking {
        val scenario = Scenario()
        scenario.sources["one"] = listOf(scenario.add("bad1"), scenario.add("bad2"))
        scenario.sources["two"] = listOf(scenario.add("good"))
        scenario.probeMillis = 11_000L
        scenario.success = "good"
        assertTrue(scenario.run())
        assertEquals(listOf("saved", "refresh:one", "probe:bad1", "refresh:two", "probe:good"), scenario.events)
    }

    @Test fun remainingCandidatesAreInterleavedAfterEachSourceHadAnOpportunity() = runBlocking {
        val scenario = Scenario()
        scenario.saved = listOf(scenario.add("a1", "a"), scenario.add("b1", "b"),
            scenario.add("a2", "a"), scenario.add("b2", "b"))
        scenario.sources["first"] = listOf(scenario.add("a3", "a"), scenario.add("a4", "a"))
        scenario.sources["second"] = listOf(scenario.add("b3", "b"), scenario.add("b4", "b"))
        scenario.success = "a4"
        assertTrue(scenario.run(AutoPickLimits(savedAttempts = 1, sourceAttempts = 1)))
        assertEquals(listOf("saved", "probe:a1", "refresh:first", "probe:a3", "refresh:second", "probe:b3",
            "probe:b1", "probe:a2", "probe:b2", "probe:a4"), scenario.events)
    }

    @Test fun downloadTimeConsumesWholeBudgetAndPreventsProbeAndNextDownload() = runBlocking {
        val scenario = Scenario()
        scenario.deadline = 100L
        scenario.downloadMillis = 100L
        scenario.sources["one"] = listOf(scenario.add("good"))
        scenario.sources["two"] = listOf(scenario.add("unused"))
        scenario.success = "good"
        assertFalse(scenario.run())
        assertEquals(listOf("saved", "refresh:one"), scenario.events)
        assertEquals(listOf(100L), scenario.refreshDeadlines)
    }

    @Test fun alreadyExpiredWholeDeadlineDoesNoStorageOrNetworkWork() = runBlocking {
        val scenario = Scenario()
        scenario.deadline = 0L
        scenario.recent = listOf(scenario.add("known"))
        scenario.sources["one"] = listOf(scenario.add("unused"))
        assertFalse(scenario.run())
        assertTrue(scenario.events.isEmpty())
    }

    @Test fun stoppedSearchDoesNotContinueIntoSavedOrRefreshPhases() = runBlocking {
        val scenario = Scenario()
        scenario.recent = listOf(scenario.add("known"), scenario.add("spare"))
        scenario.onAttempt = { scenario.available = false; false }
        assertFalse(scenario.run())
        assertEquals(listOf("probe:known"), scenario.events)
    }

    @Test fun validResultFromAlreadyStartedProbeIsAcceptedAfterBudgetOverrun() = runBlocking {
        val scenario = Scenario()
        scenario.deadline = 10L
        scenario.probeMillis = 20L
        scenario.recent = listOf(scenario.add("known"))
        scenario.success = "known"
        assertTrue(scenario.run())
        assertEquals(listOf(10L), scenario.attemptDeadlines)
        assertEquals(listOf("probe:known"), scenario.events)
    }

    @Test fun cancellationPropagatesWithoutStartingLaterWork() {
        val scenario = Scenario()
        scenario.recent = listOf(scenario.add("known"))
        scenario.onAttempt = { throw CancellationException("test") }
        assertThrows(CancellationException::class.java) { runBlocking { scenario.run() } }
        assertEquals(listOf("probe:known"), scenario.events)
    }

    @Test fun cancelledDownloadDoesNotStartItsProbeOrNextSource() {
        val scenario = Scenario()
        scenario.sources["one"] = listOf(scenario.add("one"))
        scenario.sources["two"] = listOf(scenario.add("two"))
        scenario.onRefresh = { throw CancellationException("test") }
        assertThrows(CancellationException::class.java) { runBlocking { scenario.run() } }
        assertEquals(listOf("saved", "refresh:one"), scenario.events)
    }

    @Test fun unexpectedStorageFailurePropagatesInsteadOfReportingSuccessOrNoServer() {
        val scenario = Scenario()
        scenario.sources["one"] = emptyList()
        scenario.onRefresh = { throw IllegalStateException("test") }
        assertThrows(IllegalStateException::class.java) { runBlocking { scenario.run() } }
    }

    @Test fun storedGuidMustStillResolveToAnEnabledIndexedSupportedProfile() {
        val source = source()
        assertEquals(candidate("known"), resolveAutoCandidate(source, "known"))
        whenever(source.decodeServerConfig("known")).thenReturn(null)
        assertNull(resolveAutoCandidate(source, "known"))
    }

    @Test fun removedFromIndexOrDisabledGroupCannotBeReusedFromCache() {
        val source = source()
        whenever(source.getServerGuidList("catalog")).thenReturn(emptyList())
        assertNull(resolveAutoCandidate(source, "known"))
        whenever(source.getServerGuidList("catalog")).thenReturn(listOf("known"))
        whenever(source.getSubscriptionItem("catalog")).thenReturn(SubscriptionItem(enabled = false))
        assertNull(resolveAutoCandidate(source, "known"))
        whenever(source.getSubscriptionItem("catalog")).thenReturn(null)
        assertNull(resolveAutoCandidate(source, "known"))
    }

    @Test fun unsupportedProfileIsRejectedButInsecureProfileCanBeStrictlyRetested() {
        val source = source()
        whenever(source.decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.CUSTOM, subscriptionId = "catalog"))
        assertNull(resolveAutoCandidate(source, "known"))
        whenever(source.decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.VLESS,
            subscriptionId = "catalog", insecure = true))
        assertEquals(AutoCandidate("catalog", "known"), resolveAutoCandidate(source, "known"))
    }

    @Test fun ungroupedProfilesResolveThroughDefaultSubscription() {
        val source = source()
        whenever(source.decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.VLESS))
        whenever(source.getSubscriptionItem(AppConfig.DEFAULT_SUBSCRIPTION_ID)).thenReturn(SubscriptionItem())
        whenever(source.getServerGuidList(AppConfig.DEFAULT_SUBSCRIPTION_ID)).thenReturn(listOf("known"))
        assertEquals(AutoCandidate(AppConfig.DEFAULT_SUBSCRIPTION_ID, "known"), resolveAutoCandidate(source, "known"))
        whenever(source.getSubscriptions()).thenReturn(listOf(subscription(AppConfig.DEFAULT_SUBSCRIPTION_ID)))
        assertEquals(listOf(AutoCandidate(AppConfig.DEFAULT_SUBSCRIPTION_ID, "known")), loadEligibleAutoCandidates(source))
    }

    @Test fun snapshotFiltersBeforeInterleavingAndReadsEachEnabledIndexOnce() {
        val source = source()
        whenever(source.getSubscriptions()).thenReturn(listOf(subscription("a"), subscription("b"), subscription("disabled", false)))
        whenever(source.getServerGuidList("a")).thenReturn(listOf("a1", "a2", "custom", "insecure", "moved", "missing"))
        whenever(source.getServerGuidList("b")).thenReturn(listOf("b1", "duplicate"))
        whenever(source.decodeServerConfig("a1")).thenReturn(profile("a1", "a"))
        whenever(source.decodeServerConfig("a2")).thenReturn(profile("a2", "a"))
        whenever(source.decodeServerConfig("b1")).thenReturn(profile("b1", "b"))
        whenever(source.decodeServerConfig("duplicate")).thenReturn(profile("a1", "b").copy(remarks = "renamed"))
        whenever(source.decodeServerConfig("custom")).thenReturn(profile("custom", "a").copy(configType = EConfigType.CUSTOM))
        whenever(source.decodeServerConfig("insecure")).thenReturn(profile("insecure", "a").copy(insecure = true))
        whenever(source.decodeServerConfig("moved")).thenReturn(profile("moved", "elsewhere"))
        assertEquals(listOf(AutoCandidate("b", "b1"), AutoCandidate("a", "a1"), AutoCandidate("a", "a2"), AutoCandidate("a", "insecure")),
            loadEligibleAutoCandidates(source, selectedGuid = "b1"))
        verify(source, times(1)).getServerGuidList("a")
        verify(source, times(1)).getServerGuidList("b")
        verify(source, never()).getServerGuidList("disabled")
        listOf("a1", "a2", "b1", "duplicate", "custom", "insecure", "moved", "missing").forEach {
            verify(source, times(1)).decodeServerConfig(it)
        }
    }

    @Test fun selectedDuplicateIsKeptAndUnrequestedGroupsAreNotRead() {
        val source = source()
        whenever(source.getSubscriptions()).thenReturn(listOf(subscription("a"), subscription("b")))
        whenever(source.getServerGuidList("b")).thenReturn(listOf("first", "selected"))
        whenever(source.decodeServerConfig("first")).thenReturn(profile("same", "b"))
        whenever(source.decodeServerConfig("selected")).thenReturn(profile("same", "b").copy(remarks = "different label"))
        assertEquals(listOf(AutoCandidate("b", "selected")),
            loadEligibleAutoCandidates(source, selectedGuid = "selected", groupIds = setOf("b")))
        verify(source, never()).getServerGuidList("a")
    }

    @Test fun emptyEnabledSnapshotIsEmptyAndDoesNotInventDefaultProfiles() {
        val source = source()
        whenever(source.getSubscriptions()).thenReturn(emptyList())
        assertTrue(loadEligibleAutoCandidates(source).isEmpty())
        verify(source, never()).getServerGuidList(AppConfig.DEFAULT_SUBSCRIPTION_ID)
    }

    private class Scenario {
        var clock = 0L
        var deadline = 120_000L
        var available = true
        var probeMillis = 0L
        var downloadMillis = 0L
        var recent = emptyList<AutoCandidate>()
        var saved = emptyList<AutoCandidate>()
        var success: String? = null
        val sources = linkedMapOf<String, List<AutoCandidate>>()
        val profiles = mutableMapOf<String, ProfileItem>()
        val events = mutableListOf<String>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val attemptDeadlines = mutableListOf<Long>()
        val refreshDeadlines = mutableListOf<Long>()
        var onResolve: (suspend (AutoCandidate) -> ProfileItem?)? = null
        var onAttempt: (suspend (AutoCandidate) -> Boolean)? = null
        var onRefresh: (suspend (String) -> List<AutoCandidate>)? = null

        fun add(guid: String, group: String = "catalog"): AutoCandidate {
            profiles[guid] = profile(guid, group)
            return AutoCandidate(group, guid)
        }

        suspend fun run(limits: AutoPickLimits = AutoPickLimits()) = pickProgressively(
            recent = recent,
            loadSaved = { events += "saved"; saved },
            sourceIds = sources.keys.toList(),
            refreshSource = { source, deadline ->
                events += "refresh:$source"
                refreshDeadlines += deadline
                clock += downloadMillis
                onRefresh?.invoke(source) ?: sources.getValue(source)
            },
            resolve = { item ->
                val resolver = onResolve
                if (resolver == null) profiles[item.guid] else resolver(item)
            },
            attempt = { item, index, total, deadline ->
                events += "probe:${item.guid}"
                progress += index to total
                attemptDeadlines += deadline
                clock += probeMillis
                onAttempt?.invoke(item) ?: (item.guid == success)
            },
            nowMillis = { clock },
            canContinue = { available },
            deadlineMillis = deadline,
            limits = limits,
        )
    }

    private fun subscription(id: String, enabled: Boolean = true) = SubscriptionCache(id, SubscriptionItem(enabled = enabled))

    private fun candidate(guid: String) = AutoCandidate("catalog", guid)

    private fun source(): MainDataSource = mock<MainDataSource>().apply {
        whenever(decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.VLESS, subscriptionId = "catalog"))
        whenever(getSubscriptionItem("catalog")).thenReturn(SubscriptionItem())
        whenever(getServerGuidList("catalog")).thenReturn(listOf("known"))
    }

    companion object {
        private fun profile(key: String, group: String = "catalog") = ProfileItem(
            configType = EConfigType.VLESS, subscriptionId = group,
            server = "proxy.example", serverPort = "443", password = key)
    }
}
