package com.v2ray.ang.ui.main

import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.ServiceWebProbe
import com.v2ray.ang.dto.entities.ProfileItem
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
