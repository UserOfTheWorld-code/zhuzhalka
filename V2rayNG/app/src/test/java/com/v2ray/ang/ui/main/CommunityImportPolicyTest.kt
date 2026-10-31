package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.PublicSourceImportResult
import com.v2ray.ang.handler.PublicSourceImportStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class CommunityImportPolicyTest {
    private val now = 10_000_000L
    private val subscription = SubscriptionItem(remarks = "Friends", url = "https://provider.com/list?token=approved")
    private val success = PublicSourceImportResult(PublicSourceImportStatus.SUCCESS, "group", 3)

    @Test fun onlyExplicitConsentForACompletedImportCanPublish() {
        assertEquals(subscription.url, offer()?.url)
        assertNotNull(offer(result = success.copy(status = PublicSourceImportStatus.NO_UNIQUE_SERVERS, configCount = 0)))
        assertNull(offer(share = false))
        assertNull(offer(enabled = false))
        assertNull(offer(url = null))
        assertNull(offer(url = ""))
        assertNull(offer(result = success.copy(subscriptionId = null)))
        assertNull(offer(result = success.copy(subscriptionId = "")))
        PublicSourceImportStatus.entries.filter { it != PublicSourceImportStatus.SUCCESS && it != PublicSourceImportStatus.NO_UNIQUE_SERVERS }
            .forEach { assertNull(offer(result = success.copy(status = it))) }
    }

    @Test fun changedOrPrivateSubscriptionCannotReuseFormConsent() {
        assertNull(offer(sub = null))
        assertNull(offer(sub = subscription.copy(url = "https://provider.com/changed")))
        assertNull(offer(sub = subscription.copy(enabled = false)))
        assertNull(offer(sub = subscription.copy(allowInsecureUrl = true)))
        assertNull(offer(sub = subscription.copy(requestHeaders = "Authorization: private")))
        assertNull(offer(sub = subscription.copy(userAgent = "private")))
    }

    @Test fun foregroundRefreshWaitsForReadinessAndOtherWork() {
        val ready = CommunityUiState(ready = true, enabled = true)
        assertFalse(shouldRefreshCommunity(CommunityUiState(), false, false, null, now))
        assertTrue(shouldRefreshCommunity(ready, false, false, null, now))
        assertFalse(shouldRefreshCommunity(ready.copy(enabled = false), false, false, null, now))
        assertFalse(shouldRefreshCommunity(ready.copy(syncing = true), false, false, null, now))
        assertFalse(shouldRefreshCommunity(ready, true, false, null, now))
        assertFalse(shouldRefreshCommunity(ready, false, true, null, now))
        assertFalse(shouldRefreshCommunity(ready, false, false, now - 299_999, now))
        assertTrue(shouldRefreshCommunity(ready.copy(failed = true), false, false, now - 300_000, now))
        assertFalse(shouldRefreshCommunity(ready, false, false, now + 1, now))
    }

    @Test fun optOutOrNewOperationInvalidatesDelayedImportConsent() {
        assertTrue(communityConsentStillCurrent(7, 7, true))
        assertFalse(communityConsentStillCurrent(7, 8, true))
        assertFalse(communityConsentStillCurrent(7, 7, false))
        assertFalse(communityConsentStillCurrent(7, 9, true)) // Off then on needs fresh confirmation.
    }

    @Test fun communityFailureUsesOriginalQueueAndReportsFailure() = runBlocking {
        val original = listOf("saved", "curated")
        val failure = IllegalStateException("broken optional cache")
        val errors = mutableListOf<Exception>()
        assertSame(original, optionalCommunityData(original, errors::add) { throw failure })
        assertEquals(listOf(failure), errors)
        assertEquals(listOf("ranked"), optionalCommunityData(original, errors::add) { listOf("ranked") })
        assertEquals(1, errors.size)
    }

    @Test fun cancellationIsNeverConvertedIntoOptionalCommunityFallback() {
        val errors = mutableListOf<Exception>()
        assertThrows(CancellationException::class.java) {
            runBlocking { optionalCommunityData(false, errors::add) { throw CancellationException("cancelled") } }
        }
        assertTrue(errors.isEmpty())
    }

    private fun offer(
        result: PublicSourceImportResult = success, sub: SubscriptionItem? = subscription,
        url: String? = subscription.url, share: Boolean = true, enabled: Boolean = true,
    ) = communityOfferAfterImport(result, sub, url, share, enabled, "Fallback", now)
}
