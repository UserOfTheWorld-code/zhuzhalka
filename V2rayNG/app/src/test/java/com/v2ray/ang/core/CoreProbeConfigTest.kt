package com.v2ray.ang.core

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.CoreResolvedType
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class CoreProbeConfigTest {
    @Test fun probePlanUsesOnlyTheSuppliedStrictSnapshotWithoutGuidOrSubscriptionLookups() {
        val original = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "unread-group", insecure = true,
            server = "snapshot.example", serverPort = "443", password = "test", sni = "sni.example",
            security = "reality", publicKey = "test-key", shortId = "test-id", pinnedCA256 = "ab".repeat(32))
        // No MMKV initialization or native environment is available in this JVM test.
        val plan = CoreConfigContextBuilder.buildProbe(mock<Context>(), "unread-guid", original)!!
        assertEquals("unread-guid", plan.guid)
        assertFalse(plan.isCustom)
        assertTrue(plan.routingDomainRules.isEmpty())
        assertEquals(1, plan.resolvedOutbounds.size)
        val outbound = plan.resolvedOutbounds.single()
        assertEquals(AppConfig.TAG_PROXY, outbound.tag)
        assertEquals(CoreResolvedType.NORMAL, outbound.resolvedType)
        assertEquals(listOf(original.copy(insecure = false)), outbound.resolvedProfiles)
        original.password = "changed after capture"
        assertEquals("test", outbound.profile.password)
        assertEquals(false, outbound.profile.insecure)
    }

    @Test fun arbitraryJsonGroupsAndExplicitChainsCannotBecomeSingleProfileProbePlans() {
        for (type in listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN)) {
            assertNull(CoreConfigContextBuilder.buildProbe(mock(), "guid", ProfileItem(configType = type)))
        }
        assertNull(CoreConfigContextBuilder.buildProbe(mock(), "guid",
            ProfileItem(configType = EConfigType.VLESS, proxyChainProfiles = "other")))
    }
}
