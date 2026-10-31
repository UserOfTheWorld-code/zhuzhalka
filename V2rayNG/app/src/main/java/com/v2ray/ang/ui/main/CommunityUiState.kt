package com.v2ray.ang.ui.main

import com.v2ray.ang.handler.CommunityApprovedSource
import com.v2ray.ang.handler.CommunityListOffer
import com.v2ray.ang.handler.CommunitySnapshot
import com.v2ray.ang.handler.CommunityProtocol
import com.v2ray.ang.dto.entities.SubscriptionItem

internal data class CommunityUiState(
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val syncing: Boolean = false,
    val failed: Boolean = false,
    val peerCount: Int = 0,
    val voteCount: Int = 0,
    val offers: List<CommunityListOffer> = emptyList(),
    val ownOfferCount: Int = 0,
    val approvedSources: List<CommunityApprovedSource> = emptyList(),
) {
    val approvedPublicUrls: Set<String> get() = approvedSources.map { it.url }.toSet()
}

internal fun communityUiState(snapshot: CommunitySnapshot, syncing: Boolean = false, failed: Boolean = false) =
    CommunityUiState(true, snapshot.enabled, syncing, failed, snapshot.peerCount,
        snapshot.votes.size, snapshot.offers, snapshot.ownOffers.size, snapshot.approvedSources)

/** Revalidate the exact URL shown in the confirmation, not merely its mutable group ID. */
internal fun communityOfferForConfirmedSubscription(
    subscription: SubscriptionItem?, expectedUrl: String, fallbackName: String, nowMillis: Long,
): CommunityListOffer? {
    if (subscription == null || subscription.url != expectedUrl || !subscription.enabled ||
        subscription.allowInsecureUrl || !subscription.requestHeaders.isNullOrBlank() ||
        !subscription.userAgent.isNullOrBlank()) return null
    return CommunityListOffer(subscription.remarks.take(60).ifBlank { fallbackName }, subscription.url, nowMillis)
        .takeIf { CommunityProtocol.validOffer(it, nowMillis) }
}
