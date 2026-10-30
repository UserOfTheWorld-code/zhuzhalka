package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.ServiceWebProbe
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.handler.CommunityEndpointKey
import com.v2ray.ang.handler.CommunityRanking
import com.v2ray.ang.handler.CommunityVote
import com.v2ray.ang.handler.PublicSourceCatalog
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

/** Read each enabled group index once; callers run this storage snapshot off the main thread. */
internal fun loadEligibleAutoCandidates(
    dataSource: MainDataSource,
    selectedGuid: String? = null,
    groupIds: Set<String>? = null,
): List<AutoCandidate> {
    val profiles = mutableMapOf<String, ProfileItem>()
    val groups = dataSource.getSubscriptions()
        .filter { it.guid.isNotBlank() && it.subscription.enabled && (groupIds == null || it.guid in groupIds) }
        .distinctBy { it.guid }
        .map { subscription ->
            subscription.guid to dataSource.getServerGuidList(subscription.guid).distinct().filter { guid ->
                val profile = dataSource.decodeServerConfig(guid)
                val eligible = profile != null && ServiceWebProbe.canProbeProfile(profile) &&
                    profile.subscriptionId.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID } == subscription.guid
                if (eligible) profiles[guid] = profile
                eligible
            }
        }
    val interleaved = interleaveCandidates(groups)
    val selected = interleaved.firstOrNull { it.guid == selectedGuid }
    val prioritized = if (selected == null) interleaved else listOf(selected) + interleaved.filterNot { it.guid == selectedGuid }
    val identities = mutableSetOf<ProfileItem>()
    return prioritized.filter { identities.add(profiles.getValue(it.guid).duplicateIdentity()) }
}

/** Call off-main after the current group/profile snapshot; private subscriptions yield no public key. */
internal fun communityEndpointKey(dataSource: MainDataSource, candidate: AutoCandidate, testedProfile: ProfileItem? = null): CommunityEndpointKey? {
    val subscription = dataSource.getSubscriptionItem(candidate.groupId) ?: return null
    val source = PublicSourceCatalog.sources.firstOrNull { it.subscriptionUrl == subscription.url } ?: return null
    val current = resolveAutoCandidate(dataSource, candidate.guid) ?: return null
    if (current.groupId != candidate.groupId) return null
    val profile = testedProfile ?: dataSource.decodeServerConfig(candidate.guid) ?: return null
    if (profile.subscriptionId.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID } != candidate.groupId) return null
    return CommunityRanking.publicEndpointKey(source.id, subscription, profile)
}

/** Snapshot each group index once. Final attempt resolution still rejects deletions and mutations. */
internal fun snapshotCommunityKeys(dataSource: MainDataSource, candidates: List<AutoCandidate>): Map<String, CommunityEndpointKey> {
    val keys = mutableMapOf<String, CommunityEndpointKey>()
    for ((groupId, groupCandidates) in candidates.groupBy { it.groupId }) {
        val subscription = dataSource.getSubscriptionItem(groupId) ?: continue
        if (!subscription.enabled) continue
        val source = PublicSourceCatalog.sources.firstOrNull { it.subscriptionUrl == subscription.url } ?: continue
        val indexed = dataSource.getServerGuidList(groupId).toHashSet()
        for (candidate in groupCandidates.distinctBy { it.guid }) {
            if (candidate.guid !in indexed) continue
            val profile = dataSource.decodeServerConfig(candidate.guid) ?: continue
            if (profile.subscriptionId.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID } != groupId) continue
            CommunityRanking.publicEndpointKey(source.id, subscription, profile)?.let { keys[candidate.guid] = it }
        }
    }
    return keys
}

internal fun rankCommunityCandidates(
    candidates: List<AutoCandidate>,
    keys: Map<String, CommunityEndpointKey>,
    votes: List<CommunityVote>,
    nowMillis: Long,
    selectedGuid: String?,
): List<AutoCandidate> = CommunityRanking.promote(candidates, votes, nowMillis,
    keyOf = { keys[it.guid] }, protectedPrefixCount = if (candidates.firstOrNull()?.guid == selectedGuid) 1 else 0)

