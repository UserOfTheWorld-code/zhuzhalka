package com.v2ray.ang.ui.main

import com.v2ray.ang.core.ServiceProbeResult

internal data class AutoCandidate(val groupId: String, val guid: String)

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

data class SubscriptionButton(val groupId: String, val name: String, val publicSourceId: String?, val lastUpdated: Long)

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
    val messageRes: Int? = null,
    val importedCount: Int = 0,
    val attempted: Int = 0,
    val total: Int = 0,
    val testedGuid: String? = null,
    val probe: ServiceProbeResult? = null,
    val operationSource: String? = null
)
