package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.RecentSuccessfulServer

/** Retention policy for recent three-service successes; storage remains owned by MmkvManager. */
internal object RecentSuccessfulServers {
    const val MAX_ENTRIES = 5
    const val TTL_MILLIS = 24 * 60 * 60 * 1000L

    fun retain(entries: List<RecentSuccessfulServer>, nowMillis: Long): List<RecentSuccessfulServer> = entries
        .filter { !it.guid.isNullOrBlank() && it.verifiedAtMillis > 0L &&
            it.verifiedAtMillis <= nowMillis && nowMillis - it.verifiedAtMillis <= TTL_MILLIS }
        .sortedByDescending { it.verifiedAtMillis }
        .distinctBy { it.guid }
        .take(MAX_ENTRIES)

    fun remember(entries: List<RecentSuccessfulServer>, guid: String, nowMillis: Long): List<RecentSuccessfulServer> {
        require(guid.isNotBlank() && nowMillis > 0L)
        return retain(listOf(RecentSuccessfulServer(guid, nowMillis)) + entries.filterNot { it.guid == guid }, nowMillis)
    }
}
