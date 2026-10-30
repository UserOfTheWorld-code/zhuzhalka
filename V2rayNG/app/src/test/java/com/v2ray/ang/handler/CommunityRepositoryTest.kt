package com.v2ray.ang.handler

import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CommunityRepositoryTest {
    @Test fun defaultOptOutDoesNotCreateIdentityWriteOrExchangeAnything() = runBlocking {
        val scenario = Scenario()
        assertEquals(CommunitySnapshot(), scenario.repository.snapshot())
        scenario.repository.record(scenario.vote())
        assertEquals(CommunityExchange(emptyList(), 0, 0), scenario.repository.sync())
        assertEquals(CommunitySnapshot(), scenario.repository.stopSharing())
        assertEquals(0, scenario.signerCalls)
        assertEquals(0, scenario.exchangeCalls)
        assertEquals(0, scenario.storage.writes)
    }

    @Test fun explicitEnablePersistsButDoesNotPublishUntilSync() = runBlocking {
        val scenario = Scenario()
        assertTrue(scenario.repository.setEnabled(true).enabled)
        assertEquals(1, scenario.signerCalls)
        assertEquals(0, scenario.exchangeCalls)
        assertTrue(scenario.reopen().snapshot().enabled)
        assertEquals(1, scenario.signerCalls)
        assertTrue(scenario.repository.snapshot().ownOffers.isEmpty())
    }

    @Test fun failedIdentityCreationDoesNotPersistConsent() {
        val storage = MemoryStorage()
        val repository = CommunityRepository(storage, { throw IllegalStateException("identity failure") },
            { _, _ -> error("No network expected") })
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.setEnabled(true) } }
        assertNull(storage.record)
        assertEquals(0, storage.writes)
    }

    @Test fun recordUsesOwnAuthenticatedAuthorAndSyncPublishesOnlyRecordedVotes() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote().copy(authorId = "f".repeat(64)))
        scenario.repository.sync()
        val outgoing = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        assertEquals(scenario.identity.publicKeyHex(), outgoing.author)
        assertEquals(scenario.identity.publicKeyHex(), outgoing.bundle.votes.single().authorId)
        assertTrue(outgoing.bundle.offers.isEmpty())
    }

    @Test fun latestOwnVoteReplacesPreviousWithoutDuplicatingEndpoint() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote())
        scenario.now += 1000
        scenario.repository.record(scenario.vote().copy(passed = false, latencyBucketMillis = null))
        scenario.repository.sync()
        val votes = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!.bundle.votes
        assertEquals(1, votes.size)
        assertFalse(votes.single().passed)
    }

    @Test fun authenticatedPeerCacheUsesLatestAuthorEventAndNeverCountsOwnEvent() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        val older = scenario.peer(4, votes = listOf(scenario.vote()), at = scenario.now - 1000)
        val newer = scenario.peer(4, votes = listOf(scenario.vote(2)))
        val second = scenario.peer(5, votes = listOf(scenario.vote(2)))
        val self = scenario.peer(3, votes = listOf(scenario.vote(9)))
        scenario.exchangeResult = CommunityExchange(listOf(older, newer, second, self), 2, 0)
        scenario.repository.sync()
        val snapshot = scenario.repository.snapshot()
        assertEquals(2, snapshot.peerCount)
        assertEquals(2, snapshot.votes.size)
        assertTrue(snapshot.votes.all { it.key == scenario.vote(2).key })
        assertEquals(snapshot, scenario.reopen().snapshot())
    }

    @Test fun emptyRelayResponsePreservesPreviouslyVerifiedFreshCache() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.exchangeResult = CommunityExchange(listOf(scenario.peer(4)), 1, 0)
        scenario.repository.sync()
        val previous = scenario.repository.snapshot()
        scenario.exchangeResult = CommunityExchange(emptyList(), 0, 0)
        assertEquals(scenario.exchangeResult, scenario.repository.sync())
        assertEquals(previous, scenario.repository.snapshot())
    }

    @Test fun expiredPeerCacheAndOwnOutboxAreNotReused() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote())
        scenario.exchangeResult = CommunityExchange(listOf(scenario.peer(4)), 1, 0)
        scenario.repository.sync()
        scenario.now += CommunityProtocol.TTL_MILLIS + 1000
        assertTrue(scenario.repository.snapshot().votes.isEmpty())
        assertEquals(0, scenario.repository.snapshot().peerCount)
        scenario.exchangeResult = CommunityExchange(emptyList(), 1, 0)
        scenario.repository.sync()
        assertNull(scenario.outgoing.last())
    }

    @Test fun expiredSiblingVoteDoesNotDiscardFreshOwnOutboxOrPeerCache() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        val olderAt = scenario.now - CommunityProtocol.TTL_MILLIS + 500
        scenario.repository.record(scenario.vote(1).copy(observedAtMillis = olderAt))
        scenario.repository.record(scenario.vote(2))
        val peerSigner = CommunityCrypto.createSigner(ByteArray(32).also { it[31] = 4 })
        val votes = listOf(scenario.vote(1).copy(observedAtMillis = olderAt), scenario.vote(2))
            .map { it.copy(authorId = peerSigner.publicKeyHex()) }
        val peer = CommunityProtocol.verify(CommunityProtocol.sign(CommunityBundle(votes), peerSigner, scenario.now), scenario.now)!!
        scenario.exchangeResult = CommunityExchange(listOf(peer), 1, 0)
        scenario.repository.sync()
        scenario.now += 1000
        assertEquals(listOf(scenario.vote(2).key), scenario.repository.snapshot().votes.map { it.key })
        scenario.exchangeResult = CommunityExchange(emptyList(), 1, 0)
        scenario.repository.sync()
        val own = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        assertEquals(listOf(scenario.vote(2).key), own.bundle.votes.map { it.key })
    }

    @Test fun offersArePublishedOnlyAfterExplicitShareAndStopClearsFutureOutbox() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote())
        assertTrue(scenario.repository.snapshot().ownOffers.isEmpty())
        val shared = scenario.repository.share(scenario.offer())
        assertEquals(listOf(scenario.offer()), shared.ownOffers)
        scenario.repository.sync()
        assertEquals(listOf(scenario.offer()), CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!.bundle.offers)
        scenario.now += 1000
        assertTrue(scenario.repository.stopSharing().ownOffers.isEmpty())
        scenario.repository.sync()
        val withdrawal = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        assertTrue(withdrawal.bundle.offers.isEmpty())
        assertEquals(1, withdrawal.bundle.votes.size)
    }

    @Test fun shareRequiresOptInAndRejectsInvalidOfferWithoutChangingState() {
        val scenario = Scenario()
        assertThrows(IllegalStateException::class.java) { runBlocking { scenario.repository.share(scenario.offer()) } }
        assertEquals(0, scenario.signerCalls)
        runBlocking { scenario.repository.setEnabled(true) }
        val before = scenario.storage.record
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { scenario.repository.share(scenario.offer().copy(url = "https://localhost./private")) }
        }
        assertEquals(before, scenario.storage.record)
        assertEquals(0, scenario.exchangeCalls)
    }

    @Test fun peerOffersAreDeduplicatedAndNeverSilentlyBecomeOwnOffers() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.exchangeResult = CommunityExchange(listOf(scenario.peer(4, offers = listOf(scenario.offer())),
            scenario.peer(5, offers = listOf(scenario.offer()))), 1, 0)
        scenario.repository.sync()
        val snapshot = scenario.repository.snapshot()
        assertEquals(listOf(scenario.offer()), snapshot.offers)
        assertTrue(snapshot.ownOffers.isEmpty())
        assertNull(scenario.outgoing.single())
    }

    @Test fun disablingDuringExchangePreventsLateCachePublicationAndFurtherNetwork() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario.onExchange = { _, _ ->
            entered.complete(Unit)
            release.await()
            CommunityExchange(listOf(scenario.peer(4)), 1, 0)
        }
        val syncing = async { scenario.repository.sync() }
        entered.await()
        assertFalse(scenario.repository.setEnabled(false).enabled)
        val afterDisable = scenario.storage.record
        release.complete(Unit)
        syncing.await()
        assertEquals(afterDisable, scenario.storage.record)
        assertEquals(CommunitySnapshot(), scenario.repository.snapshot())
        scenario.repository.sync()
        assertEquals(1, scenario.exchangeCalls)
    }

    @Test fun disableThenReenableStillRejectsResultFromPreviousGeneration() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario.onExchange = { _, _ -> entered.complete(Unit); release.await(); CommunityExchange(listOf(scenario.peer(4)), 1, 0) }
        val syncing = async { scenario.repository.sync() }
        entered.await()
        scenario.repository.setEnabled(false)
        scenario.repository.setEnabled(true)
        release.complete(Unit)
        syncing.await()
        assertTrue(scenario.repository.snapshot().enabled)
        assertEquals(0, scenario.repository.snapshot().peerCount)
    }

    @Test fun recordDuringExchangeIsPreservedWhenPeerCacheIsSaved() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote(1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario.onExchange = { _, _ -> entered.complete(Unit); release.await(); CommunityExchange(listOf(scenario.peer(4)), 1, 0) }
        val syncing = async { scenario.repository.sync() }
        entered.await()
        scenario.now += 1000
        scenario.repository.record(scenario.vote(2))
        release.complete(Unit)
        syncing.await()
        val ownJson = JsonParser.parseString(scenario.storage.record).asJsonObject["own"].asString
        assertEquals(setOf(scenario.vote(1).key, scenario.vote(2).key),
            CommunityProtocol.verify(ownJson, scenario.now)!!.bundle.votes.map { it.key }.toSet())
        assertEquals(1, scenario.repository.snapshot().peerCount)
    }

    @Test fun storageFailurePreservesPriorStateAndReadbackFailureIsReported() {
        val scenario = Scenario()
        runBlocking { scenario.repository.setEnabled(true) }
        val before = scenario.storage.record
        scenario.storage.failWrites = true
        assertThrows(IllegalStateException::class.java) { runBlocking { scenario.repository.record(scenario.vote()) } }
        assertEquals(before, scenario.storage.record)
        scenario.storage.failWrites = false
        scenario.storage.ignoreWrites = true
        assertThrows(IllegalStateException::class.java) { runBlocking { scenario.repository.setEnabled(false) } }
        assertEquals(before, scenario.storage.record)
    }

    @Test fun sameSecondLocalUpdatesRemainImmediateButSyncPublishesANewerSecond() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote(1))
        scenario.repository.sync()
        val first = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        scenario.repository.record(scenario.vote(2))
        assertTrue(scenario.pauses.isEmpty())
        scenario.repository.sync()
        val next = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        assertEquals(first.createdAtSeconds + 1, next.createdAtSeconds)
        assertEquals(2, next.bundle.votes.size)
        assertEquals(listOf(1000L), scenario.pauses)
    }

    @Test fun publicationAttemptIsPersistedEvenWhenAckIsLostAndSurvivesRecreation() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.share(scenario.offer())
        scenario.repository.sync()
        val first = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        val reopened = scenario.reopen()
        reopened.stopSharing()
        reopened.sync()
        val withdrawal = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        assertTrue(withdrawal.createdAtSeconds > first.createdAtSeconds)
        assertTrue(withdrawal.bundle.offers.isEmpty())
        assertEquals(listOf(1000L), scenario.pauses)
    }

    @Test fun precedingFourFieldStorageFormatReadsAndMigratesOnNextWrite() = runBlocking {
        val scenario = Scenario()
        scenario.storage.record = "{\"version\":1,\"enabled\":true,\"own\":null,\"peers\":[]}"
        assertTrue(scenario.repository.snapshot().enabled)
        assertEquals(0, scenario.signerCalls)
        scenario.repository.record(scenario.vote())
        assertEquals(0L, JsonParser.parseString(scenario.storage.record).asJsonObject["lastPublishedAtSeconds"].asLong)
        scenario.repository.sync()
        assertEquals(scenario.now / 1000, JsonParser.parseString(scenario.storage.record).asJsonObject["lastPublishedAtSeconds"].asLong)
    }

    @Test fun clockRollbackDoesNotWaitLongOrPublishFutureTimestamp() {
        val scenario = Scenario()
        runBlocking {
            scenario.repository.setEnabled(true)
            scenario.repository.record(scenario.vote())
            scenario.repository.sync()
        }
        scenario.now -= 2000L
        val error = assertThrows(IllegalStateException::class.java) { runBlocking { scenario.repository.sync() } }
        assertEquals("Community publication clock is not ready", error.message)
        assertTrue(scenario.pauses.isEmpty())
        assertEquals(1, scenario.exchangeCalls)
    }

    @Test fun failedAttemptPersistencePreventsAnyPublication() {
        val scenario = Scenario()
        runBlocking { scenario.repository.setEnabled(true); scenario.repository.record(scenario.vote()) }
        val before = scenario.storage.record
        scenario.storage.failWrites = true
        assertThrows(IllegalStateException::class.java) { runBlocking { scenario.repository.sync() } }
        assertEquals(before, scenario.storage.record)
        assertEquals(0, scenario.exchangeCalls)
    }

    @Test fun disablingDuringTimestampWaitPreventsStartingNetwork() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote())
        scenario.repository.sync()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario.onPause = { entered.complete(Unit); release.await() }
        val syncing = async { scenario.repository.sync() }
        entered.await()
        scenario.repository.setEnabled(false)
        release.complete(Unit)
        syncing.await()
        assertEquals(1, scenario.exchangeCalls)
        assertFalse(scenario.repository.snapshot().enabled)
    }

    @Test fun timestampWaitReReadsLatestOutboxBeforeSigning() = runBlocking {
        val scenario = Scenario()
        scenario.repository.setEnabled(true)
        scenario.repository.record(scenario.vote(1))
        scenario.repository.sync()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        scenario.onPause = { entered.complete(Unit); release.await() }
        val syncing = async { scenario.repository.sync() }
        entered.await()
        scenario.repository.record(scenario.vote(2))
        release.complete(Unit)
        syncing.await()
        val published = CommunityProtocol.verify(checkNotNull(scenario.outgoing.last()), scenario.now)!!
        assertEquals(setOf(scenario.vote(1).key, scenario.vote(2).key), published.bundle.votes.map { it.key }.toSet())
    }

    @Test fun corruptConsentRecordFailsClosedWithoutIdentityOrNetwork() {
        val scenario = Scenario()
        for (raw in listOf("{", "{}", "{\"version\":1,\"enabled\":\"true\",\"own\":null,\"peers\":[]}",
            "{\"version\":1,\"enabled\":false,\"enabled\":true,\"own\":null,\"peers\":[]}")) {
            scenario.storage.record = raw
            assertThrows(IllegalArgumentException::class.java) { runBlocking { scenario.repository.sync() } }
        }
        assertEquals(0, scenario.signerCalls)
        assertEquals(0, scenario.exchangeCalls)
        assertEquals(0, scenario.storage.writes)
    }

    private class Scenario {
        var now = 1_700_000_000_000L
        val identity by lazy { CommunityCrypto.createSigner(ByteArray(32).also { it[31] = 3 }) }
        val storage = MemoryStorage()
        var signerCalls = 0
        var exchangeCalls = 0
        val pauses = mutableListOf<Long>()
        val outgoing = mutableListOf<String?>()
        var exchangeResult = CommunityExchange(emptyList(), 0, 0)
        var onExchange: (suspend (String?, Long) -> CommunityExchange)? = null
        var onPause: (suspend (Long) -> Unit)? = null
        val repository = reopen()
        fun reopen() = CommunityRepository(storage, { signerCalls++; identity }, { event, at ->
            exchangeCalls++
            outgoing += event
            onExchange?.invoke(event, at) ?: exchangeResult
        }, { now }, { millis -> pauses += millis; onPause?.invoke(millis); now += millis })
        fun vote(index: Int = 1) = CommunityVote(CommunityEndpointKey("au1rxx", index.toString(16).padStart(64, '0')),
            "0".repeat(64), now, true, 500)
        fun offer() = CommunityListOffer("Public list", "https://catalog.example.net/list.txt", now)
        fun peer(secret: Int, votes: List<CommunityVote> = listOf(vote()), offers: List<CommunityListOffer> = emptyList(), at: Long = now): VerifiedCommunityEvent {
            val signer = CommunityCrypto.createSigner(ByteArray(32).also { it[31] = secret.toByte() })
            val bundle = CommunityBundle(votes.map { it.copy(authorId = signer.publicKeyHex(), observedAtMillis = at) }, offers)
            return checkNotNull(CommunityProtocol.verify(CommunityProtocol.sign(bundle, signer, at), at))
        }
    }

    /** Models MmkvManager's atomic checked write: injected write failures leave the previous record. */
    private class MemoryStorage : CommunityStorage {
        var record: String? = null
        var writes = 0
        var failWrites = false
        var ignoreWrites = false
        override fun read() = record
        override fun write(record: String) {
            if (failWrites) throw IllegalStateException("injected storage failure")
            writes++
            if (!ignoreWrites) this.record = record
        }
    }
}
