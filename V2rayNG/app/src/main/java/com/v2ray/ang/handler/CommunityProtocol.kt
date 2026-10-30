package com.v2ray.ang.handler

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URI
import java.security.MessageDigest

/** Public, explicitly shared URL. Never construct this from the whole subscription database. */
internal data class CommunityListOffer(val name: String, val url: String, val sharedAtMillis: Long)

internal data class CommunityBundle(
    val votes: List<CommunityVote> = emptyList(),
    val offers: List<CommunityListOffer> = emptyList(),
)

internal data class VerifiedCommunityEvent(
    val id: String,
    val author: String,
    val createdAtSeconds: Long,
    val bundle: CommunityBundle,
    val json: String,
)

/** NIP-01 adapter. Experimental addressable kind is isolated here, not a persisted domain contract. */
internal object CommunityProtocol {
    const val KIND = 39799
    const val NAMESPACE = "zhuzhalka-community-v1"
    const val MAX_EVENT_CHARS = 32_768
    const val MAX_VOTES = 32
    const val MAX_OFFERS = 8
    const val TTL_MILLIS = 3_600_000L
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val hex64 = Regex("[0-9a-f]{64}")
    private val hex128 = Regex("[0-9a-f]{128}")

    fun validOffer(offer: CommunityListOffer, nowMillis: Long): Boolean = runCatching {
        val uri = URI(offer.url)
        val host = uri.host.orEmpty()
        offer.name.length in 1..60 && offer.name.isNotBlank() && offer.name.none { it.isISOControl() } &&
            offer.url.length in 1..2048 && offer.url.none { it.isISOControl() } &&
            uri.scheme == "https" && uri.rawUserInfo == null && uri.fragment == null &&
            uri.port in listOf(-1, 443) && host.contains('.') &&
            host.matches(Regex("[A-Za-z0-9.-]+")) && host.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            CommunityRanking.normalizePublicHost(host) != null &&
            offer.sharedAtMillis > 0 && offer.sharedAtMillis <= nowMillis &&
            nowMillis - offer.sharedAtMillis <= TTL_MILLIS
    }.getOrDefault(false)

    fun sign(bundle: CommunityBundle, signer: CommunitySigner, nowMillis: Long): String {
        val content = encodeBundle(bundle, signer.publicKeyHex(), nowMillis)
        val tags = JsonArray().apply {
            add(JsonArray().apply { add("d"); add(NAMESPACE) })
            add(JsonArray().apply { add("t"); add(NAMESPACE) })
        }
        val seconds = nowMillis / 1000
        val publicKey = signer.publicKeyHex()
        require(hex64.matches(publicKey))
        val digest = digest(publicKey, seconds, tags, content)
        return gson.toJson(JsonObject().apply {
            addProperty("id", digest.toHex())
            addProperty("pubkey", publicKey)
            addProperty("created_at", seconds)
            addProperty("kind", KIND)
            add("tags", tags)
            addProperty("content", content)
            addProperty("sig", signer.signHash32(digest).toHex())
        }).also { require(it.length <= MAX_EVENT_CHARS) }
    }

    /** Returns no partially trusted payload. Malformed input is never passed to the logging JSON helper. */
    fun verify(json: String, nowMillis: Long): VerifiedCommunityEvent? = runCatching {
        require(json.length in 1..MAX_EVENT_CHARS)
        val event = requireNotNull(CommunityJson.parse(json, MAX_EVENT_CHARS)).asJsonObject
        require(event.keySet() == setOf("id", "pubkey", "created_at", "kind", "tags", "content", "sig"))
        val id = event.string("id")
        val author = event.string("pubkey")
        val signature = event.string("sig")
        require(hex64.matches(id) && hex64.matches(author) && hex128.matches(signature))
        require(event.integer("kind") == KIND.toLong())
        val createdAt = event.integer("created_at")
        require(createdAt > 0 && createdAt <= nowMillis / 1000 && nowMillis / 1000 - createdAt <= TTL_MILLIS / 1000)
        val tags = event.getAsJsonArray("tags")
        require(tags.size() == 2)
        require(tags[0].asJsonArray.toList().map { it.asString } == listOf("d", NAMESPACE))
        require(tags[1].asJsonArray.toList().map { it.asString } == listOf("t", NAMESPACE))
        val content = event.string("content")
        require(content.length <= 24_000)
        val digest = digest(author, createdAt, tags, content)
        require(digest.toHex() == id)
        require(CommunityCrypto.verifySignature(digest, author.fromHex(), signature.fromHex()))
        val bundle = decodeBundle(content, author, nowMillis)
        VerifiedCommunityEvent(id, author, createdAt, bundle, json)
    }.getOrNull()

