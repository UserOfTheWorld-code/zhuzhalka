package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import java.net.URI
import java.util.Locale

/** Adapter for keys deliberately published by VPNBook; never treats arbitrary subscription HTML as configs. */
internal object OfficialVpnBookSource {
    const val URL = "https://www.vpnbook.com/freevpn/outline-vpn"
    private val links = Regex("""ss://[^\s"'<>\\]+""")

    fun matches(url: String): Boolean = url == URL

    fun displayName(profile: ProfileItem): String = profile.remarks.takeUnless {
        it.isBlank() || it.trim().equals("none", ignoreCase = true)
    } ?: "VPNBook · ${profile.server}"

    fun extractUris(html: String): List<String> {
        val decoded = html.replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        return links.findAll(decoded).map { it.value }.filter { link ->
            try {
                val uri = URI(link)
                val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
                // The official page publishes its own VPNBook endpoints, not unrelated embedded links.
                uri.scheme == "ss" && host.endsWith(".vpnbook.com") &&
                    uri.port in 1..65535 && !uri.rawUserInfo.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }.distinct().toList()
    }

    data class FilterResult(val profiles: List<ProfileItem>, val duplicateCount: Int)

    fun keepUnique(candidates: List<ProfileItem>, otherProfiles: List<ProfileItem>): FilterResult {
        val seen = otherProfiles.mapNotNull(::identity).toMutableSet()
        val unique = candidates.filter { profile -> identity(profile)?.let(seen::add) == true }
        return FilterResult(unique, candidates.size - unique.size)
    }

    /** Reconciles an already imported provider group after another list changes, without downloading it. */
    fun uniqueProfileIds(profiles: Map<String, ProfileItem>, otherProfiles: List<ProfileItem>): List<String> {
        val seen = otherProfiles.mapNotNull(::identity).toMutableSet()
        return profiles.filterValues { profile -> identity(profile)?.let(seen::add) ?: true }.keys.toList()
    }

    private data class Identity(
        val protocol: EConfigType,
        val host: String,
        val port: Int,
        val method: String,
        val password: String,
    )

    private fun identity(profile: ProfileItem): Identity? {
        if (profile.configType != EConfigType.SHADOWSOCKS) return null
        val host = profile.server?.trim()?.removeSurrounding("[", "]")
            ?.trimEnd('.')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val port = profile.serverPort?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val method = profile.method?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        val password = profile.password?.takeIf { it.isNotEmpty() } ?: return null
        return Identity(profile.configType, host, port, method, password)
    }
}
