package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ZhuzhalkaAutoPickerTest {
    @Test fun successfulRecentCandidateAvoidsAllCatalogDownloads() = runBlocking {
        val attempts = mutableListOf<String>()
        val found = pickCachedFirst(listOf(candidate("known"), candidate("spare")),
            refresh = { fail("A successful cached server must not download catalogs") },
            canContinue = { true },
            attempt = { item, index, total ->
                assertEquals(1, index)
                assertEquals(2, total)
                attempts += item.guid
                true
            })
        assertTrue(found)
        assertEquals(listOf("known"), attempts)
    }

    @Test fun failedRecentServersFallBackToFreshCandidatesWithoutRepeatingGuids() = runBlocking {
        val events = mutableListOf<String>()
        val found = pickCachedFirst(listOf(candidate("known"), candidate("known"), candidate("spare")),
            refresh = { events += "refresh"; listOf(candidate("known"), candidate("new"), candidate("unused")) },
            canContinue = { true },
            attempt = { item, _, _ -> events += item.guid; item.guid == "new" })
        assertTrue(found)
        assertEquals(listOf("known", "spare", "refresh", "new"), events)
    }

    @Test fun emptyCacheRefreshesAndEmptyCatalogEndsWithoutFalseSuccess() = runBlocking {
        var refreshed = 0
        assertFalse(pickCachedFirst(emptyList(), refresh = { refreshed++; emptyList() }, canContinue = { true },
            attempt = { _, _, _ -> fail("Empty catalogs have no candidate") }))
        assertEquals(1, refreshed)
    }

    @Test fun allCandidatesFailWithoutInventingAWorkingSelection() = runBlocking {
        val attempts = mutableListOf<String>()
        assertFalse(pickCachedFirst(emptyList(), refresh = { listOf(candidate("one"), candidate("two")) },
            canContinue = { true }, attempt = { item, _, _ -> attempts += item.guid; false }))
        assertEquals(listOf("one", "two"), attempts)
    }

    @Test fun exhaustedBudgetOrStartedVpnStopsBeforeRefresh() = runBlocking {
        var available = true
        assertFalse(pickCachedFirst(listOf(candidate("known"), candidate("spare")),
            refresh = { fail("Stopped search must not refresh") }, canContinue = { available },
            attempt = { _, _, _ -> available = false; false }))
    }

    @Test fun cancellationPropagatesAndDoesNotStartFallbackDownload() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                pickCachedFirst(listOf(candidate("known")), refresh = { fail("Cancelled search must not refresh") },
                    canContinue = { true }, attempt = { _, _, _ -> throw CancellationException("test") })
            }
        }
    }

    @Test fun refreshFailurePropagatesInsteadOfReportingNotFoundAsSuccess() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                pickCachedFirst(emptyList(), refresh = { throw IllegalStateException("test") },
                    canContinue = { true }, attempt = { _, _, _ -> true })
            }
        }
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

    @Test fun profileChangedToUnsupportedOrInsecureCannotReuseOldSuccess() {
        val source = source()
        whenever(source.decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.CUSTOM, subscriptionId = "catalog"))
        assertNull(resolveAutoCandidate(source, "known"))
        whenever(source.decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.VLESS,
            subscriptionId = "catalog", insecure = true))
        assertNull(resolveAutoCandidate(source, "known"))
    }

    @Test fun ungroupedProfilesResolveThroughDefaultSubscription() {
        val source = source()
        whenever(source.decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.VLESS))
        whenever(source.getSubscriptionItem(AppConfig.DEFAULT_SUBSCRIPTION_ID)).thenReturn(SubscriptionItem())
        whenever(source.getServerGuidList(AppConfig.DEFAULT_SUBSCRIPTION_ID)).thenReturn(listOf("known"))
        assertEquals(AutoCandidate(AppConfig.DEFAULT_SUBSCRIPTION_ID, "known"), resolveAutoCandidate(source, "known"))
    }

    private fun candidate(guid: String) = AutoCandidate("catalog", guid)

    private fun source(): MainDataSource = mock<MainDataSource>().apply {
        whenever(decodeServerConfig("known")).thenReturn(ProfileItem(configType = EConfigType.VLESS, subscriptionId = "catalog"))
        whenever(getSubscriptionItem("catalog")).thenReturn(SubscriptionItem())
        whenever(getServerGuidList("catalog")).thenReturn(listOf("known"))
    }
}
