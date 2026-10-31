package com.v2ray.ang.ui.main

import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.handler.CommunityListOffer
import com.v2ray.ang.handler.PublicSourceImportResult
import com.v2ray.ang.handler.PublicSourceImportStatus
import kotlinx.coroutines.CancellationException

/** The add form's consent covers its exact URL and only a completed, verified import. */
internal fun communityOfferAfterImport(
    result: PublicSourceImportResult,
    subscription: SubscriptionItem?,
    confirmedUrl: String?,
    shareRequested: Boolean,
    communityEnabled: Boolean,
    fallbackName: String,
    nowMillis: Long,
): CommunityListOffer? {
    if (!shareRequested || !communityEnabled || confirmedUrl == null || result.subscriptionId.isNullOrBlank() ||
        result.status !in setOf(PublicSourceImportStatus.SUCCESS, PublicSourceImportStatus.NO_UNIQUE_SERVERS)) return null
    return communityOfferForConfirmedSubscription(subscription, confirmedUrl, fallbackName, nowMillis)
}

/** MainActivity asks while started; failed attempts also wait before the next automatic exchange. */
internal fun shouldRefreshCommunity(
    state: CommunityUiState,
    busy: Boolean,
    operationActive: Boolean,
    lastAttemptElapsed: Long?,
    nowElapsed: Long,
): Boolean = state.ready && state.enabled && !state.syncing && !busy && !operationActive &&
    (lastAttemptElapsed == null || nowElapsed - lastAttemptElapsed >= 300_000L)

/** A completed import must never supersede an opt-out requested while its download was running. */
internal fun communityConsentStillCurrent(capturedOperation: Long, currentOperation: Long, enabled: Boolean): Boolean =
    enabled && capturedOperation == currentOperation

/** Community hints are optional; storage/ranking failures must not abort ordinary auto selection. */
internal suspend fun <T> optionalCommunityData(
    fallback: T,
    onFailure: (Exception) -> Unit,
    read: suspend () -> T,
): T = try {
    read()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    onFailure(error)
    fallback
}
