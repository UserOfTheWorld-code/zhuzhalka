package com.v2ray.ang.ui.main

import com.v2ray.ang.core.ProbeService
import com.v2ray.ang.core.ServiceProbeResult
import com.v2ray.ang.core.ServiceWebProbe
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.PublicSourceImportResult
import com.v2ray.ang.handler.PublicSourceImportStatus
import com.v2ray.ang.R
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ZhuzhalkaSelectionTest {
    @Test fun catalogsAreInterleavedWithoutRepeatingProfileIds() {
        assertEquals(listOf(AutoCandidate("a", "1"), AutoCandidate("b", "3"), AutoCandidate("a", "2")),
            interleaveCandidates(listOf("a" to listOf("1", "2"), "b" to listOf("3", "1"))))
        assertTrue(interleaveCandidates(emptyList()).isEmpty())
        assertTrue(interleaveCandidates(listOf("empty" to emptyList())).isEmpty())
    }

    @Test fun onlyAllThreeSuccessfulChecksPass() {
        val successful = ProbeService.entries.associateWith { 12L }
        assertTrue(ServiceProbeResult(successful).passed)
        assertFalse(ServiceProbeResult(emptyMap()).passed)
        for (service in ProbeService.entries) {
            assertFalse(ServiceProbeResult(successful - service).passed)
            assertFalse(ServiceProbeResult(successful + (service to -1L)).passed)
        }
    }

    @Test fun importFailuresNeverDisplayLoadedMessage() {
        for (status in PublicSourceImportStatus.entries) {
            val message = importResultMessage(PublicSourceImportResult(status))
            assertEquals(status == PublicSourceImportStatus.SUCCESS, message == R.string.buzz_loaded)
        }
        assertEquals(R.string.buzz_invalid_url, importResultMessage(PublicSourceImportResult(PublicSourceImportStatus.INVALID_URL)))
        assertEquals(R.string.buzz_no_unique, importResultMessage(PublicSourceImportResult(PublicSourceImportStatus.NO_UNIQUE_SERVERS)))
    }

    @Test fun certificatePinnedProfilesAreEligibleEvenWithLegacyInsecureFlag() {
        val profile = ProfileItem(configType = EConfigType.VLESS, insecure = true)
        assertFalse(ServiceWebProbe.canProbeProfile(profile))
        assertFalse(ServiceWebProbe.canProbeProfile(profile.copy(pinnedCA256 = "")))
        assertTrue(ServiceWebProbe.canProbeProfile(profile.copy(pinnedCA256 = "test-certificate-pin")))
        assertTrue(ServiceWebProbe.canProbeProfile(profile.copy(insecure = false)))
        assertTrue(ServiceWebProbe.canProbeProfile(profile.copy(insecure = null)))
    }

    @Test fun pinningCannotMakeDirectOrArbitraryConfigurationsEligibleForProbe() {
        listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN).forEach { type ->
            assertFalse(ServiceWebProbe.canProbeProfile(ProfileItem(
                configType = type, pinnedCA256 = "test-certificate-pin", insecure = true,
            )))
        }
    }

    @Test fun importedOrAutoPickedProfileReplacesDefaultGroupBeforePublishingSuccess() {
        val storage = SelectionStorage()
        var state = MainUiState(selectedGroupId = "Default", selectedGuid = "old")

        assertTrue(applyZhuzhalkaSelection(storage.source, "catalog", "new") { group, guid ->
            assertEquals(group, storage.group)
            assertEquals(guid, storage.guid)
            state = state.copy(selectedGroupId = group, selectedGuid = guid)
        })

        assertEquals("catalog", state.selectedGroupId)
        assertEquals("new", state.selectedGuid)
    }

    @Test fun aCandidateRemovedDuringBackgroundRefreshCannotBecomeSelected() {
        val storage = SelectionStorage()
        whenever(storage.source.getServerGuidList("catalog")).thenReturn(emptyList())

        assertFalse(applyZhuzhalkaSelection(storage.source, "catalog", "new") { _, _ -> fail("Must not publish") })

        assertEquals("Default", storage.group)
        assertEquals("old", storage.guid)
        verify(storage.source, never()).setSelectedSubscriptionId(any())
    }

    @Test fun aPartialSelectionWriteRestoresStorageAndDoesNotPublishSuccess() {
        val storage = SelectionStorage().apply { ignoreNewGroup = true }

        assertThrows(IllegalStateException::class.java) {
            applyZhuzhalkaSelection(storage.source, "catalog", "new") { _, _ -> fail("Must not publish") }
        }

        assertEquals("Default", storage.group)
        assertEquals("old", storage.guid)
    }

    private class SelectionStorage {
        val source: MainDataSource = mock()
        var group = "Default"
        var guid = "old"
        var ignoreNewGroup = false

        init {
            whenever(source.getSelectedSubscriptionId()).thenAnswer { group }
            whenever(source.getSelectServer()).thenAnswer { guid }
            whenever(source.getServerGuidList("catalog")).thenReturn(listOf("new"))
            whenever(source.decodeServerConfig("new")).thenReturn(ProfileItem(configType = EConfigType.VLESS))
            doAnswer { if (!ignoreNewGroup || it.getArgument<String>(0) != "catalog") group = it.getArgument(0); null }
                .whenever(source).setSelectedSubscriptionId(any())
            doAnswer { guid = it.getArgument(0); null }.whenever(source).setSelectServer(any())
        }
    }
}
