package com.v2ray.ang.handler

import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.enums.EConfigType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommunityRankingTest {
    private val now = 10_000_000L
    private val source = PublicSourceCatalog.sources.first()
    private fun subscription() = SubscriptionItem(url = source.subscriptionUrl)
    private fun profile() = ProfileItem(configType = EConfigType.VLESS, subscriptionId = "local-group",
        server = "node.example.net", serverPort = "443", password = "private-auth", network = "ws", security = "tls")
    private fun key(profile: ProfileItem = profile(), subscription: SubscriptionItem = subscription()) =
        CommunityRanking.publicEndpointKey(source.id, subscription, profile)
    private fun endpoint(number: Int) = CommunityEndpointKey(source.id, number.toString(16).padStart(64, '0'))
    private fun author(number: Int) = number.toString(16).padStart(64, '0')
    private fun vote(endpointNumber: Int, authorNumber: Int, passed: Boolean = true, age: Long = 0L, latency: Int? = null) =
        CommunityVote(endpoint(endpointNumber), author(authorNumber), now - age, passed, latency)
    private fun ranked(candidates: List<Int>, votes: List<CommunityVote>, max: Int = 3, prefix: Int = 0) =
        CommunityRanking.promote(candidates, votes, now, ::endpoint, max, prefix)
    private fun twoVotes(endpoint: Int, latency: Int? = null) =
        listOf(vote(endpoint, 1, latency = latency), vote(endpoint, 2, latency = latency))

    @Test fun eachCuratedSourceCanProduceItsOwnOpaqueEndpointKey() {
        PublicSourceCatalog.sources.forEach { entry ->
            val result = CommunityRanking.publicEndpointKey(entry.id, SubscriptionItem(url = entry.subscriptionUrl), profile())
            assertEquals(entry.id, result?.sourceId)
            assertTrue(result?.endpointHash?.matches(Regex("[0-9a-f]{64}")) == true)
            assertFalse(result.toString().contains("node.example.net"))
            assertFalse(result.toString().contains("private-auth"))
        }
    }

    @Test fun customUrlUnknownSourceAndSourceUrlMismatchCannotCreateSharingKey() {
        assertNull(CommunityRanking.publicEndpointKey("private", subscription(), profile()))
        assertNull(key(subscription = subscription().copy(url = "https://private.example.net/secret-token")))
        assertNull(key(subscription = subscription().copy(url = source.subscriptionUrl + "?token=secret")))
        assertNull(CommunityRanking.publicEndpointKey(PublicSourceCatalog.sources[1].id, subscription(), profile()))
    }

    @Test fun authenticatedDisabledAndChainSubscriptionsDoNotQualify() {
        listOf(subscription().copy(requestHeaders = "Authorization: secret"),
            subscription().copy(userAgent = "custom-secret"), subscription().copy(enabled = false),
            subscription().copy(allowInsecureUrl = true), subscription().copy(prevProfile = "private"),
            subscription().copy(nextProfile = "private")).forEach { assertNull(key(subscription = it)) }
    }

    @Test fun unsupportedInsecureAndChainedProfilesDoNotQualify() {
        listOf(EConfigType.CUSTOM, EConfigType.HTTP, EConfigType.SOCKS, EConfigType.POLICYGROUP,
            EConfigType.PROXYCHAIN, EConfigType.HYSTERIA).forEach { assertNull(key(profile().copy(configType = it))) }
        assertNull(key(profile().copy(insecure = true)))
        assertNull(key(profile().copy(insecure = true, pinnedCA256 = "pin")))
        assertNull(key(profile().copy(proxyChainProfiles = "private")))
    }

    @Test fun credentialsAndTokenBearingTransportFieldsNeverAffectPublicKey() {
        val changed = profile().copy(password = "different", username = "user-secret", secretKey = "wireguard-secret",
            preSharedKey = "preshared-secret", obfsPassword = "obfs-secret", quicKey = "quic-secret", seed = "seed-secret",
            path = "/token-secret", serviceName = "grpc-secret", host = "token-header", authority = "authority-secret",
            sni = "sni-secret", xhttpExtra = "json-secret", shortId = "reality-secret", spiderX = "/spider-secret",
            publicKey = "public-key", remarks = "new label", description = "private notes", addedTime = 55L,
            subscriptionId = "another-local-guid")
        assertEquals(key(), key(changed))
    }

    @Test fun endpointProtocolTransportAndSecuritySeparateAdvisoryKeys() {
        val original = key()
        listOf(profile().copy(server = "other.example.net"), profile().copy(serverPort = "8443"),
            profile().copy(configType = EConfigType.TROJAN), profile().copy(network = "grpc"),
            profile().copy(security = "reality")).forEach { assertNotEquals(original, key(it)) }
    }

    @Test fun equivalentHostnamePortAndDefaultsNormalizeWithoutDns() {
        assertEquals(key(), key(profile().copy(server = "NODE.Example.NET.", serverPort = "00443")))
        assertEquals(key(profile().copy(network = null, security = null)),
            key(profile().copy(network = "raw", security = "none")))
        assertEquals(key(profile().copy(network = "http")), key(profile().copy(network = "h2")))
        assertEquals(key(profile().copy(server = "xn--bcher-kva.de")), key(profile().copy(server = "bücher.de")))
        assertEquals(key(profile().copy(server = "[2606:4700:4700::1111]")),
            key(profile().copy(server = "2606:4700:4700:0:0:0:0:1111")))
    }

    @Test fun invalidHostPortOrArbitraryClassificationCannotLeakIntoKey() {
        listOf("", "user:secret@proxy.net", "https://proxy.net", "node.example.net/path", "node.example.net?token=x",
            "node.example.net\nsecret", "bad_name.example.net", "::bad::ip", "[::1%eth0]", "127.1", "999.1.1.1")
            .forEach { assertNull(key(profile().copy(server = it)), it) }
        listOf("", "0", "65536", "-1", "443/path", "99999999999999")
            .forEach { assertNull(key(profile().copy(serverPort = it)), it) }
        assertNull(key(profile().copy(network = "custom-secret")))
        assertNull(key(profile().copy(security = "secret")))
    }

    @Test fun privateLocalAndDocumentationAddressesAreNotShared() {
        listOf("localhost", "server.local", "private.lan", "private.internal", "private.home", "x.onion",
            "0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.1.1", "172.16.2.3", "192.168.1.1",
            "100.64.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.1.1.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd12::1", "fe80::1", "ff02::1", "2001:db8::1", "::ffff:127.0.0.1")
            .forEach { assertNull(key(profile().copy(server = it)), it) }
        assertNotNull(key(profile().copy(server = "8.8.8.8")))
        assertNotNull(key(profile().copy(server = "2606:4700:4700::1111")))
    }

    @Test fun latencyIsRoundedUpWithinExplicitBounds() {
        assertEquals(0, CommunityRanking.roundLatencyMillis(0))
        assertEquals(250, CommunityRanking.roundLatencyMillis(1))
        assertEquals(250, CommunityRanking.roundLatencyMillis(250))
        assertEquals(500, CommunityRanking.roundLatencyMillis(251))
        assertEquals(60_000, CommunityRanking.roundLatencyMillis(60_000))
        assertNull(CommunityRanking.roundLatencyMillis(-1))
        assertNull(CommunityRanking.roundLatencyMillis(60_001))
        assertNull(CommunityRanking.roundLatencyMillis(Long.MAX_VALUE))
    }

    @Test fun oneHourBoundaryRetainedButExpiredFutureAndInvalidTimesRejected() {
        val boundary = vote(1, 1, age = CommunityRanking.TTL_MILLIS)
        assertEquals(listOf(boundary), CommunityRanking.retainVotes(listOf(boundary,
            vote(1, 2, age = CommunityRanking.TTL_MILLIS + 1), vote(1, 3, age = -1),
            vote(1, 4).copy(observedAtMillis = 0L), vote(1, 5).copy(observedAtMillis = Long.MIN_VALUE)), now))
        assertTrue(CommunityRanking.retainVotes(listOf(boundary), 0L).isEmpty())
        assertTrue(CommunityRanking.retainVotes(listOf(boundary), -1L).isEmpty())
    }

    @Test fun malformedAuthorsEndpointsAndLatencyAreRejected() {
        val valid = vote(1, 1)
        val malformed = listOf(valid.copy(authorId = "username"), valid.copy(authorId = "g".repeat(64)),
            valid.copy(key = endpoint(1).copy(sourceId = "custom")),
            valid.copy(key = endpoint(1).copy(endpointHash = "private-url")),
            valid.copy(latencyBucketMillis = -1), valid.copy(latencyBucketMillis = 60_001),
            valid.copy(passed = false, latencyBucketMillis = 1))
        assertTrue(CommunityRanking.retainVotes(malformed, now).isEmpty())
        assertEquals(500, CommunityRanking.retainVotes(listOf(valid.copy(latencyBucketMillis = 251)), now).single().latencyBucketMillis)
    }

    @Test fun latestVotePerAuthorAndEndpointWinsIncludingFailure() {
        val previous = vote(1, 1, age = 20)
        val latest = vote(1, 1, passed = false)
        val otherEndpoint = vote(2, 1)
        assertEquals(setOf(latest, otherEndpoint), CommunityRanking.retainVotes(listOf(previous, latest, otherEndpoint), now).toSet())
    }

    @Test fun authorCaseCannotCreateExtraPeerAndConflictingTimestampIsConservative() {
        val lower = vote(1, 0xab)
        val upper = lower.copy(authorId = lower.authorId.uppercase(), key = lower.key.copy(endpointHash = lower.key.endpointHash.uppercase()))
        assertEquals(1, CommunityRanking.retainVotes(listOf(lower, upper), now).size)
        val failure = lower.copy(passed = false)
        assertEquals(listOf(failure), CommunityRanking.retainVotes(listOf(lower, failure), now))
        assertEquals(listOf(failure), CommunityRanking.retainVotes(listOf(failure, lower), now))
    }

    @Test fun retainedVotesHaveBoundedSizeAndInputScanning() {
        val input = (1..CommunityRanking.MAX_INPUT_VOTES + 1).map { vote(1, it, age = it.toLong()) }
        val retained = CommunityRanking.retainVotes(input, now)
        assertEquals(CommunityRanking.MAX_RETAINED_VOTES, retained.size)
        assertEquals(author(1), retained.first().authorId)
        assertTrue(retained.none { it.authorId == author(CommunityRanking.MAX_INPUT_VOTES + 1) })
        val invalidPrefix = List(CommunityRanking.MAX_INPUT_VOTES) { vote(1, 1).copy(authorId = "invalid") }
        assertTrue(CommunityRanking.retainVotes(invalidPrefix + vote(1, 2), now).isEmpty())
    }

    @Test fun noHintsAndExpiredHintsPreserveOriginalCandidateOrder() {
        val candidates = listOf(3, 2, 1)
        assertEquals(candidates, ranked(candidates, emptyList()))
        assertEquals(candidates, ranked(candidates, twoVotes(1).map { it.copy(observedAtMillis = now - CommunityRanking.TTL_MILLIS - 1) }))
    }

    @Test fun onePeerAndRepeatedVotesCannotPromoteAnEndpoint() {
        val candidates = listOf(1, 2)
        assertEquals(candidates, ranked(candidates, List(100) { vote(2, 1, age = it.toLong()) }))
        assertEquals(candidates, ranked(candidates, listOf(vote(2, 1))))
    }

    @Test fun negativeOrTiedVotesDoNotHideOrPromoteCandidates() {
        val candidates = listOf(1, 2, 3)
        assertEquals(candidates, ranked(candidates, listOf(vote(3, 1), vote(3, 2, false))))
        assertEquals(candidates, ranked(candidates, listOf(vote(3, 1, false), vote(3, 2, false))))
    }

    @Test fun signedHintsOnlyChangeOrderAndNeverCreateCandidates() {
        assertEquals(listOf(3, 1, 2), ranked(listOf(1, 2, 3), twoVotes(3) + twoVotes(999)))
        assertTrue(ranked(emptyList(), twoVotes(3)).isEmpty())
    }

    @Test fun successfulPeerRatioPrecedesSpeedAndRawVoteCount() {
        val mixed = (1..10).map { vote(2, it, passed = it <= 9, latency = if (it <= 9) 250 else null) }
        val unanimous = twoVotes(3, latency = 60_000)
        assertEquals(listOf(3, 2, 1), ranked(listOf(1, 2, 3), mixed + unanimous))
    }

    @Test fun confidenceThenMedianLatencyBreakEqualRatioTies() {
        val stronger = (1..3).map { vote(3, it, latency = 60_000) }
        val fast = twoVotes(2, 250)
        assertEquals(listOf(3, 2, 1), ranked(listOf(1, 2, 3), stronger + fast))
        assertEquals(listOf(3, 2, 1), ranked(listOf(1, 2, 3), twoVotes(2, 1000) + twoVotes(3, 250)))
        val withOutlier = listOf(vote(2, 1, latency = 250), vote(2, 2, latency = 500), vote(2, 3, latency = 60_000))
        assertEquals(listOf(2, 3, 1), ranked(listOf(1, 3, 2), withOutlier + (1..3).map { vote(3, it, latency = 750) }))
    }

    @Test fun missingLatencyDoesNotOutrankKnownLatencyAndExactTiesRemainStable() {
        assertEquals(listOf(3, 2, 1), ranked(listOf(1, 2, 3), twoVotes(2) + twoVotes(3, 1000)))
        assertEquals(listOf(3, 2, 1), ranked(listOf(1, 3, 2), twoVotes(2, 500) + twoVotes(3, 500)))
    }

    @Test fun threeBoostHardCapPreservesAllRemainingOriginalOrder() {
        val candidates = (1..10).toList()
        val hints = (6..10).flatMap { twoVotes(it, latency = (11 - it) * 250) }
        assertEquals(listOf(10, 9, 8, 1, 2, 3, 4, 5, 6, 7), ranked(candidates, hints, max = 99))
        assertEquals(candidates, ranked(candidates, hints, max = 0))
        assertEquals(candidates, ranked(candidates, hints, max = -1))
    }

    @Test fun localPreferredPrefixRemainsAheadOfAllCommunityHints() {
        assertEquals(listOf(1, 2, 4, 3, 5), ranked(listOf(1, 2, 3, 4, 5), twoVotes(4), prefix = 2))
        assertEquals(listOf(1, 2, 3), ranked(listOf(1, 2, 3), twoVotes(3), prefix = 99))
    }

    @Test fun duplicateEndpointCannotConsumeMoreThanOnePromotionOrRepeatProtectedEndpoint() {
        val keys = mapOf("one" to endpoint(1), "two" to endpoint(2), "two-copy" to endpoint(2), "three" to endpoint(3))
        val candidates = listOf("one", "two-copy", "two", "three")
        val hints = twoVotes(2, 250) + twoVotes(3, 500)
        assertEquals(listOf("two-copy", "three", "one", "two"),
            CommunityRanking.promote(candidates, hints, now, { keys[it] }))
        assertEquals(listOf("two", "three", "one", "two-copy"),
            CommunityRanking.promote(listOf("two", "one", "two-copy", "three"), hints, now, { keys[it] }, protectedPrefixCount = 1))
    }

    @Test fun nullKeysStayInQueueAndNoQueueEntryIsLostOrDuplicated() {
        val candidates = listOf("private", "public", "unmapped")
        assertEquals(listOf("public", "private", "unmapped"), CommunityRanking.promote(candidates, twoVotes(1), now,
            { if (it == "public") endpoint(1) else null }))
    }
}