    private fun digest(author: String, seconds: Long, tags: JsonArray, content: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(gson.toJson(JsonArray().apply {
            add(0); add(author); add(seconds); add(KIND); add(tags); add(content)
        }).toByteArray(Charsets.UTF_8))

    private fun encodeBundle(bundle: CommunityBundle, author: String, nowMillis: Long): String {
        require(bundle.votes.size <= MAX_VOTES && bundle.offers.size <= MAX_OFFERS)
        require(bundle.offers.distinctBy { it.url }.size == bundle.offers.size)
        require(CommunityRanking.retainVotes(bundle.votes, nowMillis).size == bundle.votes.size)
        require(bundle.votes.all { it.authorId == author } && bundle.offers.all { validOffer(it, nowMillis) })
        return gson.toJson(JsonObject().apply {
            addProperty("version", 1)
            add("votes", JsonArray().apply { bundle.votes.forEach { vote ->
                add(JsonObject().apply {
                    addProperty("source", vote.key.sourceId); addProperty("endpoint", vote.key.endpointHash)
                    addProperty("at", vote.observedAtMillis); addProperty("passed", vote.passed)
                    vote.latencyBucketMillis?.let { addProperty("latency", it) }
                })
            } })
            add("lists", JsonArray().apply { bundle.offers.forEach { offer ->
                add(JsonObject().apply {
                    addProperty("name", offer.name); addProperty("url", offer.url); addProperty("at", offer.sharedAtMillis)
                })
            } })
        })
    }

    private fun decodeBundle(content: String, author: String, nowMillis: Long): CommunityBundle {
        val root = requireNotNull(CommunityJson.parse(content, 24_000)).asJsonObject
        require(root.keySet() == setOf("version", "votes", "lists") && root.integer("version") == 1L)
        val votes = root.getAsJsonArray("votes")
        val offers = root.getAsJsonArray("lists")
        require(votes.size() <= MAX_VOTES && offers.size() <= MAX_OFFERS)
        val decodedVotes = votes.map { element ->
            val v = element.asJsonObject
            require(v.keySet() == setOf("source", "endpoint", "at", "passed") ||
                v.keySet() == setOf("source", "endpoint", "at", "passed", "latency"))
            require(v["passed"].asJsonPrimitive.isBoolean)
            CommunityVote(CommunityEndpointKey(v.string("source"), v.string("endpoint")), author,
                v.integer("at"), v["passed"].asBoolean, v["latency"]?.let {
                    val value = v.integer("latency")
                    require(value in 0..60_000)
                    value.toInt()
                })
        }
        // Validate every signed entry before pruning time-expired hints. One old vote must not
        // discard newer votes in the same still-current event, but malformed/future data is invalid.
        require(decodedVotes.all { vote -> vote.observedAtMillis <= nowMillis &&
            CommunityRanking.retainVotes(listOf(vote), vote.observedAtMillis).size == 1 })
        require(decodedVotes.distinctBy { it.key.sourceId to it.key.endpointHash.lowercase(java.util.Locale.ROOT) }.size == decodedVotes.size)
        val decodedOffers = offers.map {
            val o = it.asJsonObject
            require(o.keySet() == setOf("name", "url", "at"))
            CommunityListOffer(o.string("name"), o.string("url"), o.integer("at")).also { offer ->
                require(offer.sharedAtMillis <= nowMillis && validOffer(offer, offer.sharedAtMillis))
            }
        }
        require(decodedOffers.distinctBy { it.url }.size == decodedOffers.size)
        return CommunityBundle(CommunityRanking.retainVotes(decodedVotes, nowMillis),
            decodedOffers.filter { nowMillis - it.sharedAtMillis <= TTL_MILLIS })
    }

    private fun JsonObject.string(name: String): String = get(name).asJsonPrimitive.let {
        require(it.isString); it.asString
    }
    private fun JsonObject.integer(name: String): Long = get(name).asJsonPrimitive.let {
        require(it.isNumber && it.asString.matches(Regex("[0-9]{1,16}"))); it.asLong
    }
    internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    internal fun String.fromHex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
