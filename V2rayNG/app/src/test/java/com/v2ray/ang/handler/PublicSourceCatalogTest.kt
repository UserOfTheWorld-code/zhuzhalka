package com.v2ray.ang.handler

import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import com.v2ray.ang.util.HttpUtil
import okhttp3.Headers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicSourceCatalogTest {
    @Test fun autoDownloadFinishesBeforeAnyNewGroupIsCreated() = runBlocking {
        val result = importer.addCustomAndRefreshForAuto(source.displayName, source.subscriptionUrl, 1000, { 0 },
            download = { item, deadline ->
                assertEquals(source.subscriptionUrl, item.url)
                assertEquals(1000L, deadline)
                assertTrue(storage.items.isEmpty())
                HttpUtil.SubscriptionContent("body", Headers.Builder().build())
            }, importDownloaded = { target, content ->
                assertEquals("body", content.body)
                storage.profiles[target.guid] = 2
                SubscriptionUpdateResult(configCount = 2, successCount = 1)
            })
        assertEquals(PublicSourceImportStatus.SUCCESS, result.status)
        assertEquals(2, result.configCount)
        assertTrue(refreshes.isEmpty(), "Legacy proxy download must not run")
    }

    @Test fun cancelledOrFailedAutoDownloadNeverCreatesAGroup() = runBlocking {
        for (cancelled in listOf(false, true)) {
            try {
                val result = importer.addCustomAndRefreshForAuto(source.displayName, source.subscriptionUrl, 1000, { 0 },
                    download = { _, _ -> if (cancelled) throw CancellationException() else error("network") },
                    importDownloaded = { _, _ -> error("Must not import") })
                assertEquals(PublicSourceImportStatus.REFRESH_FAILED, result.status)
                assertFalse(cancelled)
            } catch (_: CancellationException) { assertTrue(cancelled) }
            assertTrue(storage.items.isEmpty())
            assertTrue(storage.ids.isEmpty())
        }
    }

    @Test fun expiredAutoBudgetDoesNotFetchAndLateResponseDoesNotImport() = runBlocking {
        var now = 1000L
        var fetched = 0
        val download: suspend (SubscriptionItem, Long) -> HttpUtil.SubscriptionContent = { _, _ ->
            fetched++; now = 1000; HttpUtil.SubscriptionContent("body", Headers.Builder().build())
        }
        repeat(2) { run ->
            if (run == 1) now = 0
            val result = importer.addCustomAndRefreshForAuto(source.displayName, source.subscriptionUrl, 1000, { now }, download,
                importDownloaded = { _, _ -> error("Expired result must not import") })
            assertEquals(PublicSourceImportStatus.REFRESH_FAILED, result.status)
        }
        assertEquals(1, fetched)
        assertTrue(storage.items.isEmpty())
    }

    @Test fun disabledOrChangedSubscriptionNeverImportsAnAutoDownload() = runBlocking {
        val item = SubscriptionItem(remarks = "Private name", url = source.subscriptionUrl, enabled = false)
        storage.seed("existing", item, 4)
        val disabled = importer.addCustomAndRefreshForAuto("ignored", source.subscriptionUrl, 1000, { 0 },
            download = { _, _ -> error("Disabled list must not fetch") }, importDownloaded = { _, _ -> error("No import") })
        assertEquals(PublicSourceImportStatus.DISABLED, disabled.status)
        storage.items["existing"] = item.copy(enabled = true, requestHeaders = "test")
        val changed = importer.addCustomAndRefreshForAuto("ignored", source.subscriptionUrl, 1000, { 0 },
            download = { snapshot, _ ->
                assertEquals("test", snapshot.requestHeaders)
                storage.items["existing"] = snapshot.copy(requestHeaders = "changed")
                HttpUtil.SubscriptionContent("body", Headers.Builder().build())
            }, importDownloaded = { _, _ -> error("Old authentication must not import") })
        assertEquals(PublicSourceImportStatus.REFRESH_FAILED, changed.status)
        assertEquals(4, storage.profiles["existing"])
        assertEquals("changed", storage.items["existing"]?.requestHeaders)
    }

    private val source = PublicSource("first", "Publisher", "https://example.org/subscription")
    private val storage = FakeStorage()
    private val refreshes = mutableListOf<SubscriptionCache>()
    private var refreshResult = SubscriptionUpdateResult(configCount = 3, successCount = 1)
    private var refreshError: Exception? = null
    private var persistProfiles = true
    private val importer = PublicSourceImporter(
        sources = listOf(source),
        storage = storage,
        refresh = { target ->
            refreshes.add(target)
            refreshError?.let { throw it }
            if (persistProfiles && refreshResult.successCount > 0) {
                storage.profiles[target.guid] = refreshResult.configCount
            }
            refreshResult
        },
        newId = { "new-id" },
    )

    @Test
    fun unknownSourceDoesNotAccessStorageOrNetwork() {
        val result = importer.addAndRefresh("missing")

        assertEquals(PublicSourceImportStatus.UNKNOWN_SOURCE, result.status)
        assertEquals(0, storage.reads)
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun addsOnlySelectedSourceAndReportsVerifiedConfigCount() {
        storage.seed("other", SubscriptionItem(remarks = "Private", url = "https://private.example/sub"), 8)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.SUCCESS, result.status)
        assertEquals("new-id", result.subscriptionId)
        assertEquals(3, result.configCount)
        assertTrue(result.added)
        assertEquals(listOf("new-id"), refreshes.map { it.guid })
        assertEquals(listOf("other", "new-id"), storage.ids)
        assertEquals(8, storage.profiles["other"])
        assertEquals(source.displayName, storage.items["new-id"]?.remarks)
        assertEquals(false, storage.items["new-id"]?.autoUpdate)
    }

    @Test
    fun repeatedActionRefreshesExistingSubscriptionWithoutDuplicatingIt() {
        importer.addAndRefresh(source.id)
        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.SUCCESS, result.status)
        assertFalse(result.added)
        assertEquals("new-id", result.subscriptionId)
        assertEquals(1, storage.saves)
        assertEquals(listOf("new-id"), storage.ids)
        assertEquals(listOf("new-id", "new-id"), refreshes.map { it.guid })
    }

    @Test
    fun exactUrlMatchPreservesUserNameFilterAndAuthentication() {
        val saved = SubscriptionItem(
            remarks = "My own name",
            url = source.subscriptionUrl,
            filter = "chosen-region",
            userAgent = "my-client",
            requestHeaders = "private headers",
            autoUpdate = true,
        )
        storage.seed("user-id", saved, 6)

        val result = importer.addCustomAndRefresh("Replacement", "  ${source.subscriptionUrl}  ")

        assertEquals("user-id", result.subscriptionId)
        assertEquals(saved, storage.items["user-id"])
        assertEquals(saved, refreshes.single().subscription)
        assertEquals(0, storage.saves)
        assertFalse(result.added)
    }

    @Test
    fun matchingNameDoesNotMergeDifferentUrls() {
        storage.seed("user-id", SubscriptionItem(remarks = source.displayName, url = "https://other.example/sub"), 7)

        val result = importer.addAndRefresh(source.id)

        assertTrue(result.added)
        assertEquals(listOf("user-id", "new-id"), storage.ids)
        assertEquals(7, storage.profiles["user-id"])
    }

    @Test
    fun disabledUserSubscriptionStaysDisabledAndIsNotFetched() {
        storage.seed("user-id", SubscriptionItem(url = source.subscriptionUrl, enabled = false), 5)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.DISABLED, result.status)
        assertEquals("user-id", result.subscriptionId)
        assertTrue(refreshes.isEmpty())
        assertEquals(false, storage.items["user-id"]?.enabled)
        assertEquals(5, storage.profiles["user-id"])
    }

    @Test
    fun invalidCustomUrlsDoNotAccessStorageOrNetwork() {
        listOf(
            "", "http://example.org/sub", "vless://example.org", "https:///missing-host",
            "https://user:password@example.org/sub", "https://example.org:70000/sub",
            "https://example.org/a b", "https://example.org/sub\nAuthorization: secret",
        ).forEach { url ->
            assertEquals(PublicSourceImportStatus.INVALID_URL, importer.addCustomAndRefresh("Name", url).status)
        }
        assertEquals(0, storage.reads)
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun blankCustomNameFallsBackToHostWithoutSubscriptionToken() {
        val result = importer.addCustomAndRefresh(" ", "https://provider.example/private-token?key=secret")

        assertEquals(PublicSourceImportStatus.SUCCESS, result.status)
        assertEquals("provider.example", storage.items["new-id"]?.remarks)
    }

    @Test
    fun unsuccessfulDownloadRemovesOnlyNewSubscription() {
        storage.seed("other", SubscriptionItem(url = "https://other.example/sub"), 9)
        refreshResult = SubscriptionUpdateResult(failureCount = 1)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.REFRESH_FAILED, result.status)
        assertNull(result.subscriptionId)
        assertFalse(result.added)
        assertEquals(listOf("other"), storage.ids)
        assertEquals(9, storage.profiles["other"])
        assertNull(storage.items["new-id"])
    }

    @Test
    fun failedRefreshPreservesExistingSubscriptionAndProfiles() {
        val saved = SubscriptionItem(remarks = "Keep", url = source.subscriptionUrl, lastUpdated = 123)
        storage.seed("user-id", saved, 9)
        refreshResult = SubscriptionUpdateResult(failureCount = 1)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.REFRESH_FAILED, result.status)
        assertEquals("user-id", result.subscriptionId)
        assertEquals(saved, storage.items["user-id"])
        assertEquals(9, storage.profiles["user-id"])
        assertEquals(0, storage.removes)
        assertEquals(0, storage.saves)
    }

    @Test
    fun refreshExceptionRollsBackNewRecord() {
        refreshError = IllegalStateException("network unavailable")

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.REFRESH_FAILED, result.status)
        assertTrue(storage.items.isEmpty())
        assertTrue(storage.ids.isEmpty())
    }

    @Test
    fun partialSubscriptionWriteIsRolledBackBeforeNetwork() {
        storage.failAfterPayloadWrite = true
        storage.seed("other", SubscriptionItem(url = "https://other.example/sub"), 2)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.STORAGE_FAILED, result.status)
        assertTrue(refreshes.isEmpty())
        assertNull(storage.items["new-id"])
        assertEquals(listOf("other"), storage.ids)
        assertEquals(2, storage.profiles["other"])
    }

    @Test
    fun silentlyMissingSubscriptionIndexIsDetectedAndRolledBack() {
        storage.skipIndexWrite = true

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.STORAGE_FAILED, result.status)
        assertTrue(refreshes.isEmpty())
        assertTrue(storage.items.isEmpty())
    }

    @Test
    fun reportedSuccessWithoutStoredProfilesIsNotReportedAsSuccess() {
        persistProfiles = false

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.STORAGE_FAILED, result.status)
        assertTrue(storage.items.isEmpty())
        assertEquals(0, result.configCount)
    }

    @Test
    fun failedRollbackReportsStorageFailureAndRetainedId() {
        refreshResult = SubscriptionUpdateResult(failureCount = 1)
        storage.ignoreRemove = true

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.STORAGE_FAILED, result.status)
        assertEquals("new-id", result.subscriptionId)
        assertFalse(result.added)
    }

    @Test
    fun cancellationIsRethrownAfterRollback() {
        refreshError = CancellationException("owner ended")

        assertThrows(CancellationException::class.java) { importer.addAndRefresh(source.id) }

        assertTrue(storage.items.isEmpty())
        assertTrue(storage.ids.isEmpty())
    }

    @Test
    fun uuidCollisionCannotOverwriteUserSubscription() {
        val saved = SubscriptionItem(remarks = "Private", url = "https://private.example/sub")
        storage.seed("new-id", saved, 12)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.STORAGE_FAILED, result.status)
        assertEquals(saved, storage.items["new-id"])
        assertEquals(12, storage.profiles["new-id"])
        assertEquals(0, storage.saves)
        assertEquals(0, storage.removes)
        assertTrue(refreshes.isEmpty())
    }

    @Test
    fun catalogHasUniqueStableIdsAndHttpsSubscriptionUrls() {
        val sources = PublicSourceCatalog.sources

        assertEquals(listOf("au1rxx", "eternity", "vpnbook", "radikal", "matin", "nodesradar"), sources.map { it.id })
        assertEquals(sources.size, sources.map { it.subscriptionUrl }.distinct().size)
        assertTrue(sources.all { it.subscriptionUrl.startsWith("https://") })
    }

    @Test
    fun validDuplicateOnlySourceRemainsAvailableForFutureRefreshWithExplicitEmptyResult() {
        refreshResult = SubscriptionUpdateResult(configCount = 0, successCount = 1, duplicateCount = 3)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.NO_UNIQUE_SERVERS, result.status)
        assertEquals(0, result.configCount)
        assertEquals(3, result.duplicateCount)
        assertEquals("new-id", result.subscriptionId)
        assertEquals(listOf("new-id"), storage.ids)
        assertTrue(result.added)
    }

    @Test
    fun duplicateOnlyResultCannotLeaveStaleIndexedProfiles() {
        storage.seed("user-id", SubscriptionItem(url = source.subscriptionUrl), 7)
        persistProfiles = false
        refreshResult = SubscriptionUpdateResult(configCount = 0, successCount = 1, duplicateCount = 3)

        val result = importer.addAndRefresh(source.id)

        assertEquals(PublicSourceImportStatus.STORAGE_FAILED, result.status)
        assertEquals("user-id", result.subscriptionId)
        assertEquals(0, result.configCount)
    }

    private class FakeStorage : PublicSourceStorage {
        val items = linkedMapOf<String, SubscriptionItem>()
        val ids = mutableListOf<String>()
        val profiles = mutableMapOf<String, Int>()
        var reads = 0
        var saves = 0
        var removes = 0
        var failAfterPayloadWrite = false
        var skipIndexWrite = false
        var ignoreRemove = false

        fun seed(id: String, item: SubscriptionItem, profileCount: Int) {
            items[id] = item.copy()
            ids.add(id)
            profiles[id] = profileCount
        }

        override fun subscriptions(): List<SubscriptionCache> {
            reads++
            return ids.mapNotNull { id -> items[id]?.let { SubscriptionCache(id, it.copy()) } }
        }

        override fun subscription(id: String) = items[id]?.copy()
        override fun subscriptionIds() = ids.toList()

        override fun save(id: String, item: SubscriptionItem) {
            saves++
            items[id] = item.copy()
            if (failAfterPayloadWrite) throw IllegalStateException("Index unavailable")
            if (!skipIndexWrite && id !in ids) ids.add(id)
        }

        override fun remove(id: String) {
            removes++
            if (ignoreRemove) return
            items.remove(id)
            ids.remove(id)
            profiles.remove(id)
        }

        override fun profileCount(id: String) = profiles[id] ?: 0
    }
}
