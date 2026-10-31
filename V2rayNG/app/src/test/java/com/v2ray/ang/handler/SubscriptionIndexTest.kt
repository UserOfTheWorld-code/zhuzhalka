package com.v2ray.ang.handler

import android.util.Log
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.RecentSuccessfulServer
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.JsonUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockStatic
import org.mockito.MockedStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.reset
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SubscriptionIndexTest {
    private val mainValues = mutableMapOf<String, String>()
    private val subValues = mutableMapOf<String, String>()
    private val profileValues = mutableMapOf<String, String>()
    private val rawValues = mutableMapOf<String, String>()
    private val affiliationValues = mutableMapOf<String, String>()
    private lateinit var nativeLock: MockedStatic<MmkvProfileIndexLock>

    @BeforeEach
    fun prepareStorage() {
        nativeLock = mockStatic(MmkvProfileIndexLock::class.java)
        for ((storage, values) in listOf(
            main to mainValues,
            subs to subValues,
            profiles to profileValues,
            raw to rawValues,
            affiliations to affiliationValues,
        )) {
            reset(storage)
            whenever(storage.decodeString(any())).thenAnswer { values[it.getArgument<String>(0)] }
            whenever(storage.encode(any<String>(), any<String>())).thenAnswer {
                values[it.getArgument(0)] = it.getArgument(1)
                true
            }
            whenever(storage.allKeys()).thenAnswer { values.keys.toTypedArray() }
            doAnswer {
                values.remove(it.getArgument<String>(0))
                storage
            }.whenever(storage).remove(any())
            doAnswer {
                values.remove(it.getArgument<String>(0))
                null
            }.whenever(storage).removeValueForKey(any())
        }
    }

    @AfterEach
    fun closeNativeLockBoundary() {
        nativeLock.close()
    }

    @Test
    fun duplicateIdsKeepTheirFirstPositionWithoutWritingStorage() {
        val stored = """["second","first","second","third","first"]"""
        mainValues["SUB_IDS"] = stored

        assertEquals(listOf("second", "first", "third"), MmkvManager.decodeSubsList())
        assertEquals(stored, mainValues["SUB_IDS"])
        verify(main, never()).encode(any<String>(), any<String>())
    }

    @Test
    fun strictProbeCommitPreservesGuidIndexSelectionAndCurrentMetadata() {
        val original = seedStrictProbeProfile()
        val renamed = original.copy(remarks = "User renamed", addedTime = 123)
        profileValues["probe"] = JsonUtil.toJson(renamed)
        org.junit.jupiter.api.Assertions.assertTrue(MmkvManager.acceptStrictProbe("probe", original))
        assertEquals(renamed.copy(insecure = false), MmkvManager.decodeServerConfig("probe"))
        assertEquals("other", MmkvManager.getSelectServer())
        assertEquals(listOf("probe"), MmkvManager.decodeServerList("group"))
    }

    @Test
    fun strictProbeCommitRejectsMutationMissingIndexDisabledAndImplicitChains() {
        val original = seedStrictProbeProfile()
        for (changed in listOf(original.copy(password = "new"), original.copy(sni = "new.example"),
            original.copy(subscriptionId = "other"), original.copy(proxyChainProfiles = "other"))) {
            profileValues["probe"] = JsonUtil.toJson(changed)
            org.junit.jupiter.api.Assertions.assertFalse(MmkvManager.acceptStrictProbe("probe", original))
            assertEquals(changed, MmkvManager.decodeServerConfig("probe"))
        }
        profileValues["probe"] = JsonUtil.toJson(original)
        for (subscription in listOf(SubscriptionItem(enabled = false), SubscriptionItem(prevProfile = "previous"),
            SubscriptionItem(nextProfile = "next"))) {
            subValues["group"] = JsonUtil.toJson(subscription)
            org.junit.jupiter.api.Assertions.assertFalse(MmkvManager.acceptStrictProbe("probe", original))
        }
        subValues["group"] = JsonUtil.toJson(SubscriptionItem())
        mainValues["SUB_SERVERS_group"] = "[]"
        org.junit.jupiter.api.Assertions.assertFalse(MmkvManager.acceptStrictProbe("probe", original))
        assertEquals(original, MmkvManager.decodeServerConfig("probe"))
    }

    @Test
    fun failedOrUnverifiedStrictProbeWriteRestoresExactOriginalPayload() {
        for (claimsSuccess in listOf(false, true)) {
            val original = seedStrictProbeProfile()
            val previous = profileValues.getValue("probe")
            val strictJson = JsonUtil.toJson(original.copy(insecure = false))
            doAnswer { profileValues["probe"] = "partial"; claimsSuccess }
                .whenever(profiles).encode("probe", strictJson)
            mockStatic(Log::class.java).use {
                assertThrows(IllegalStateException::class.java) { MmkvManager.acceptStrictProbe("probe", original) }
            }
            assertEquals(previous, profileValues["probe"])
            assertEquals("other", MmkvManager.getSelectServer())
        }
    }

    private fun seedStrictProbeProfile(): ProfileItem {
        val original = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "group", insecure = true,
            server = "test.example", serverPort = "443", password = "test", sni = "test.example")
        profileValues["probe"] = JsonUtil.toJson(original)
        subValues["group"] = JsonUtil.toJson(SubscriptionItem())
        mainValues["SUB_SERVERS_group"] = "[\"probe\"]"
        mainValues["SELECTED_SERVER"] = "other"
        return original
    }

    @Test
    fun recentSuccessCacheRoundTripsGuidsAndTimestampsOnly() {
        val entries = listOf(RecentSuccessfulServer("one", 1000), RecentSuccessfulServer("two", 999))
        assertEquals(emptyList<RecentSuccessfulServer>(), MmkvManager.decodeRecentSuccessfulServers())
        MmkvManager.encodeRecentSuccessfulServers(entries)
        assertEquals(entries, MmkvManager.decodeRecentSuccessfulServers())
        assertEquals(setOf("RECENT_SUCCESSFUL_SERVERS"), mainValues.keys)
    }

    @Test
    fun communityRecordsAreIndependentAndDoNotChangeProfilesOrSelection() {
        mainValues["SELECTED_SERVER"] = "keep"
        profileValues["keep"] = "profile"
        assertNull(MmkvManager.decodeCommunityIdentityRecord())
        assertNull(MmkvManager.decodeCommunityStateRecord())
        MmkvManager.encodeCommunityIdentityRecord("wrapped-key")
        MmkvManager.encodeCommunityStateRecord("community-state")
        assertEquals("wrapped-key", MmkvManager.decodeCommunityIdentityRecord())
        assertEquals("community-state", MmkvManager.decodeCommunityStateRecord())
        assertEquals("keep", mainValues["SELECTED_SERVER"])
        assertEquals(mapOf("keep" to "profile"), profileValues)
    }

    @Test
    fun failedCommunityStateWriteRestoresPreviousRecord() {
        mainValues["COMMUNITY_STATE_V1"] = "previous"
        doAnswer {
            mainValues["COMMUNITY_STATE_V1"] = "partial-write"
            false
        }.whenever(main).encode("COMMUNITY_STATE_V1", "new-state")
        assertThrows(IllegalStateException::class.java) { MmkvManager.encodeCommunityStateRecord("new-state") }
        assertEquals("previous", MmkvManager.decodeCommunityStateRecord())
    }

    @Test
    fun unverifiedNewCommunityIdentityWriteRemovesPartialRecord() {
        doAnswer {
            mainValues["COMMUNITY_IDENTITY_V1"] = "partial-write"
            true
        }.whenever(main).encode("COMMUNITY_IDENTITY_V1", "wrapped-key")
        assertThrows(IllegalStateException::class.java) { MmkvManager.encodeCommunityIdentityRecord("wrapped-key") }
        assertNull(MmkvManager.decodeCommunityIdentityRecord())
    }

    @Test
    fun unchangedCommunityRecordDoesNotRewriteStorage() {
        mainValues["COMMUNITY_STATE_V1"] = "same-state"
        MmkvManager.encodeCommunityStateRecord("same-state")
        verify(main, never()).encode(any<String>(), any<String>())
    }

    @Test
    fun failedRecentSuccessCacheWriteRestoresOldRecord() {
        val previous = JsonUtil.toJson(listOf(RecentSuccessfulServer("keep", 1000)))
        val replacement = JsonUtil.toJson(listOf(RecentSuccessfulServer("new", 2000)))
        mainValues["RECENT_SUCCESSFUL_SERVERS"] = previous
        doAnswer {
            mainValues["RECENT_SUCCESSFUL_SERVERS"] = "partial-write"
            false
        }.whenever(main).encode("RECENT_SUCCESSFUL_SERVERS", replacement)

        assertThrows(IllegalStateException::class.java) {
            MmkvManager.encodeRecentSuccessfulServers(listOf(RecentSuccessfulServer("new", 2000)))
        }
        assertEquals(previous, mainValues["RECENT_SUCCESSFUL_SERVERS"])
    }

    @Test
    fun unverifiedNewCacheWriteRemovesPartialRecord() {
        val replacement = JsonUtil.toJson(listOf(RecentSuccessfulServer("new", 2000)))
        doAnswer {
            mainValues["RECENT_SUCCESSFUL_SERVERS"] = "partial-write"
            true
        }.whenever(main).encode("RECENT_SUCCESSFUL_SERVERS", replacement)

        assertThrows(IllegalStateException::class.java) {
            MmkvManager.encodeRecentSuccessfulServers(listOf(RecentSuccessfulServer("new", 2000)))
        }
        assertNull(mainValues["RECENT_SUCCESSFUL_SERVERS"])
    }

    @Test
    fun nullCacheEntriesAreIgnoredWithoutCrashing() {
        mainValues["RECENT_SUCCESSFUL_SERVERS"] = """[null,{"guid":"keep","verifiedAtMillis":1000}]"""
        assertEquals(listOf(RecentSuccessfulServer("keep", 1000)), MmkvManager.decodeRecentSuccessfulServers())
    }

    @Test
    fun duplicateIndexEntriesProduceOnlyOneSubscriptionRow() {
        mainValues["SUB_IDS"] = """["b","a","b","a"]"""
        subValues["a"] = JsonUtil.toJson(SubscriptionItem(remarks = "Alpha"))
        subValues["b"] = JsonUtil.toJson(SubscriptionItem(remarks = "Beta"))

        val rows = MmkvManager.decodeSubscriptions()

        assertEquals(listOf("b", "a"), rows.map { it.guid })
        assertEquals(listOf("Beta", "Alpha"), rows.map { it.subscription.remarks })
    }

    @Test
    fun repeatedAndBlankNamesDoNotMergeDifferentSubscriptions() {
        mainValues["SUB_IDS"] = """["a","b","c","d"]"""
        listOf("a" to "Same", "b" to "Same", "c" to "", "d" to " ").forEach { (id, name) ->
            subValues[id] = JsonUtil.toJson(SubscriptionItem(remarks = name))
        }

        assertEquals(listOf("a", "b", "c", "d"), MmkvManager.decodeSubscriptions().map { it.guid })
    }

    @Test
    fun decodedIndexRemainsMutableAndCanBeSavedInANewOrder() {
        mainValues["SUB_IDS"] = """["a","b","a"]"""
        val ids = MmkvManager.decodeSubsList()
        ids.remove("a")
        ids.add(0, "c")
        MmkvManager.encodeSubsList(ids)

        assertEquals("""["c","b"]""", mainValues["SUB_IDS"])
        assertEquals(listOf("c", "b"), MmkvManager.decodeSubsList())
    }

    @Test
    fun missingBlankAndEmptyIndexesRemainEmpty() {
        assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        listOf("", " ", "[]", "null").forEach { stored ->
            mainValues["SUB_IDS"] = stored
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    @Test
    fun malformedIndexKeepsTheExistingEmptyFallback() {
        mockStatic(Log::class.java).use {
            mainValues["SUB_IDS"] = "{"
            assertEquals(emptyList<String>(), MmkvManager.decodeSubsList())
        }
    }

    @Test
    fun newSubscriptionRollbackRemovesIndexedAndUnindexedPayloadsOnlyForThatSubscription() {
        mainValues["SUB_IDS"] = """["user","new"]"""
        mainValues["SUB_SERVERS_new"] = """["indexed","missing-profile"]"""
        mainValues["SUB_SERVERS_user"] = """["user-profile"]"""
        mainValues["SELECTED_SERVER"] = "indexed"
        subValues["user"] = JsonUtil.toJson(SubscriptionItem(remarks = "Keep"))
        subValues["new"] = JsonUtil.toJson(SubscriptionItem(remarks = "Failed import"))
        mapOf("indexed" to "new", "orphan" to "new", "user-profile" to "user").forEach { (id, subId) ->
            profileValues[id] = JsonUtil.toJson(ProfileItem(configType = EConfigType.VLESS, subscriptionId = subId))
            rawValues[id] = "raw-$id"
            affiliationValues[id] = "aff-$id"
        }
        rawValues["missing-profile"] = "partially removed raw profile"

        MmkvManager.removeNewSubscription("new")

        assertEquals(listOf("user"), MmkvManager.decodeSubsList())
        assertEquals(listOf("user-profile"), MmkvManager.decodeServerList("user"))
        assertEquals(setOf("user-profile"), profileValues.keys)
        assertEquals(mapOf("user-profile" to "raw-user-profile"), rawValues)
        assertEquals(mapOf("user-profile" to "aff-user-profile"), affiliationValues)
        assertEquals(setOf("user"), subValues.keys)
        assertNull(mainValues["SUB_SERVERS_new"])
        assertNull(MmkvManager.getSelectServer())
        nativeLock.verify { MmkvProfileIndexLock.lock(main) }
        nativeLock.verify { MmkvProfileIndexLock.unlock(main) }
    }

    @Test
    fun failedAuxiliaryRollbackKeepsIdentifyingPayloadAndSubscriptionForRetry() {
        mainValues["SUB_IDS"] = """["new"]"""
        subValues["new"] = JsonUtil.toJson(SubscriptionItem(remarks = "Failed import"))
        profileValues["orphan"] = JsonUtil.toJson(ProfileItem(configType = EConfigType.VLESS, subscriptionId = "new"))
        rawValues["orphan"] = "raw"
        doNothing().whenever(raw).removeValueForKey(any())

        assertThrows(IllegalStateException::class.java) { MmkvManager.removeNewSubscription("new") }

        assertEquals(setOf("orphan"), profileValues.keys)
        assertEquals(setOf("new"), subValues.keys)
        assertEquals(listOf("new"), MmkvManager.decodeSubsList())
        nativeLock.verify { MmkvProfileIndexLock.lock(main) }
        nativeLock.verify { MmkvProfileIndexLock.unlock(main) }
    }

    @Test
    fun rollbackCannotRemoveTheDefaultSubscription() {
        assertThrows(IllegalArgumentException::class.java) { MmkvManager.removeNewSubscription(AppConfig.DEFAULT_SUBSCRIPTION_ID) }
        assertThrows(IllegalArgumentException::class.java) { MmkvManager.removeNewSubscription("") }
        verify(subs, never()).remove(any())
    }

    @Test
    fun duplicateOnlySourceClearsItsVisibleIndexAndPreservesActivePayload() {
        mainValues["SUB_SERVERS_provider"] = """["active"]"""
        mainValues["SUB_SERVERS_other"] = """["other"]"""
        mainValues["SELECTED_SERVER"] = "active"
        profileValues["active"] = "active-payload"

        MmkvManager.clearSubscriptionProfileIndex("provider")

        assertEquals(emptyList<String>(), MmkvManager.decodeServerList("provider"))
        assertEquals(listOf("other"), MmkvManager.decodeServerList("other"))
        assertEquals("active", MmkvManager.getSelectServer())
        assertEquals("active-payload", profileValues["active"])
    }

    @Test
    fun failedEmptyIndexWriteRestoresPreviousGroup() {
        mainValues["SUB_SERVERS_provider"] = """["keep"]"""
        doReturn(false).whenever(main).encode("SUB_SERVERS_provider", "[]")

        assertThrows(IllegalStateException::class.java) { MmkvManager.clearSubscriptionProfileIndex("provider") }

        assertEquals(listOf("keep"), MmkvManager.decodeServerList("provider"))
    }

    companion object {
        private val main: MMKV = mock()
        private val subs: MMKV = mock()
        private val settings: MMKV = mock()
        private val profiles: MMKV = mock()
        private val raw: MMKV = mock()
        private val affiliations: MMKV = mock()

        @BeforeAll
        @JvmStatic
        fun initializeHandles() {
            mockStatic(MMKV::class.java).use {
                it.`when`<MMKV> { MMKV.mmkvWithID("MAIN", MMKV.MULTI_PROCESS_MODE) }.thenReturn(main)
                it.`when`<MMKV> { MMKV.mmkvWithID("SUB", MMKV.MULTI_PROCESS_MODE) }.thenReturn(subs)
                it.`when`<MMKV> { MMKV.mmkvWithID("SETTING", MMKV.MULTI_PROCESS_MODE) }.thenReturn(settings)
                it.`when`<MMKV> { MMKV.mmkvWithID("PROFILE_FULL_CONFIG", MMKV.MULTI_PROCESS_MODE) }.thenReturn(profiles)
                it.`when`<MMKV> { MMKV.mmkvWithID("SERVER_RAW", MMKV.MULTI_PROCESS_MODE) }.thenReturn(raw)
                it.`when`<MMKV> { MMKV.mmkvWithID("SERVER_AFF", MMKV.MULTI_PROCESS_MODE) }.thenReturn(affiliations)
                MmkvManager.decodeSubscriptions()
                MmkvManager.decodeSettingsString("test-initialize")
                MmkvManager.decodeServerConfig("test-initialize")
                MmkvManager.decodeServerRaw("test-initialize")
                MmkvManager.decodeServerAffiliationInfo("test-initialize")
            }
        }
    }
}
