package com.v2ray.ang.handler

import com.v2ray.ang.dto.SubscriptionUpdateResult
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OfficialVpnBookSourceTest {
    @Test
    fun unnamedProviderKeysUseHostWhileExplicitLabelsRemainUnchanged() {
        val candidate = profile("ca225.vpnbook.com", "443", "test-password")
        listOf("", "  ", "none", " None ").forEach { missingLabel ->
            assertEquals("VPNBook · ca225.vpnbook.com",
                OfficialVpnBookSource.displayName(candidate.copy(remarks = missingLabel)))
        }
        assertEquals("Canada — personal label", OfficialVpnBookSource.displayName(
            candidate.copy(remarks = "Canada — personal label")))
    }

    @Test
    fun onlyExactOfficialPageActivatesHtmlAdapter() {
        assertTrue(OfficialVpnBookSource.matches("https://www.vpnbook.com/freevpn/outline-vpn"))
        listOf(
            "http://www.vpnbook.com/freevpn/outline-vpn",
            "https://www.vpnbook.com/freevpn/outline-vpn?other=1",
            "https://www.vpnbook.com.evil.example/freevpn/outline-vpn",
        ).forEach { assertFalse(OfficialVpnBookSource.matches(it)) }
    }

    @Test
    fun extractsNextJsEscapedKeysWithoutTrailingBackslashAndDeduplicatesHtmlCopies() {
        val link = "ss://bWV0aG9kOnBhc3M@pl134.vpnbook.com:443/?outline=1"
        val html = """<script>self.__next_f.push([1,"{\"connectionString\":\"$link\"}"])</script><a href="$link">copy</a>"""

        assertEquals(listOf(link), OfficialVpnBookSource.extractUris(html))
    }

    @Test
    fun decodesHtmlEntitiesAndEscapedSlashes() {
        val html = """<a href=&quot;ss:\/\/bWV0aG9kOnBhc3M@ca225.vpnbook.com:443/?outline=1&amp;x=2&quot;>key</a>"""

        assertEquals(
            listOf("ss://bWV0aG9kOnBhc3M@ca225.vpnbook.com:443/?outline=1&x=2"),
            OfficialVpnBookSource.extractUris(html),
        )
    }

    @Test
    fun ignoresMissingKeysMalformedUrisAndUnrelatedEmbeddedEndpoints() {
        val html = """<p>Unavailable</p> ss://token@not-vpnbook.com:443 ss://token@vpnbook.com.evil.test:443 ss://token@pl134.vpnbook.com:70000 ss://no-host"""

        assertTrue(OfficialVpnBookSource.extractUris(html).isEmpty())
        assertTrue(OfficialVpnBookSource.extractUris("").isEmpty())
    }

    @Test
    fun deduplicatesRealConnectionIdentityIgnoringLabelsGroupAndTimestamps() {
        val candidate = profile("PL134.VPNBOOK.COM.", "443", "pass")
        val existing = profile("pl134.vpnbook.com", "0443", "pass").copy(
            remarks = "A completely different name",
            description = "Other metadata",
            subscriptionId = "other-catalog",
            addedTime = 1,
        )
        val canada = profile("ca225.vpnbook.com", "443", "different")

        val result = OfficialVpnBookSource.keepUnique(listOf(candidate, canada), listOf(existing))

        assertEquals(listOf(canada), result.profiles)
        assertEquals(1, result.duplicateCount)
    }

    @Test
    fun differentPortPasswordEncryptionAndProtocolRemainDistinct() {
        val candidate = profile("host.vpnbook.com", "443", "pass")
        val others = listOf(
            candidate.copy(serverPort = "8443"),
            candidate.copy(password = "other"),
            candidate.copy(method = "aes-256-gcm"),
            candidate.copy(configType = EConfigType.VLESS),
        )

        assertEquals(listOf(candidate), OfficialVpnBookSource.keepUnique(listOf(candidate), others).profiles)
    }

    @Test
    fun repeatedProviderEntriesAppearOnlyOnceAndAllDuplicatesReturnEmptyList() {
        val candidate = profile("host.vpnbook.com", "443", "pass")
        val one = OfficialVpnBookSource.keepUnique(listOf(candidate, candidate.copy(remarks = "another label")), emptyList())
        val none = OfficialVpnBookSource.keepUnique(listOf(candidate), listOf(candidate.copy(subscriptionId = "other")))

        assertEquals(1, one.profiles.size)
        assertEquals(1, one.duplicateCount)
        assertTrue(none.profiles.isEmpty())
        assertEquals(1, none.duplicateCount)
    }

    @Test
    fun subscriptionUpdateTotalsIncludeRemovedDuplicates() {
        val result = SubscriptionUpdateResult(configCount = 1, successCount = 1, duplicateCount = 2) +
            SubscriptionUpdateResult(successCount = 1, duplicateCount = 3)

        assertEquals(1, result.configCount)
        assertEquals(2, result.successCount)
        assertEquals(5, result.duplicateCount)
    }

    @Test
    fun importingOtherCatalogAfterProviderRemovesOverlapWithoutReloadingProvider() {
        val poland = profile("pl134.vpnbook.com", "443", "pass")
        val canada = profile("ca225.vpnbook.com", "443", "other")
        val providerFirst = linkedMapOf("poland-id" to poland, "canada-id" to canada)
        val importedLater = listOf(poland.copy(remarks = "Catalog label", subscriptionId = "new-catalog"))

        val before = OfficialVpnBookSource.uniqueProfileIds(providerFirst, emptyList())
        val after = OfficialVpnBookSource.uniqueProfileIds(providerFirst, importedLater)
        val reverseOrder = OfficialVpnBookSource.keepUnique(providerFirst.values.toList(), importedLater)

        assertEquals(listOf("poland-id", "canada-id"), before)
        assertEquals(listOf("canada-id"), after)
        assertEquals(listOf(canada), reverseOrder.profiles)
        assertEquals(2, providerFirst.size)
    }

    private fun profile(host: String, port: String, password: String) = ProfileItem(
        configType = EConfigType.SHADOWSOCKS,
        server = host,
        serverPort = port,
        password = password,
        method = "chacha20-ietf-poly1305",
        remarks = "Provider",
        subscriptionId = "provider-group",
    )
}
