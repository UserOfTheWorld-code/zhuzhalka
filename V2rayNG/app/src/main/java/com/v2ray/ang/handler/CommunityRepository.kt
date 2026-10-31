package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Local approval, never serialized into a public event. The exact URL must remain unchanged. */
internal data class CommunityApprovedSource(
    val groupId: String,
    val name: String,
    val url: String,
    val approvedAtMillis: Long,
    val publish: Boolean,
)

internal data class CommunitySnapshot(
    val enabled: Boolean = false,
    val votes: List<CommunityVote> = emptyList(),
    val offers: List<CommunityListOffer> = emptyList(),
    val ownOffers: List<CommunityListOffer> = emptyList(),
    val peerCount: Int = 0,
    val approvedSources: List<CommunityApprovedSource> = emptyList(),
) {
    val approvedPublicUrls: Set<String> get() = approvedSources.map { it.url }.toSet()
}

internal interface CommunityStorage {
    fun read(): String?
    fun write(record: String)
}

/** Owns sharing consent, signed cache and outbox. The ViewModel owns network-operation lifetime. */
internal class CommunityRepository(
    private val storage: CommunityStorage,
    private val signer: () -> CommunitySigner,
    private val exchange: suspend (String?, Long) -> CommunityExchange,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val subscriptions: () -> List<SubscriptionCache> = { emptyList() },
) {
    private data class State(
        val enabled: Boolean, val own: String?, val peers: List<String>, val lastPublishedAtSeconds: Long = 0L,
        val approved: List<CommunityApprovedSource> = emptyList(),
        val legacyOffers: List<CommunityListOffer> = emptyList(),
        val pendingPublication: Boolean = false,
    )
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private var generation = 0L

    suspend fun snapshot(): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { summarize(reconcile(read()), clock()) }
    }

    suspend fun setEnabled(enabled: Boolean): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = reconcile(read())
            // This is the only opt-in path; reading the cache never creates an identity.
            if (enabled) signer()
            write(previous.copy(enabled = enabled))
            generation++
            summarize(read(), clock())
        }
    }

    suspend fun record(vote: CommunityVote) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = reconcile(read())
            if (!state.enabled) return@withLock
            val now = clock()
            require(CommunityRanking.validSourceId(vote.key.sourceId, false) ||
                state.approved.any { CommunityRanking.sourceIdForUrl(it.url, setOf(it.url)) == vote.key.sourceId }) {
                "Unapproved community source"
            }
            val identity = signer()
            val own = state.own?.let { CommunityProtocol.verify(it, now) }?.bundle ?: CommunityBundle()
            val votes = CommunityRanking.retainVotes(
                listOf(vote.copy(authorId = identity.publicKeyHex())) + own.votes.filterNot { it.key == vote.key }, now,
            ).take(CommunityProtocol.MAX_VOTES)
            val event = CommunityProtocol.sign(CommunityBundle(votes, publicationOffers(state, now)), identity, now)
            write(state.copy(own = event))
        }
    }

    /** Requires confirmation of the complete URL. No database-wide publishing inference is allowed. */
    suspend fun share(groupId: String, offer: CommunityListOffer): CommunitySnapshot = approve(groupId, offer, true)

    /** Call only after explicit selection and successful import of the currently displayed peer offer. */
    suspend fun acceptList(groupId: String, offer: CommunityListOffer): CommunitySnapshot = approve(groupId, offer, false)

    private suspend fun approve(groupId: String, offer: CommunityListOffer, publish: Boolean): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = reconcile(read())
            check(state.enabled)
            val now = clock()
            require(CommunityProtocol.validOffer(offer, now))
            if (!publish) require(summarize(state, now).offers.any { it.url == offer.url && it.name == offer.name }) {
                "Community offer is no longer available"
            }
            val current = subscriptions().firstOrNull { it.guid == groupId }?.subscription
            require(matches(offer.url, current)) { "Public source changed before approval" }
            val previous = state.approved.firstOrNull { it.groupId == groupId && it.url == offer.url }
            val approved = CommunityApprovedSource(groupId, offer.name, offer.url, now, publish || previous?.publish == true)
            require(validApproval(approved))
            val sources = listOf(approved) + state.approved.filterNot { it.groupId == groupId }
            require(sources.size <= MAX_APPROVED && sources.filter { it.publish }.map { it.url }.distinct().size <= CommunityProtocol.MAX_OFFERS)
            val next = state.copy(approved = sources, legacyOffers = state.legacyOffers.filterNot { it.url == offer.url },
                pendingPublication = state.pendingPublication || approved.publish)
            write(next)
            generation++
            summarize(read(), now)
        }
    }

    suspend fun stopSharing(): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = reconcile(read())
            if (state.enabled) {
                write(state.copy(approved = state.approved.map { it.copy(publish = false) },
                    legacyOffers = emptyList(), pendingPublication = true))
                generation++
            }
            summarize(read(), clock())
        }
    }

    /** Revocation is durable: restoring a changed URL later never silently restores approval. */
    private fun reconcile(state: State): State {
        if (state.approved.isEmpty() && state.legacyOffers.isEmpty()) return state
        val current = subscriptions().associate { it.guid to it.subscription }
        val approved = state.approved.filter { matches(it.url, current[it.groupId]) }
        val now = clock()
        val legacy = state.legacyOffers.filter { CommunityProtocol.validOffer(it, now) && current.values.any { sub -> matches(it.url, sub) } }
        if (approved == state.approved && legacy == state.legacyOffers) return state
        val revokedPublication = state.approved.any { it.publish && it !in approved } || legacy != state.legacyOffers
        val next = state.copy(approved = approved, legacyOffers = legacy,
            pendingPublication = state.pendingPublication || revokedPublication)
        write(next)
        generation++
        return next
    }

    private fun matches(url: String, subscription: SubscriptionItem?): Boolean = subscription != null &&
        subscription.url == url && subscription.enabled && !subscription.allowInsecureUrl &&
        subscription.requestHeaders.isNullOrBlank() && subscription.userAgent.isNullOrBlank()

    private fun publicationOffers(state: State, now: Long): List<CommunityListOffer> =
        (state.approved.filter { it.publish }.map { CommunityListOffer(it.name, it.url, now) } +
            state.legacyOffers.filter { CommunityProtocol.validOffer(it, now) })
            .distinctBy { it.url }.take(CommunityProtocol.MAX_OFFERS)

    suspend fun sync(): CommunityExchange = withContext(Dispatchers.IO) {
        syncMutex.withLock sync@{
            val (starting, revision) = mutex.withLock { reconcile(read()) to generation }
            if (!starting.enabled) return@sync CommunityExchange(emptyList(), 0, 0)
            // NIP-01 replacement uses whole seconds. Only explicit sync may wait; recording probe
            // results remains immediate. Persist attempts before sending because relay ACKs can be lost.
            if (starting.own != null || starting.pendingPublication || starting.approved.any { it.publish }) {
                val now = clock()
                check(now > 0L && starting.lastPublishedAtSeconds < Long.MAX_VALUE / 1000 - 1) {
                    "Community publication clock is not ready"
                }
                val waitMillis = ((starting.lastPublishedAtSeconds + 1) * 1000 - now).coerceAtLeast(0L)
                check(waitMillis <= 1000L) { "Community publication clock is not ready" }
                if (waitMillis > 0L) pause(waitMillis)
            }
            val prepared = mutex.withLock {
                val current = reconcile(read())
                if (!current.enabled || revision != generation) return@withLock null
                val now = clock()
                val own = current.own?.let { CommunityProtocol.verify(it, now) }
                val offers = publicationOffers(current, now)
                val outgoing = if (own != null || offers.isNotEmpty() || current.pendingPublication) {
                    check(now / 1000 > current.lastPublishedAtSeconds) { "Community publication clock is not ready" }
                    val allowedIds = current.approved.mapNotNull { CommunityRanking.sourceIdForUrl(it.url, setOf(it.url)) }.toSet()
                    val votes = own?.bundle?.votes.orEmpty().filter {
                        CommunityRanking.validSourceId(it.key.sourceId, false) || it.key.sourceId in allowedIds
                    }
                    // Renew advertisements only. Observation timestamps are never changed by synchronization.
                    CommunityProtocol.sign(CommunityBundle(votes, offers), signer(), now).also { event ->
                        write(current.copy(own = event, lastPublishedAtSeconds = now / 1000, pendingPublication = false))
                    }
                } else null
                outgoing to now
            } ?: return@sync CommunityExchange(emptyList(), 0, 0)
            val result = exchange(prepared.first, prepared.second)
            mutex.withLock {
                val current = read()
                if (current.enabled && revision == generation) {
                    val existing = current.peers.mapNotNull { CommunityProtocol.verify(it, clock()) }
                    val self = signer().publicKeyHex()
                    val peers = (existing + result.events).filter { it.author != self }
                        .sortedWith(compareByDescending<VerifiedCommunityEvent> { it.createdAtSeconds }.thenByDescending { it.version }.thenBy { it.id })
                        .distinctBy { it.author }.take(128).map { it.json }
                    write(current.copy(peers = peers))
                }
            }
            result
        }
    }

    private fun summarize(state: State, now: Long): CommunitySnapshot {
        if (!state.enabled) return CommunitySnapshot()
        val peers = state.peers.mapNotNull { CommunityProtocol.verify(it, now) }
            .distinctBy { it.author }
        return CommunitySnapshot(true,
            CommunityRanking.retainVotes(peers.flatMap { it.bundle.votes }, now),
            peers.flatMap { it.bundle.offers }.distinctBy { it.url }.take(24),
            publicationOffers(state, now), peers.size, state.approved)
    }

    private fun read(): State {
        val raw = storage.read() ?: return State(false, null, emptyList())
        // Corruption fails closed; it must never silently opt an installation into public sharing.
        require(raw.length <= 5_000_000)
        val root = requireNotNull(CommunityJson.parse(raw, 5_000_000)).asJsonObject
        val version = integer(root, "version")
        val legacyKeys = setOf("version", "enabled", "own", "peers")
        require((version == 1L && (root.keySet() == legacyKeys || root.keySet() == legacyKeys + "lastPublishedAtSeconds")) ||
            (version == 2L && root.keySet() == legacyKeys + setOf("lastPublishedAtSeconds", "approved", "legacyOffers", "pendingPublication")))
        require(root["enabled"].asJsonPrimitive.isBoolean)
        val own = root["own"].let { if (it.isJsonNull) null else string(root, "own") }
        require(own == null || own.length <= CommunityProtocol.MAX_EVENT_CHARS)
        val peers = root.getAsJsonArray("peers")
        require(peers.size() <= 128)
        val lastPublished = if (root.has("lastPublishedAtSeconds")) integer(root, "lastPublishedAtSeconds") else 0L
        val approved = if (version == 1L) emptyList() else root.getAsJsonArray("approved").also { require(it.size() <= MAX_APPROVED) }.map {
            val item = it.asJsonObject
            require(item.keySet() == setOf("groupId", "name", "url", "approvedAtMillis", "publish"))
            require(item["publish"].asJsonPrimitive.isBoolean)
            CommunityApprovedSource(string(item, "groupId"), string(item, "name"), string(item, "url"),
                integer(item, "approvedAtMillis"), item["publish"].asBoolean).also { source -> require(validApproval(source)) }
        }
        require(approved.distinctBy { it.groupId }.size == approved.size)
        require(approved.filter { it.publish }.map { it.url }.distinct().size <= CommunityProtocol.MAX_OFFERS)
        // Old signed advertisements remain temporary. They do not become durable publishing consent.
        val legacyOffers = if (version == 1L) own?.let { CommunityProtocol.verify(it, clock()) }?.bundle?.offers.orEmpty()
            else root.getAsJsonArray("legacyOffers").also { require(it.size() <= CommunityProtocol.MAX_OFFERS) }.map {
                val item = it.asJsonObject
                require(item.keySet() == setOf("name", "url", "at"))
                CommunityListOffer(string(item, "name"), string(item, "url"), integer(item, "at")).also { offer ->
                    require(CommunityProtocol.validOffer(offer, offer.sharedAtMillis))
                }
            }
        require(legacyOffers.distinctBy { it.url }.size == legacyOffers.size)
        val pending = if (version == 1L) false else root["pendingPublication"].asJsonPrimitive.let { require(it.isBoolean); it.asBoolean }
        return State(root["enabled"].asBoolean, own, peers.map {
            require(it.asJsonPrimitive.isString)
            it.asString.also { value -> require(value.length <= CommunityProtocol.MAX_EVENT_CHARS) }
        }, lastPublished, approved, legacyOffers, pending)
    }

    private fun validApproval(source: CommunityApprovedSource): Boolean = source.groupId.length in 1..256 &&
        source.groupId.isNotBlank() && source.groupId.none { it.isISOControl() } && source.approvedAtMillis > 0 &&
        CommunityProtocol.validOffer(CommunityListOffer(source.name, source.url, source.approvedAtMillis), source.approvedAtMillis)

    private fun string(root: JsonObject, key: String): String = requireNotNull(root[key]).asJsonPrimitive.let { require(it.isString); it.asString }
    private fun integer(root: JsonObject, key: String): Long = requireNotNull(root[key]).asJsonPrimitive.let {
        require(it.isNumber && it.asString.matches(Regex("[0-9]{1,16}"))); it.asLong
    }

    private fun encode(state: State): String = JsonObject().apply {
        addProperty("version", 2); addProperty("enabled", state.enabled); addProperty("own", state.own)
        addProperty("lastPublishedAtSeconds", state.lastPublishedAtSeconds)
        addProperty("pendingPublication", state.pendingPublication)
        add("peers", JsonArray().apply { state.peers.forEach(::add) })
        add("approved", JsonArray().apply { state.approved.forEach { source -> add(JsonObject().apply {
            addProperty("groupId", source.groupId); addProperty("name", source.name); addProperty("url", source.url)
            addProperty("approvedAtMillis", source.approvedAtMillis); addProperty("publish", source.publish)
        }) } })
        add("legacyOffers", JsonArray().apply { state.legacyOffers.forEach { offer -> add(JsonObject().apply {
            addProperty("name", offer.name); addProperty("url", offer.url); addProperty("at", offer.sharedAtMillis)
        }) } })
    }.toString()

    private fun write(state: State) {
        val previous = storage.read()
        try {
            storage.write(encode(state))
            check(read() == state) { "Community state readback failed" }
        } catch (error: Exception) {
            // One durable record owns both approvals and outbox. Failed readback cannot leave new consent behind.
            try {
                if (storage.read() != previous) {
                    val restore = previous ?: encode(State(false, null, emptyList()))
                    storage.write(restore)
                    check(storage.read() == restore) { "Community state rollback failed" }
                }
            } catch (rollback: Exception) { error.addSuppressed(rollback) }
            throw error
        }
    }

    companion object {
        private const val MAX_APPROVED = 32
        fun android(): CommunityRepository {
            val relay = CommunityRelay()
            return CommunityRepository(object : CommunityStorage {
                override fun read() = MmkvManager.decodeCommunityStateRecord()
                override fun write(record: String) = MmkvManager.encodeCommunityStateRecord(record)
            }, { CommunityIdentity.getOrCreateSigner() }, relay::exchange, subscriptions = MmkvManager::decodeSubscriptions)
        }
    }
}
