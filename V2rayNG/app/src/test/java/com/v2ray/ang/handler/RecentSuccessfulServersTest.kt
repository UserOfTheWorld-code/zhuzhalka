package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.RecentSuccessfulServer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecentSuccessfulServersTest {
    private val now = 2 * RecentSuccessfulServers.TTL_MILLIS

    @Test fun absentCacheDoesNotInventSuccesses() {
        assertTrue(RecentSuccessfulServers.retain(emptyList(), now).isEmpty())
    }

    @Test fun expiresOldFutureAndInvalidRecordsButKeepsBoundary() {
        val entries = listOf(
            RecentSuccessfulServer("old", now - RecentSuccessfulServers.TTL_MILLIS - 1),
            RecentSuccessfulServer("boundary", now - RecentSuccessfulServers.TTL_MILLIS),
            RecentSuccessfulServer("future", now + 1),
            RecentSuccessfulServer("zero", 0),
            RecentSuccessfulServer("negative", -1),
            RecentSuccessfulServer(" ", now),
        )
        assertEquals(listOf(entries[1]), RecentSuccessfulServers.retain(entries, now))
    }

    @Test fun retainsOnlyFiveMostRecentDistinctGuids() {
        val entries = (0..7).map { RecentSuccessfulServer("server-$it", now - it) }
        val result = RecentSuccessfulServers.retain(entries.reversed() + entries[2].copy(verifiedAtMillis = now), now)
        assertEquals(5, result.size)
        assertEquals(5, result.map { it.guid }.toSet().size)
        assertEquals(listOf("server-0", "server-2", "server-1", "server-3", "server-4"), result.map { it.guid })
    }

    @Test fun rememberedSuccessMovesToFrontAndRenewsTimestamp() {
        val entries = (0..4).map { RecentSuccessfulServer("server-$it", now - it - 1) }
        val result = RecentSuccessfulServers.remember(entries, "server-4", now)
        assertEquals(RecentSuccessfulServer("server-4", now), result.first())
        assertEquals(listOf("server-4", "server-0", "server-1", "server-2", "server-3"), result.map { it.guid })
        assertEquals(5, result.size)
    }

    @Test fun newSuccessDropsOldestAndCannotRecordBlankGuidOrInvalidTime() {
        val entries = (0..4).map { RecentSuccessfulServer("server-$it", now - it - 1) }
        assertEquals(listOf("new", "server-0", "server-1", "server-2", "server-3"),
            RecentSuccessfulServers.remember(entries, "new", now).map { it.guid })
        assertThrows(IllegalArgumentException::class.java) { RecentSuccessfulServers.remember(entries, "", now) }
        assertThrows(IllegalArgumentException::class.java) { RecentSuccessfulServers.remember(entries, "new", 0) }
    }
}