internal data class AutoPickLimits(
    val recentAttempts: Int = 5,
    val recentMillis: Long = 20_000L,
    val savedAttempts: Int = 12,
    val savedMillis: Long = 20_000L,
    val sourceAttempts: Int = 8,
    val sourceMillis: Long = 10_000L,
) {
    init {
        require(recentAttempts > 0 && savedAttempts > 0 && sourceAttempts > 0)
        require(recentMillis > 0 && savedMillis > 0 && sourceMillis > 0)
    }
}

/**
 * Try available profiles before downloading, then probe each source before fetching the next one.
 * Deadlines prevent new work; a blocking native call already in progress may finish after its deadline.
 * Connection identities are held only in this search's memory and are never persisted or logged.
 */
internal suspend fun pickProgressively(
    recent: List<AutoCandidate>,
    loadSaved: suspend () -> List<AutoCandidate>,
    sourceIds: List<String>,
    refreshSource: suspend (String, Long) -> List<AutoCandidate>,
    resolve: suspend (AutoCandidate) -> ProfileItem?,
    attempt: suspend (AutoCandidate, Int, Int, Long) -> Boolean,
    nowMillis: () -> Long,
    canContinue: () -> Boolean,
    deadlineMillis: Long,
    limits: AutoPickLimits = AutoPickLimits(),
): Boolean {
    val attempted = mutableSetOf<ProfileItem>()
    val backlog = linkedMapOf<String, MutableList<String>>()
    fun hasTime() = canContinue() && nowMillis() < deadlineMillis
    fun phaseDeadline(phaseMillis: Long) =
        minOf(deadlineMillis, nowMillis().coerceAtMost(Long.MAX_VALUE - phaseMillis) + phaseMillis)
    fun retain(candidates: List<AutoCandidate>) {
        candidates.forEach { backlog.getOrPut(it.groupId) { mutableListOf() }.add(it.guid) }
    }

    suspend fun tryBatch(candidates: List<AutoCandidate>, maxAttempts: Int, phaseMillis: Long): Boolean {
        val queue = candidates.distinctBy { it.guid }
        val batchDeadline = phaseDeadline(phaseMillis)
        var attempts = 0
        for ((index, candidate) in queue.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (!hasTime()) return false
            // Each nonempty phase can try its first eligible candidate while the whole search has time.
            if (attempts >= maxAttempts || (attempts > 0 && nowMillis() >= batchDeadline)) {
                retain(queue.drop(index))
                return false
            }
            val profile = resolve(candidate) ?: continue
            currentCoroutineContext().ensureActive()
            if (!hasTime()) return false
            if (attempts > 0 && nowMillis() >= batchDeadline) {
                retain(queue.drop(index))
                return false
            }
            if (!ServiceWebProbe.canProbeProfile(profile) ||
                profile.subscriptionId.ifBlank { AppConfig.DEFAULT_SUBSCRIPTION_ID } != candidate.groupId ||
                !attempted.add(profile.duplicateIdentity())) continue
            attempts++
            // A phase limits candidate starts; an admitted candidate needs all three service checks.
            val passed = attempt(candidate, attempts, minOf(queue.size, maxAttempts), deadlineMillis)
            currentCoroutineContext().ensureActive()
            if (passed) return true
        }
        return false
    }

    if (tryBatch(recent, limits.recentAttempts, limits.recentMillis)) return true
    currentCoroutineContext().ensureActive()
    if (!hasTime()) return false
    val saved = loadSaved()
    currentCoroutineContext().ensureActive()
    if (tryBatch(saved, limits.savedAttempts, limits.savedMillis)) return true
    for (sourceId in sourceIds.distinct()) {
        currentCoroutineContext().ensureActive()
        if (!hasTime()) return false
        val refreshed = refreshSource(sourceId, deadlineMillis)
        currentCoroutineContext().ensureActive()
        if (tryBatch(refreshed, limits.sourceAttempts, limits.sourceMillis)) return true
    }
    currentCoroutineContext().ensureActive()
    if (!hasTime()) return false
    return tryBatch(interleaveCandidates(backlog.map { it.key to it.value }), Int.MAX_VALUE, Long.MAX_VALUE)
}
