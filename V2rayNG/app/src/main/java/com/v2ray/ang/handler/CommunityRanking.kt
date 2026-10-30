package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.NetworkType
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Advisory endpoint identity, deliberately shared across different credentials on that endpoint. */
data class CommunityEndpointKey(val sourceId: String, val endpointHash: String)

/** The transport must authenticate authorId and the entire vote before passing it to this policy. */
data class CommunityVote(
    val key: CommunityEndpointKey,
    val authorId: String,
    val observedAtMillis: Long,
    val passed: Boolean,
    val latencyBucketMillis: Int? = null,
)

/**
 * Pure limits and ordering for untrusted community hints. These never establish connectivity:
 * every promoted profile still needs the normal local three-service check and selection checks.
 * Signatures identify keys, not people; a bounded promotion prevents votes from replacing the queue.
 */
object CommunityRanking {
    const val TTL_MILLIS = 60 * 60 * 1000L
    const val MAX_INPUT_VOTES = 4096
    const val MAX_RETAINED_VOTES = 2048
    const val MAX_BOOSTS = 3
    const val MIN_PEERS = 2
    const val LATENCY_BUCKET_MILLIS = 250
    const val MAX_LATENCY_MILLIS = 60_000

    private val hex64 = Regex("[0-9a-fA-F]{64}")
    private val supportedProtocols = setOf(EConfigType.VLESS, EConfigType.VMESS, EConfigType.TROJAN,
        EConfigType.SHADOWSOCKS, EConfigType.HYSTERIA2, EConfigType.WIREGUARD)

    /**
     * Only exact curated, unauthenticated subscription URLs qualify. No URL, password, username,
     * key, SNI, transport path, arbitrary header or hash of credentials enters the fingerprint.
     * The digest is an identifier, not anonymity: public catalog endpoints can be enumerated.
     * URL association does not prove that a user has not edited a profile inside that group, and
     * does not authorize exporting any profile or subscription. This API only creates rating keys.
     */
    fun publicEndpointKey(
        sourceId: String,
        subscription: SubscriptionItem,
        profile: ProfileItem,
    ): CommunityEndpointKey? {
        val source = PublicSourceCatalog.sources.firstOrNull { it.id == sourceId } ?: return null
        if (subscription.url != source.subscriptionUrl || !subscription.enabled || subscription.allowInsecureUrl ||
            !subscription.requestHeaders.isNullOrBlank() || !subscription.userAgent.isNullOrBlank() ||
            !subscription.prevProfile.isNullOrBlank() || !subscription.nextProfile.isNullOrBlank() ||
            profile.configType !in supportedProtocols || profile.insecure == true ||
            !profile.proxyChainProfiles.isNullOrBlank()) return null
        val host = normalizePublicHost(profile.server) ?: return null
        val port = profile.serverPort?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: return null
        val network = when (val value = profile.network.orEmpty().lowercase(Locale.ROOT)) {
            "", "raw", "tcp" -> "tcp"
            "http", "h2" -> "h2"
            else -> value.takeIf { candidate -> NetworkType.entries.any { it.type == candidate } } ?: return null
        }
        val security = when (val value = profile.security.orEmpty().lowercase(Locale.ROOT)) {
            "", "none" -> "none"
            "tls", "reality" -> value
            else -> return null
        }
        val material = listOf("zhuzhalka-endpoint-v1", sourceId, profile.configType.name.lowercase(Locale.ROOT),
            host, port.toString(), network, security).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return CommunityEndpointKey(sourceId, digest)
    }

    /** Round the slowest successful service measurement; rejected values must not be published. */
    fun roundLatencyMillis(slowestMillis: Long): Int? = slowestMillis
        .takeIf { it in 0L..MAX_LATENCY_MILLIS.toLong() }
        ?.let { ((it + LATENCY_BUCKET_MILLIS - 1) / LATENCY_BUCKET_MILLIS * LATENCY_BUCKET_MILLIS).toInt() }

    /** One latest vote per authenticated author and endpoint, with strict time and size limits. */
    fun retainVotes(votes: List<CommunityVote>, nowMillis: Long): List<CommunityVote> {
        if (nowMillis <= 0L) return emptyList()
        val knownSources = PublicSourceCatalog.sources.map { it.id }.toSet()
        return votes.asSequence().take(MAX_INPUT_VOTES)
            .filter { it.key.sourceId in knownSources && hex64.matches(it.key.endpointHash) && hex64.matches(it.authorId) &&
                it.observedAtMillis > 0L && it.observedAtMillis <= nowMillis &&
                nowMillis - it.observedAtMillis <= TTL_MILLIS &&
                (it.latencyBucketMillis == null || (it.passed && it.latencyBucketMillis in 0..MAX_LATENCY_MILLIS)) }
            .map { it.copy(key = it.key.copy(endpointHash = it.key.endpointHash.lowercase(Locale.ROOT)),
                authorId = it.authorId.lowercase(Locale.ROOT),
                latencyBucketMillis = it.latencyBucketMillis?.let { value -> roundLatencyMillis(value.toLong()) }) }
            // A contradictory same-timestamp vote is resolved conservatively, independent of relay order.
            .sortedWith(compareByDescending<CommunityVote> { it.observedAtMillis }
                .thenBy { it.passed }.thenByDescending { it.latencyBucketMillis ?: Int.MAX_VALUE }
                .thenBy { it.authorId }.thenBy { it.key.sourceId }.thenBy { it.key.endpointHash })
            .distinctBy { it.authorId to it.key }
            .take(MAX_RETAINED_VOTES)
            .toList()
    }

