package com.v2ray.ang.handler

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class CommunityProtocolTest {
    private val now = 1_700_000_000_000L
    private val signer by lazy { CommunityCrypto.createSigner(ByteArray(32).also { it[31] = 3 }) }
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private fun vote(index: Int = 1) = CommunityVote(CommunityEndpointKey("au1rxx", index.toString(16).padStart(64, '0')),
        signer.publicKeyHex(), now, true, 500)
    private fun offer(index: Int = 1) = CommunityListOffer("Public list $index", "https://catalog.example.net/list-$index.txt", now)
    private fun signed(bundle: CommunityBundle = CommunityBundle(listOf(vote()), listOf(offer()))) = CommunityProtocol.sign(bundle, signer, now)

    @Test fun actualBip340SignedEventRoundTripsWithoutSecretsOrPrivateProfileData() {
        val bundle = CommunityBundle(listOf(vote()), listOf(offer()))
        val json = signed(bundle)
        val verified = checkNotNull(CommunityProtocol.verify(json, now))
        assertEquals(bundle, verified.bundle)
        assertEquals(signer.publicKeyHex(), verified.author)
        assertEquals(now / 1000, verified.createdAtSeconds)
        assertEquals(json, verified.json)
        val event = JsonParser.parseString(json).asJsonObject
        assertEquals(setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"), event.keySet())
        assertFalse(json.contains("password"))
        assertFalse(json.contains("secretKey"))
    }

    @Test fun emptyBundleIsValidForAnExplicitWithdrawal() {
        val event = signed(CommunityBundle())
        assertEquals(CommunityBundle(), CommunityProtocol.verify(event, now)?.bundle)
    }

    @Test fun modifiedContentIdPublicKeyAndSignatureAreRejected() {
        val event = JsonParser.parseString(signed()).asJsonObject
        for (field in listOf("id", "pubkey", "sig")) {
            val changed = event.deepCopy()
            changed.addProperty(field, if (field == "sig") "0".repeat(128) else "0".repeat(64))
            assertNull(CommunityProtocol.verify(changed.toString(), now), field)
        }
        val changed = event.deepCopy().apply { addProperty("content", "{}") }
        assertNull(CommunityProtocol.verify(changed.toString(), now))
    }

    @Test fun malformedEmptyOversizedAndWrongJsonTypesAreRejected() {
        listOf("", "{", "[]", "null", "false", "x".repeat(CommunityProtocol.MAX_EVENT_CHARS + 1))
            .forEach { assertNull(CommunityProtocol.verify(it, now)) }
        assertNull(CommunityProtocol.verify(rewrite { it.addProperty("content", 12) }, now))
        assertNull(CommunityProtocol.verify(rewrite { it.add("tags", JsonObject()) }, now))
    }

    @Test fun duplicateKeysLenientJsonTrailingDocumentsAndExcessiveDepthAreRejected() {
        val event = signed()
        assertNull(CommunityProtocol.verify("{\"kind\":39799," + event.drop(1), now))
        assertNull(CommunityProtocol.verify(event.replace("\"kind\":", "kind:"), now))
        assertNull(CommunityProtocol.verify(event + "{}", now))
        assertNull(CommunityProtocol.verify(rewrite { outer ->
            outer.addProperty("content", "{\"version\":1," + outer["content"].asString.drop(1))
        }, now))
        assertNull(CommunityProtocol.verify("[".repeat(9) + "0" + "]".repeat(9), now))
    }

    @Test fun signedFutureAndExpiredEventsAreRejectedAtTimeBoundary() {
        assertNotNull(CommunityProtocol.verify(signed(CommunityBundle()), now + CommunityProtocol.TTL_MILLIS))
        assertNull(CommunityProtocol.verify(signed(CommunityBundle()), now + CommunityProtocol.TTL_MILLIS + 1000))
        assertNull(CommunityProtocol.verify(signed(CommunityBundle()), now - 1000))
        assertNull(CommunityProtocol.verify(rewrite { it.addProperty("created_at", 0) }, now))
    }

    @Test fun unknownEventAndBundleFieldsAreRejectedEvenWithAValidSignature() {
        assertNull(CommunityProtocol.verify(rewrite { it.addProperty("private", "unexpected") }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it.addProperty("extra", "unexpected") }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it["votes"].asJsonArray[0].asJsonObject.addProperty("author", signer.publicKeyHex()) }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it["lists"].asJsonArray[0].asJsonObject.addProperty("headers", "secret") }, now))
    }

    @Test fun wrongKindNamespaceTagOrderAndVersionAreRejected() {
        assertNull(CommunityProtocol.verify(rewrite { it.addProperty("kind", 1) }, now))
        assertNull(CommunityProtocol.verify(rewrite { it["tags"].asJsonArray[0].asJsonArray.set(1, gson.toJsonTree("other")) }, now))
        assertNull(CommunityProtocol.verify(rewrite { it["tags"].asJsonArray.add(JsonArray()) }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it.addProperty("version", 2) }, now))
    }

    @Test fun integerAndBooleanFieldsMustHaveCanonicalTypes() {
        assertNull(CommunityProtocol.verify(rewrite { it.addProperty("created_at", (now / 1000).toString()) }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it["votes"].asJsonArray[0].asJsonObject.addProperty("at", 1.5) }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it["votes"].asJsonArray[0].asJsonObject.addProperty("passed", "true") }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it["votes"].asJsonArray[0].asJsonObject.addProperty("latency", "500") }, now))
    }

    @Test fun malformedPrivateAndFutureVotePayloadIsRejectedAfterValidSignature() {
        for ((field, value) in listOf("source" to "private-subscription", "endpoint" to "https://secret.example.net/token")) {
            assertNull(CommunityProtocol.verify(rewriteContent {
                it["votes"].asJsonArray[0].asJsonObject.addProperty(field, value)
            }, now))
        }
        assertNull(CommunityProtocol.verify(rewriteContent {
            it["votes"].asJsonArray[0].asJsonObject.addProperty("at", now + 1)
        }, now))
        assertNull(CommunityProtocol.verify(rewriteContent {
            it["votes"].asJsonArray[0].asJsonObject.addProperty("latency", 60_001)
        }, now))
    }

    @Test fun expirationOfOneEntryPreservesFreshVotesAndOffersInTheSameSignedEvent() {
        val oldAt = now - CommunityProtocol.TTL_MILLIS + 500
        val freshVote = vote(2)
        val freshOffer = offer(2)
        val event = signed(CommunityBundle(listOf(vote(1).copy(observedAtMillis = oldAt), freshVote),
            listOf(offer(1).copy(sharedAtMillis = oldAt), freshOffer)))
        val later = checkNotNull(CommunityProtocol.verify(event, now + 1000))
        assertEquals(listOf(freshVote), later.bundle.votes)
        assertEquals(listOf(freshOffer), later.bundle.offers)
    }

    @Test fun invalidExpiredEntryCannotHideBehindTtlPruning() {
        assertNull(CommunityProtocol.verify(rewriteContent {
            it["votes"].asJsonArray[0].asJsonObject.apply {
                addProperty("at", now - CommunityProtocol.TTL_MILLIS - 1)
                addProperty("endpoint", "private-url")
            }
        }, now))
        assertNull(CommunityProtocol.verify(rewriteContent {
            it["lists"].asJsonArray[0].asJsonObject.apply {
                addProperty("at", now - CommunityProtocol.TTL_MILLIS - 1)
                addProperty("url", "https://localhost./private")
            }
        }, now))
    }

    @Test fun duplicateVotesAndListsCannotInflateOnePublisher() {
        assertNull(CommunityProtocol.verify(rewriteContent { it["votes"].asJsonArray.add(it["votes"].asJsonArray[0].deepCopy()) }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { it["lists"].asJsonArray.add(it["lists"].asJsonArray[0].deepCopy()) }, now))
        assertThrows(IllegalArgumentException::class.java) { signed(CommunityBundle(listOf(vote(), vote()))) }
        assertThrows(IllegalArgumentException::class.java) { signed(CommunityBundle(offers = listOf(offer(), offer()))) }
    }

    @Test fun payloadCountLimitsAreEnforcedByWriterAndReader() {
        assertThrows(IllegalArgumentException::class.java) { signed(CommunityBundle((1..33).map(::vote))) }
        assertThrows(IllegalArgumentException::class.java) { signed(CommunityBundle(offers = (1..9).map(::offer))) }
        assertNull(CommunityProtocol.verify(rewriteContent { root ->
            root.add("votes", JsonArray().apply { repeat(33) { add(root["votes"].asJsonArray[0].deepCopy()) } })
        }, now))
        assertNull(CommunityProtocol.verify(rewriteContent { root ->
            root.add("lists", JsonArray().apply { repeat(9) { add(root["lists"].asJsonArray[0].deepCopy()) } })
        }, now))
    }

    @Test fun cannotSignVotesAttributedToAnotherPublicKey() {
        assertThrows(IllegalArgumentException::class.java) { signed(CommunityBundle(listOf(vote().copy(authorId = "f".repeat(64))))) }
    }

    @Test fun listOfferRequiresBoundedHttpsPublicHostnameAndFreshExplicitTimestamp() {
        assertTrue(CommunityProtocol.validOffer(offer(), now))
        listOf("http://catalog.example.net/list", "https://user:password@catalog.example.net/list", "https://catalog.example.net/list#secret",
            "https://catalog.example.net:8443/list", "https://127.0.0.1/list", "https://192.168.0.1/list", "https://[::1]/list",
            "https://localhost./list", "https://private.lan/list", "https://private.home/list", "https://private.local/list",
            "https://catalog.example.net/\nsecret", "not a URL")
            .forEach { assertFalse(CommunityProtocol.validOffer(offer().copy(url = it), now), it) }
        assertFalse(CommunityProtocol.validOffer(offer().copy(name = " "), now))
        assertFalse(CommunityProtocol.validOffer(offer().copy(name = "x".repeat(61)), now))
        assertFalse(CommunityProtocol.validOffer(offer().copy(sharedAtMillis = now + 1), now))
        assertFalse(CommunityProtocol.validOffer(offer().copy(sharedAtMillis = now - CommunityProtocol.TTL_MILLIS - 1), now))
    }

    @Test fun urlSyntaxCannotInferWhetherAPathOrQueryContainsAPrivateToken() {
        // Validity is not disclosure consent: the UI must show the whole URL before explicit sharing.
        assertTrue(CommunityProtocol.validOffer(offer().copy(url = "https://catalog.example.net/private-token?key=secret"), now))
    }

    private fun rewriteContent(change: (JsonObject) -> Unit): String = rewrite { event ->
        val content = JsonParser.parseString(event["content"].asString).asJsonObject
        change(content)
        event.addProperty("content", gson.toJson(content))
    }

    /** Sign altered fixtures so tests reach schema validation instead of merely rejecting a bad hash. */
    private fun rewrite(change: (JsonObject) -> Unit): String {
        val event = JsonParser.parseString(signed()).asJsonObject
        change(event)
        val canonical = JsonArray().apply {
            add(0); add(event["pubkey"]); add(event["created_at"]); add(event["kind"]); add(event["tags"]); add(event["content"])
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(gson.toJson(canonical).toByteArray(Charsets.UTF_8))
        event.addProperty("id", hash.toCommunityHex())
        event.addProperty("sig", signer.signHash32(hash).toCommunityHex())
        return gson.toJson(event)
    }
}
