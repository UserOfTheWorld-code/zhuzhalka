package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.CommunityApprovedSource
import com.v2ray.ang.handler.CommunityEndpointKey
import com.v2ray.ang.handler.CommunityListOffer
import com.v2ray.ang.handler.CommunitySnapshot
import com.v2ray.ang.handler.CommunityVote
import com.v2ray.ang.handler.PublicSourceCatalog
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.times

class CommunityAutoPickTest {
    private val now = 10_000_000L
    private val key = CommunityEndpointKey("eternity", "a".repeat(64))
    private val votes = listOf("b", "c").map { CommunityVote(key, it.repeat(64), now, true, 500) }

    @Test fun hintsPreserveSelectedPrefixAndAllUnpromotedCandidates() {
        val candidates = listOf("selected", "ordinary", "suggested", "last").map { AutoCandidate("g", it) }
        val ranked = rankCommunityCandidates(candidates, mapOf("suggested" to key), votes, now, "selected")
        assertEquals(listOf("selected", "suggested", "ordinary", "last"), ranked.map { it.guid })
    }

    @Test fun unavailableAndStaleCommunityDataKeepExistingOrder() {
        val candidates = listOf(AutoCandidate("g", "first"), AutoCandidate("g", "second"))
        assertEquals(candidates, rankCommunityCandidates(candidates, mapOf("second" to key), emptyList(), now, null))
        assertEquals(candidates, rankCommunityCandidates(candidates, mapOf("second" to key), votes, now + 3_600_001, null))
    }

    @Test fun promotedServerStillNeedsLocalProbeAndFailureContinuesNormalQueue() = runBlocking {
        val ordinary = AutoCandidate("g", "ordinary")
        val hinted = AutoCandidate("g", "hinted")
        val queue = rankCommunityCandidates(listOf(ordinary, hinted), mapOf("hinted" to key), votes, now, null)
        val tried = mutableListOf<String>()
        val found = pickProgressively(emptyList(), { queue }, emptyList(), { _, _ -> error("Unexpected refresh") },
            resolve = { profile(it.guid) }, attempt = { c, _, _, _ -> tried.add(c.guid); c.guid == "ordinary" },
            nowMillis = { now }, canContinue = { true }, deadlineMillis = now + 60_000)
        assertTrue(found)
        assertEquals(listOf("hinted", "ordinary"), tried)
    }

    @Test fun recentLocalSuccessDoesNotLoadOrWaitForCommunityCandidates() = runBlocking {
        val tried = mutableListOf<String>()
        val found = pickProgressively(listOf(AutoCandidate("g", "local")), { error("Must not load") }, emptyList(),
            { _, _ -> error("Must not refresh") }, { profile(it.guid) },
            { c, _, _, _ -> tried.add(c.guid); true }, { now }, { true }, now + 60_000)
        assertTrue(found)
        assertEquals(listOf("local"), tried)
    }

    @Test fun privateDisabledMovedAndAuthenticatedSourcesHaveNoShareableKey() {
        val source: MainDataSource = mock()
        val candidate = AutoCandidate("g", "guid")
        whenever(source.getServerGuidList("g")).thenReturn(listOf("guid"))
        whenever(source.decodeServerConfig("guid")).thenReturn(profile("guid"))
        val subscription = SubscriptionItem(url = PublicSourceCatalog.sources.first { it.id == "eternity" }.subscriptionUrl)
        whenever(source.getSubscriptionItem("g")).thenReturn(subscription)
        val endpoint = communityEndpointKey(source, candidate)
        assertNotNull(endpoint)
        whenever(source.getSubscriptionItem("g")).thenReturn(subscription.copy(url = "https://provider.com/private-token"))
        assertNull(communityEndpointKey(source, candidate))
        whenever(source.getSubscriptionItem("g")).thenReturn(subscription.copy(enabled = false))
        assertNull(communityEndpointKey(source, candidate))
        whenever(source.getSubscriptionItem("g")).thenReturn(subscription.copy(requestHeaders = "Authorization: secret"))
        assertNull(communityEndpointKey(source, candidate))
        whenever(source.getSubscriptionItem("g")).thenReturn(subscription)
        whenever(source.decodeServerConfig("guid")).thenReturn(profile("guid").copy(subscriptionId = "moved"))
        assertNull(communityEndpointKey(source, candidate))
    }

    @Test fun capturedProbeEndpointIsUsedInsteadOfLaterEndpointMutation() {
        val source: MainDataSource = mock()
        val candidate = AutoCandidate("g", "guid")
        val tested = profile("guid")
        whenever(source.getServerGuidList("g")).thenReturn(listOf("guid"))
        whenever(source.decodeServerConfig("guid")).thenReturn(tested)
        whenever(source.getSubscriptionItem("g")).thenReturn(SubscriptionItem(
            url = PublicSourceCatalog.sources.first { it.id == "eternity" }.subscriptionUrl))
        val oldKey = communityEndpointKey(source, candidate)
        whenever(source.decodeServerConfig("guid")).thenReturn(tested.copy(server = "different.org"))
        assertEquals(oldKey, communityEndpointKey(source, candidate, tested))
        assertNotEquals(oldKey, communityEndpointKey(source, candidate))
    }

