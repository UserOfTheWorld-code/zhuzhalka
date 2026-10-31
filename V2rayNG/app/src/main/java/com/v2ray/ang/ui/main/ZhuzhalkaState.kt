package com.v2ray.ang.ui.main

import com.v2ray.ang.core.ServiceProbeResult
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

internal data class AutoCandidate(val groupId: String, val guid: String)

/** Recheck the current stored profile before the simple screen delegates a start to Android. */
internal fun simpleConnectionIssue(dataSource: MainDataSource, guid: String?): Int? {
    val profile = guid?.let(dataSource::decodeServerConfig) ?: return R.string.buzz_profile_unsupported
    if (profile.insecure == true && profile.pinnedCA256.isNullOrEmpty()) return R.string.buzz_profile_insecure
    return if (resolveAutoCandidate(dataSource, guid) == null) R.string.buzz_profile_unsupported else null
}

/** A reused GUID is not evidence that the current connection is the one that passed the probes. */
internal fun resolveTestedAutoCandidate(dataSource: MainDataSource, guid: String, testedProfile: ProfileItem): AutoCandidate? {
    val candidate = resolveAutoCandidate(dataSource, guid) ?: return null
    val current = dataSource.decodeServerConfig(guid) ?: return null
    return candidate.takeIf { current.duplicateIdentity() == testedProfile.duplicateIdentity() }
}

/** Only a complete strict success may tighten the stored profile; cancellation never accepts a partial test. */
internal suspend fun acceptTestedAutoCandidate(
    dataSource: MainDataSource,
    guid: String,
    original: ProfileItem,
    result: ServiceProbeResult,
): AutoCandidate? {
    currentCoroutineContext().ensureActive()
    if (!result.passed || resolveTestedAutoCandidate(dataSource, guid, original) == null) return null
    currentCoroutineContext().ensureActive()
    if (!dataSource.acceptStrictProbe(guid, original)) return null
    return resolveTestedAutoCandidate(dataSource, guid, original.copy(insecure = false))
}

/** STOP_SUCCESS precedes TUN teardown; also wait until Android no longer routes probes through VPN. */
internal suspend fun awaitAutoPickDisconnect(
    running: Boolean,
    disconnectConfirmed: Boolean,
    runningUpdates: Flow<Boolean>,
    requestStop: () -> Unit,
    hasVpnRoute: suspend () -> Boolean,
    timeoutMillis: Long = 8_000L,
): Boolean {
    if (running && !disconnectConfirmed) return false
    if (running) requestStop()
    return withTimeoutOrNull(timeoutMillis) {
        if (running) runningUpdates.first { !it }
        // Poll the actual framework route within the same bounded wait. This is not a
        // fixed grace delay or a claim that every daemon resource has finished closing.
        while (hasVpnRoute()) delay(50L)
        true
    } ?: false
}

/** Publish the selected group and profile together before returning to a cancellable caller. */
internal fun applyZhuzhalkaSelection(
    dataSource: MainDataSource,
    groupId: String,
    guid: String,
    publish: (String, String) -> Unit,
): Boolean {
    if (dataSource.decodeServerConfig(guid) == null || guid !in dataSource.getServerGuidList(groupId)) return false
    val previousGroup = dataSource.getSelectedSubscriptionId()
    val previousGuid = dataSource.getSelectServer()
    try {
        dataSource.setSelectedSubscriptionId(groupId)
        dataSource.setSelectServer(guid)
        check(dataSource.getSelectedSubscriptionId() == groupId && dataSource.getSelectServer() == guid) {
            "Selected group and profile could not be verified"
        }
        publish(groupId, guid)
        return true
    } catch (error: Exception) {
        try {
            dataSource.setSelectedSubscriptionId(previousGroup)
        } catch (rollbackError: Exception) {
            error.addSuppressed(rollbackError)
        }
        try {
            // This storage API uses a blank GUID for an unselected profile.
            dataSource.setSelectServer(previousGuid.orEmpty())
        } catch (rollbackError: Exception) {
            error.addSuppressed(rollbackError)
        }
        if (dataSource.getSelectedSubscriptionId() != previousGroup || dataSource.getSelectServer().orEmpty() != previousGuid.orEmpty()) {
            error.addSuppressed(IllegalStateException("Selection rollback could not be verified"))
        }
        throw error
    }
}

data class SubscriptionButton(val groupId: String, val name: String, val publicSourceId: String?, val lastUpdated: Long, val url: String = "")

/** Interleave catalogs so a large first subscription cannot starve the other ones. */
internal fun interleaveCandidates(groups: List<Pair<String, List<String>>>): List<AutoCandidate> {
    val seen = mutableSetOf<String>()
    return buildList {
        for (index in 0 until (groups.maxOfOrNull { it.second.size } ?: 0)) {
            groups.forEach { (groupId, servers) ->
                servers.getOrNull(index)?.takeIf { seen.add(it) }?.let { add(AutoCandidate(groupId, it)) }
            }
        }
    }
}

data class ZhuzhalkaState(
    val busy: Boolean = false,
    val autoPicking: Boolean = false,
    val cancelling: Boolean = false,
    val waitingForDisconnect: Boolean = false,
    val messageRes: Int? = null,
    val importedCount: Int = 0,
    val attempted: Int = 0,
    val total: Int = 0,
    val testedGuid: String? = null,
    val probe: ServiceProbeResult? = null,
    val operationSource: String? = null
)