    /**
     * Keep locally preferred candidates in the protected prefix. Promote at most three other
     * endpoints; retain every remaining candidate in its original order. Callers leave the recent
     * local-success phase outside this ordering entirely. Unknown keys and absent hints are neutral.
     */
    fun <T> promote(
        candidates: List<T>,
        votes: List<CommunityVote>,
        nowMillis: Long,
        keyOf: (T) -> CommunityEndpointKey?,
        maxBoosts: Int = MAX_BOOSTS,
        protectedPrefixCount: Int = 0,
    ): List<T> {
        val count = maxBoosts.coerceIn(0, MAX_BOOSTS)
        val prefix = protectedPrefixCount.coerceIn(0, candidates.size)
        if (count == 0 || prefix == candidates.size) return candidates
        val scores = retainVotes(votes, nowMillis).groupBy { it.key }.mapValues { (_, reports) ->
            val successes = reports.count { it.passed }
            val latencies = reports.filter { it.passed }.mapNotNull { it.latencyBucketMillis }.sorted()
            val median = if (latencies.isEmpty()) Int.MAX_VALUE
                else (latencies[(latencies.size - 1) / 2] + latencies[latencies.size / 2]) / 2
            Score(successes, reports.size, median)
        }.filterValues { it.peers >= MIN_PEERS && it.successes * 2 > it.peers }
        if (scores.isEmpty()) return candidates
        val protectedKeys = candidates.take(prefix).mapNotNull(keyOf).toSet()
        val ranked = candidates.withIndex().drop(prefix).mapNotNull { item ->
            val key = keyOf(item.value) ?: return@mapNotNull null
            if (key in protectedKeys) return@mapNotNull null
            scores[key]?.let { RankedCandidate(item.index, key, it) }
        }.sortedWith { left, right ->
            val ratio = (right.score.successes.toLong() * left.score.peers)
                .compareTo(left.score.successes.toLong() * right.score.peers)
            when {
                ratio != 0 -> ratio
                left.score.peers != right.score.peers -> right.score.peers.compareTo(left.score.peers)
                left.score.medianLatency != right.score.medianLatency -> left.score.medianLatency.compareTo(right.score.medianLatency)
                else -> left.index.compareTo(right.index)
            }
        }.distinctBy { it.key }.take(count)
        val promotedIndices = ranked.map { it.index }.toSet()
        return candidates.take(prefix) + ranked.map { candidates[it.index] } +
            candidates.filterIndexed { index, _ -> index >= prefix && index !in promotedIndices }
    }

    private data class Score(val successes: Int, val peers: Int, val medianLatency: Int)
    private data class RankedCandidate(val index: Int, val key: CommunityEndpointKey, val score: Score)

    internal fun normalizePublicHost(value: String?): String? {
        val host = value?.trim()?.removeSurrounding("[", "]")?.removeSuffix(".") ?: return null
        if (host.isEmpty() || host.length > 253 || host.any { it.isWhitespace() || it in "@/%?#\\" }) return null
        if (':' in host) {
            if (!host.all { it in "0123456789abcdefABCDEF:." }) return null
            return try {
                // A validated numeric literal uses InetAddress parsing only, never hostname lookup.
                if (URI("https://[$host]/").host == null) return null
                val address = InetAddress.getByName(host)
                if (!isPublicLiteral(address)) null else address.hostAddress?.lowercase(Locale.ROOT)
            } catch (_: Exception) { null }
        }
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { part -> part.isEmpty() || part.length > 3 ||
                    part.toIntOrNull()?.let { it in 0..255 } != true }) return null
            val address = InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
            return if (isPublicLiteral(address)) address.hostAddress else null
        }
        return try {
            val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            val labels = ascii.split('.')
            if (ascii.length > 253 || labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 } ||
                labels.last().all(Char::isDigit) || labels.last() in
                setOf("local", "localhost", "lan", "internal", "home", "test", "invalid", "example", "onion")) null
            else ascii
        } catch (_: IllegalArgumentException) { null }
    }

    private fun isPublicLiteral(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress ||
            address.isLinkLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 0xff }
        return if (bytes.size == 4) {
            val (first, second, third) = bytes
            first != 0 && first < 224 && !(first == 100 && second in 64..127) &&
                !(first == 192 && second == 0 && third in setOf(0, 2)) &&
                !(first == 198 && (second in 18..19 || (second == 51 && third == 100))) &&
                !(first == 203 && second == 0 && third == 113)
        } else {
            (bytes[0] and 0xfe) != 0xfc &&
                !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
        }
    }
}
