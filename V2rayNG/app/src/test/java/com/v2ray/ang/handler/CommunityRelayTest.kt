package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class CommunityRelayTest {
    private val now = 1_800_000_000_000L
    private val servers = mutableListOf<MockWebServer>()
    private val clients = mutableListOf<OkHttpClient>()
    private val first = CommunityCrypto.createSigner(ByteArray(32).also { it[31] = 3 })
    private val second = CommunityCrypto.createSigner(ByteArray(32).also { it[31] = 7 })

    @AfterEach
    fun closeResources() {
        clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll() }
        servers.forEach { it.close() }
        clients.forEach { it.dispatcher.executorService.shutdownNow() }
    }

    @Test
    fun twoIndependentSignersPublishAndReadVerifiedEventsOverRealWebSockets() = runBlocking {
        val stored = ConcurrentHashMap<String, String>()
        val server = server()
        repeat(3) {
            server.enqueue(upgrade { socket, request ->
                when (request[0].asString) {
                    "REQ" -> {
                        val sub = request[1].asString
                        stored.values.forEach { socket.send(eventFrame(sub, it)) }
                        socket.send(frame("EOSE", sub))
                    }
                    "EVENT" -> {
                        val event = CommunityProtocol.verify(request[1].toString(), now)
                        if (event != null) {
                            stored[event.author] = event.json
                            socket.send(frame("OK", event.id, true, "stored"))
                        }
                    }
                }
            })
        }
        val a = relay(server)
        val b = relay(server)
        val firstEvent = signed(first, "11".repeat(32))
        val secondEvent = signed(second, "22".repeat(32))
        assertNotEquals(first.publicKeyHex(), second.publicKeyHex())
        val published = a.exchange(firstEvent, now)
        assertEquals(1, published.publishedRelays)
        assertTrue(published.publicationAttempted)
        val fromFirst = b.exchange(secondEvent, now)
        assertEquals(listOf(first.publicKeyHex()), fromFirst.events.map { it.author })
        assertEquals(1, fromFirst.publishedRelays)
        val fromBoth = a.exchange(null, now)
        assertEquals(setOf(first.publicKeyHex(), second.publicKeyHex()), fromBoth.events.map { it.author }.toSet())
        assertEquals(1, fromBoth.readableRelays)
        assertFalse(fromBoth.publicationAttempted)
    }

    @Test
    fun malformedFramesAndForgedEventsAreIgnoredWithoutLosingLaterValidData() = runBlocking {
        val event = signed(first)
        val forged = JsonParser.parseString(event).asJsonObject.apply { addProperty("sig", "00".repeat(64)) }.toString()
        val server = server().apply { enqueue(upgrade { socket, request ->
            if (request[0].asString == "REQ") {
                val sub = request[1].asString
                listOf("not-json", "[]", "[{}]", "[\"EVENT\",{},{}]", "[\"EOSE\",{}]", "[\"OK\",{},null,[]]",
                    "[\"CLOSED\",{},{}]", "[\"NOTICE\",\"ignored\"]").forEach { socket.send(it) }
                socket.send(eventFrame(sub, forged))
                socket.send(eventFrame("wrong-subscription", event))
                socket.send(eventFrame(sub, event.replaceFirst("{", "{\"id\":\"duplicate\",")))
                socket.send(eventFrame(sub, event))
                socket.send(frame("EOSE", sub))
            }
        }) }
        val result = relay(server).exchange(null, now)
        assertEquals(1, result.readableRelays)
        assertEquals(listOf(first.publicKeyHex()), result.events.map { it.author })
    }

    @Test
    fun negativeAcknowledgementIsNotReportedAsSuccessfulPublication() = runBlocking {
        val server = server().apply { enqueue(upgrade { socket, request ->
            when (request[0].asString) {
                "REQ" -> socket.send(frame("EOSE", request[1].asString))
                "EVENT" -> socket.send(frame("OK", request[1].asJsonObject["id"].asString, false, "denied"))
            }
        }) }
        val result = relay(server).exchange(signed(first), now)
        assertTrue(result.publicationAttempted)
        assertEquals(1, result.readableRelays)
        assertEquals(0, result.publishedRelays)
    }

    @Test
    fun acknowledgementMustMatchEventIdAndBooleanType() = runBlocking {
        val server = server().apply { enqueue(upgrade { socket, request ->
            when (request[0].asString) {
                "REQ" -> socket.send(frame("EOSE", request[1].asString))
                "EVENT" -> {
                    val id = request[1].asJsonObject["id"].asString
                    socket.send(frame("OK", "00".repeat(32), true, "wrong id"))
                    socket.send(frame("OK", id, "true", "not boolean"))
                    socket.send(frame("OK", id, false, "rejected"))
                }
            }
        }) }
        val result = relay(server).exchange(signed(first), now)
        assertEquals(0, result.publishedRelays)
        assertTrue(result.publicationAttempted)
    }

    @Test
    fun oneFailedRelayDoesNotCancelAnotherSuccessfulRelay() = runBlocking {
        val bad = server().apply { enqueue(MockResponse.Builder().code(503).build()) }
        val good = server().apply { enqueue(upgrade { socket, request ->
            if (request[0].asString == "REQ") {
                socket.send(eventFrame(request[1].asString, signed(second)))
                socket.send(frame("EOSE", request[1].asString))
            }
        }) }
        val result = relay(bad, good).exchange(null, now)
        assertEquals(1, result.readableRelays)
        assertEquals(listOf(second.publicKeyHex()), result.events.map { it.author })
    }

    @Test
    fun duplicateEventsAcrossRelaysAreReturnedOnce() = runBlocking {
        val event = signed(first)
        val pair = List(2) { server().apply { enqueue(upgrade { socket, request ->
            if (request[0].asString == "REQ") {
                socket.send(eventFrame(request[1].asString, event))
                socket.send(eventFrame(request[1].asString, event))
                socket.send(frame("EOSE", request[1].asString))
            }
        }) } }
        val result = relay(*pair.toTypedArray()).exchange(null, now)
        assertEquals(2, result.readableRelays)
        assertEquals(1, result.events.size)
    }

    @Test
    fun binaryOrOversizedMessagesCloseOnlyTheirOwnExchange() = runBlocking {
        val binary = server().apply { enqueue(upgrade { socket, request ->
            if (request[0].asString == "REQ") socket.send(byteArrayOf(1, 2, 3).toByteString())
        }) }
        val huge = server().apply { enqueue(upgrade { socket, request ->
            if (request[0].asString == "REQ") socket.send("x".repeat(CommunityProtocol.MAX_EVENT_CHARS + 129))
        }) }
        val result = relay(binary, huge).exchange(null, now)
        assertTrue(result.events.isEmpty())
        assertEquals(0, result.readableRelays)
    }

    @Test
    fun frameFloodCannotKeepAnExchangeAliveIndefinitely() = runBlocking {
        val server = server().apply { enqueue(upgrade { socket, request ->
            if (request[0].asString == "REQ") repeat(140) { socket.send(frame("NOTICE", "ignored")) }
        }) }
        val result = withTimeout(5_000) { relay(server).exchange(null, now) }
        assertEquals(0, result.readableRelays)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun callerCancellationClosesAnOpenWebSocketAndPropagatesCancellation() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val disconnected = CompletableDeferred<Unit>()
        val server = server().apply { enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JsonParser.parseString(text).asJsonArray[0].asString == "REQ") requested.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected.complete(Unit) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected.complete(Unit) }
        }).build()) }
        val job = async { relay(server).exchange(null, now) }
        withTimeout(5_000) { requested.await() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        withTimeout(5_000) { disconnected.await() }
    }

    @Test
    fun silentRelayTimesOutWithoutInventingReadOrPublishSuccess() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val server = server().apply { enqueue(upgrade { _, request ->
            if (request[0].asString == "REQ") requested.complete(Unit)
        }) }
        val result = withTimeout(5_000) { relay(server, timeout = 1_000).exchange(signed(first), now) }
        assertTrue(requested.isCompleted)
        assertTrue(result.publicationAttempted)
        assertEquals(0, result.readableRelays)
        assertEquals(0, result.publishedRelays)
    }

    @Test
    fun unverifiedOutgoingEventIsRejectedBeforeOpeningConnection() {
        val server = server()
        assertThrows(IllegalArgumentException::class.java) { runBlocking { relay(server).exchange("{}", now) } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun loopbackExceptionIsExplicitAndCannotBeUsedAsUserinfoOrHostPrefixBypass() {
        for (url in listOf("ws://127.0.0.1:80@evil.example/", "ws://127.0.0.1.evil.example:80/", "ws://192.168.0.1:80/", "ws://127.0.0.1:80/?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { CommunityRelay(client(), listOf(url), allowLoopbackForTests = true, reportFailure = {}).exchange(null, now) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { CommunityRelay(client(), listOf("ws://127.0.0.1:80/"), reportFailure = {}).exchange(null, now) }
        }
    }

    @Test
    fun strictJsonRejectsDuplicateKeysDeepNestingExtraDocumentsAndLenientSyntax() {
        for (input in listOf("{\"a\":1,\"a\":2}", "[1] [2]", "[NaN]", "['single-quotes']", "[/*comment*/1]", "[".repeat(12) + "0" + "]".repeat(12), "[" + List(1_025) { "0" }.joinToString(",") + "]")) {
            assertNull(CommunityJson.parse(input, 32_768))
        }
        assertNull(CommunityJson.parse("[1]", 2))
        assertEquals(1, CommunityJson.parse("[1]", 3)?.asJsonArray?.get(0)?.asInt)
    }

    private fun signed(signer: CommunitySigner, hash: String = "11".repeat(32)) = CommunityProtocol.sign(
        CommunityBundle(votes = listOf(CommunityVote(CommunityEndpointKey("eternity", hash), signer.publicKeyHex(), now, true, 250))), signer, now,
    )

    private fun server(): MockWebServer = MockWebServer().also {
        it.start(InetAddress.getByName("127.0.0.1"), 0)
        servers.add(it)
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder().build().also { clients.add(it) }

    private fun relay(vararg servers: MockWebServer, timeout: Long = 7_000) = CommunityRelay(
        client(), servers.map { it.url("/").toString().replaceFirst("http://", "ws://") },
        exchangeTimeoutMillis = timeout, allowLoopbackForTests = true, reportFailure = {},
    )

    private fun upgrade(receive: (WebSocket, JsonArray) -> Unit): MockResponse = MockResponse.Builder()
        .webSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { receive(webSocket, JsonParser.parseString(text).asJsonArray) }
        }).build()

    private fun eventFrame(subscription: String, event: String) = "[\"EVENT\",\"$subscription\",$event]"

    private fun frame(vararg parts: Any): String = JsonArray().apply { parts.forEach {
        when (it) {
            is Boolean -> add(it)
            is Number -> add(it)
            else -> add(it.toString())
        }
    } }.toString()
}