    @Test fun presentationReflectsInitialDisabledEmptyPopulatedLoadingAndFailure() {
        assertFalse(CommunityUiState().ready)
        assertEquals(CommunityUiState(ready = true), communityUiState(CommunitySnapshot()))
        val offer = CommunityListOffer("Shared", "https://provider.com/list", now)
        val snapshot = CommunitySnapshot(true, votes, listOf(offer), listOf(offer), 2)
        val state = communityUiState(snapshot, syncing = true, failed = true)
        assertTrue(state.ready && state.enabled && state.syncing && state.failed)
        assertEquals(2, state.peerCount)
        assertEquals(2, state.voteCount)
        assertEquals(listOf(offer), state.offers)
        assertEquals(1, state.ownOfferCount)
    }

    @Test fun publicationRequiresExactConfirmedUrlAndSelfContainedEnabledHttpsSubscription() {
        val sub = SubscriptionItem(remarks = "Friends", url = "https://provider.com/list?token=approved")
        assertEquals(sub.url, communityOfferForConfirmedSubscription(sub, sub.url, "Fallback", now)?.url)
        assertNull(communityOfferForConfirmedSubscription(sub.copy(url = "https://provider.com/other-secret"), sub.url, "Fallback", now))
        assertNull(communityOfferForConfirmedSubscription(null, sub.url, "Fallback", now))
        assertNull(communityOfferForConfirmedSubscription(sub.copy(enabled = false), sub.url, "Fallback", now))
        assertNull(communityOfferForConfirmedSubscription(sub.copy(allowInsecureUrl = true), sub.url, "Fallback", now))
        assertNull(communityOfferForConfirmedSubscription(sub.copy(requestHeaders = "Authorization: token"), sub.url, "Fallback", now))
        assertNull(communityOfferForConfirmedSubscription(sub.copy(userAgent = "custom"), sub.url, "Fallback", now))
        assertNull(communityOfferForConfirmedSubscription(sub.copy(url = ""), "", "Fallback", now))
        assertNull(communityOfferForConfirmedSubscription(sub.copy(url = "http://provider.com/list"), "http://provider.com/list", "Fallback", now))
        assertEquals("Fallback", communityOfferForConfirmedSubscription(sub.copy(remarks = ""), sub.url, "Fallback", now)?.name)
    }

    @Test fun rankingSnapshotReadsEachGroupIndexAndProfileOnlyOnceAndRejectsStaleMembership() {
        val source: MainDataSource = mock()
        val subscription = SubscriptionItem(url = PublicSourceCatalog.sources.first { it.id == "eternity" }.subscriptionUrl)
        whenever(source.getSubscriptionItem("g")).thenReturn(subscription)
        whenever(source.getServerGuidList("g")).thenReturn(listOf("one", "two", "moved", "missing"))
        whenever(source.decodeServerConfig("one")).thenReturn(profile("one"))
        whenever(source.decodeServerConfig("two")).thenReturn(profile("two"))
        whenever(source.decodeServerConfig("moved")).thenReturn(profile("moved").copy(subscriptionId = "other"))
        val candidates = listOf("one", "one", "two", "moved", "missing", "unindexed").map { AutoCandidate("g", it) }
        assertEquals(setOf("one", "two"), snapshotCommunityKeys(source, candidates).keys)
        verify(source, times(1)).getSubscriptionItem("g")
        verify(source, times(1)).getServerGuidList("g")
        verify(source, times(1)).decodeServerConfig("one")
        verify(source, times(1)).decodeServerConfig("two")
        verify(source, times(0)).decodeServerConfig("unindexed")
    }

    @Test fun explicitlyChosenPublicCustomListCanHaveKeysButReceivedOffersAloneCannot() {
        val source: MainDataSource = mock()
        val url = "https://catalog.example.net/public.txt"
        val candidate = AutoCandidate("g", "guid")
        whenever(source.getSubscriptionItem("g")).thenReturn(SubscriptionItem(url = url))
        whenever(source.getServerGuidList("g")).thenReturn(listOf("guid"))
        whenever(source.decodeServerConfig("guid")).thenReturn(profile("guid"))
        assertNull(communityEndpointKey(source, candidate))
        assertTrue(snapshotCommunityKeys(source, listOf(candidate)).isEmpty())
        val endpoint = communityEndpointKey(source, candidate, approvedPublicUrls = setOf(url))
        assertNotNull(endpoint)
        assertEquals(endpoint, snapshotCommunityKeys(source, listOf(candidate), setOf(url))["guid"])
        whenever(source.getSubscriptionItem("g")).thenReturn(SubscriptionItem(url = url + "?changed=1"))
        assertNull(communityEndpointKey(source, candidate, approvedPublicUrls = setOf(url)))
    }

    @Test fun presentationCarriesOnlyDurableApprovedSourcesAsRatingPermissions() {
        val url = "https://catalog.example.net/public.txt"
        val received = CommunityListOffer("Public", url, now)
        assertTrue(communityUiState(CommunitySnapshot(enabled = true, offers = listOf(received))).approvedPublicUrls.isEmpty())
        val selected = CommunityApprovedSource("group", "Public", url, now, false)
        val state = communityUiState(CommunitySnapshot(enabled = true, approvedSources = listOf(selected)))
        assertEquals(listOf(selected), state.approvedSources)
        assertEquals(setOf(url), state.approvedPublicUrls)
        assertEquals(0, state.ownOfferCount)
    }

    private fun profile(id: String) = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "g",
        server = "$id.org", serverPort = "443", password = id, security = "tls")
}
