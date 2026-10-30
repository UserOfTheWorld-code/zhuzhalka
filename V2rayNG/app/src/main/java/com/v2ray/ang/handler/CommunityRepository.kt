package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class CommunitySnapshot(
    val enabled: Boolean = false,
    val votes: List<CommunityVote> = emptyList(),
    val offers: List<CommunityListOffer> = emptyList(),
    val ownOffers: List<CommunityListOffer> = emptyList(),
    val peerCount: Int = 0,
)

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
) {
    private data class State(val enabled: Boolean, val own: String?, val peers: List<String>, val lastPublishedAtSeconds: Long = 0L)
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private var generation = 0L

    suspend fun snapshot(): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock { summarize(read(), clock()) }
    }

    suspend fun setEnabled(enabled: Boolean): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val previous = read()
            // This is the only opt-in path; reading the cache never creates an identity.
            if (enabled) signer()
            write(previous.copy(enabled = enabled))
            generation++
            summarize(read(), clock())
        }
    }

    suspend fun record(vote: CommunityVote) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = read()
            if (!state.enabled) return@withLock
            val now = clock()
            val identity = signer()
            val own = state.own?.let { CommunityProtocol.verify(it, now) }?.bundle ?: CommunityBundle()
            val votes = CommunityRanking.retainVotes(
                listOf(vote.copy(authorId = identity.publicKeyHex())) + own.votes.filterNot { it.key == vote.key }, now,
            ).take(CommunityProtocol.MAX_VOTES)
            val event = CommunityProtocol.sign(CommunityBundle(votes, own.offers), identity, now)
            write(state.copy(own = event))
        }
    }

    /** Explicit per-list publishing action. Its caller must show the full public-sharing disclosure. */
    suspend fun share(offer: CommunityListOffer): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = read()
            check(state.enabled)
            val now = clock()
            require(CommunityProtocol.validOffer(offer, now))
            val identity = signer()
            val own = state.own?.let { CommunityProtocol.verify(it, now) }?.bundle ?: CommunityBundle()
            val offers = (listOf(offer) + own.offers.filterNot { it.url == offer.url }).take(CommunityProtocol.MAX_OFFERS)
            write(state.copy(own = CommunityProtocol.sign(own.copy(offers = offers), identity, now)))
            summarize(read(), now)
        }
    }

    suspend fun stopSharing(): CommunitySnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = read()
            if (state.enabled) {
                val now = clock()
                val own = state.own?.let { CommunityProtocol.verify(it, now) }?.bundle ?: CommunityBundle()
                write(state.copy(own = CommunityProtocol.sign(own.copy(offers = emptyList()), signer(), now)))
            }
            summarize(read(), clock())
        }
    }

    suspend fun sync(): CommunityExchange = withContext(Dispatchers.IO) {
        syncMutex.withLock sync@{
            val (starting, revision) = mutex.withLock { read() to generation }
            if (!starting.enabled) return@sync CommunityExchange(emptyList(), 0, 0)
            // NIP-01 replacement uses whole seconds. Only explicit sync may wait; recording probe
            // results remains immediate. Persist attempts before sending because relay ACKs can be lost.
            if (starting.own != null) {
                val now = clock()
                check(now > 0L && starting.lastPublishedAtSeconds < Long.MAX_VALUE / 1000 - 1) {
                    "Community publication clock is not ready"
                }
                val waitMillis = ((starting.lastPublishedAtSeconds + 1) * 1000 - now).coerceAtLeast(0L)
                check(waitMillis <= 1000L) { "Community publication clock is not ready" }
                if (waitMillis > 0L) pause(waitMillis)
            }
            val prepared = mutex.withLock {
                val current = read()
                if (!current.enabled || revision != generation) return@withLock null
                val now = clock()
                val own = current.own?.let { CommunityProtocol.verify(it, now) }
                val outgoing = own?.let {
                    check(now / 1000 > current.lastPublishedAtSeconds) { "Community publication clock is not ready" }
                    CommunityProtocol.sign(it.bundle, signer(), now).also { event ->
                        write(current.copy(own = event, lastPublishedAtSeconds = now / 1000))
                    }
                }
                outgoing to now
            } ?: return@sync CommunityExchange(emptyList(), 0, 0)
            val result = exchange(prepared.first, prepared.second)
            mutex.withLock {
                val current = read()
                if (current.enabled && revision == generation) {
                    val existing = current.peers.mapNotNull { CommunityProtocol.verify(it, clock()) }
                    val self = signer().publicKeyHex()
                    val peers = (existing + result.events).filter { it.author != self }
                        .sortedWith(compareByDescending<VerifiedCommunityEvent> { it.createdAtSeconds }.thenBy { it.id })
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
        val own = state.own?.let { CommunityProtocol.verify(it, now) }
        return CommunitySnapshot(true,
            CommunityRanking.retainVotes(peers.flatMap { it.bundle.votes }, now),
            peers.flatMap { it.bundle.offers }.distinctBy { it.url }.take(24),
            own?.bundle?.offers.orEmpty(), peers.size)
    }

    private fun read(): State {
        val raw = storage.read() ?: return State(false, null, emptyList())
        // Corruption fails closed; it must never silently opt an installation into public sharing.
        require(raw.length <= 5_000_000)
        val root = requireNotNull(CommunityJson.parse(raw, 5_000_000)).asJsonObject
        require(root.keySet() == setOf("version", "enabled", "own", "peers") ||
            root.keySet() == setOf("version", "enabled", "own", "peers", "lastPublishedAtSeconds"))
        require(root["version"].asJsonPrimitive.isNumber && root["version"].asString == "1")
        require(root["enabled"].asJsonPrimitive.isBoolean)
        val own = root["own"].let { if (it.isJsonNull) null else it.asString }
        require(own == null || own.length <= CommunityProtocol.MAX_EVENT_CHARS)
        val peers = root.getAsJsonArray("peers")
        require(peers.size() <= 128)
        val lastPublished = root["lastPublishedAtSeconds"]?.asJsonPrimitive?.let {
            require(it.isNumber && it.asString.matches(Regex("[0-9]{1,16}")))
            it.asLong
        } ?: 0L
        return State(root["enabled"].asBoolean, own, peers.map {
            it.asString.also { value -> require(value.length <= CommunityProtocol.MAX_EVENT_CHARS) }
        }, lastPublished)
    }

    private fun write(state: State) {
        storage.write(JsonObject().apply {
            addProperty("version", 1); addProperty("enabled", state.enabled); addProperty("own", state.own)
            addProperty("lastPublishedAtSeconds", state.lastPublishedAtSeconds)
            add("peers", JsonArray().apply { state.peers.forEach(::add) })
        }.toString())
        check(read() == state) { "Community state readback failed" }
    }

    companion object {
        fun android(): CommunityRepository {
            val relay = CommunityRelay()
            return CommunityRepository(object : CommunityStorage {
                override fun read() = MmkvManager.decodeCommunityStateRecord()
                override fun write(record: String) = MmkvManager.encodeCommunityStateRecord(record)
            }, { CommunityIdentity.getOrCreateSigner() }, relay::exchange)
        }
    }
}
