package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.ServiceWebProbe
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reject deleted, disabled, unindexed or unsupported profiles each time their GUID is used. */
internal fun resolveAutoCandidate(dataSource: MainDataSource, guid: String): AutoCandidate? {
    val profile = dataSource.decodeServerConfig(guid) ?: return null
    if (!ServiceWebProbe.canProbeProfile(profile)) return null
    val group = profile.subscriptionId.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID }
    if (dataSource.getSubscriptionItem(group)?.enabled != true || guid !in dataSource.getServerGuidList(group)) return null
    return AutoCandidate(group, guid)
}

/** The refresh callback is deliberately lazy: a rechecked recent success needs no catalog download. */
internal suspend fun pickCachedFirst(
    recent: List<AutoCandidate>,
    refresh: suspend () -> List<AutoCandidate>,
    canContinue: () -> Boolean,
    attempt: suspend (AutoCandidate, Int, Int) -> Boolean,
): Boolean {
    val attempted = mutableSetOf<String>()
    suspend fun tryCandidates(candidates: List<AutoCandidate>): Boolean {
        val unique = candidates.distinctBy { it.guid }.filterNot { it.guid in attempted }
        for ((index, candidate) in unique.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!canContinue()) return false
            attempted.add(candidate.guid)
            if (attempt(candidate, index + 1, unique.size)) return true
        }
        return false
    }
    if (tryCandidates(recent)) return true
    currentCoroutineContext().ensureActive()
    if (!canContinue()) return false
    return tryCandidates(refresh())
}
