package com.v2ray.ang.handler

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI

internal data class CommunityExchange(
    val events: List<VerifiedCommunityEvent>,
    val readableRelays: Int,
    val publishedRelays: Int,
    val publicationAttempted: Boolean = false,
)

/** Short, cancellable foreground exchanges. No listener, background daemon, or central app backend. */
internal class CommunityRelay(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(7, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
    private val relays: List<String> = DEFAULT_RELAYS,
    private val exchangeTimeoutMillis: Long = 7_000,
    private val allowLoopbackForTests: Boolean = false,
    private val reportFailure: (String) -> Unit = { errorType ->
        LogUtil.w(message = "Community relay exchange failed", throwable = IllegalStateException(errorType))
    },
) {
    suspend fun exchange(outgoing: String?, nowMillis: Long): CommunityExchange = coroutineScope {
        require(exchangeTimeoutMillis in 1..7_000 && relays.size in 1..3 && relays.distinct().size == relays.size)
        require(relays.all(::permittedRelay)) { "Unsupported community relay" }
        val outgoingId = withContext(Dispatchers.Default) {
            outgoing?.let { requireNotNull(CommunityProtocol.verify(it, nowMillis)) { "Invalid outgoing community event" }.id }
        }
        val results = relays.map { relay -> async {
            try {
                exchangeOne(relay, outgoing, outgoingId, nowMillis)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportFailure(error.javaClass.simpleName)
                CommunityExchange(emptyList(), 0, 0)
            }
        } }.awaitAll()
        CommunityExchange(results.flatMap { it.events }.distinctBy { it.id }.take(192),
            results.sumOf { it.readableRelays }, results.sumOf { it.publishedRelays }, outgoing != null)
    }

    private fun permittedRelay(relay: String): Boolean {
        if (relay in DEFAULT_RELAYS) return true
        if (!allowLoopbackForTests) return false
        return runCatching {
            val uri = URI(relay)
            uri.scheme == "ws" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
                uri.rawUserInfo == null && uri.rawFragment == null && uri.rawQuery == null && uri.rawPath == "/"
        }.getOrDefault(false)
    }

    private suspend fun exchangeOne(relay: String, outgoing: String?, outgoingId: String?, nowMillis: Long): CommunityExchange {
        val messages = Channel<String>(130)
        val subscription = UUID.randomUUID().toString()
        val events = mutableListOf<VerifiedCommunityEvent>()
        val finishing = AtomicBoolean(false)
        var read = false
        var published = false
        var acknowledged = outgoing == null
        val socket = client.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val filter = JsonObject().apply {
                    add("kinds", JsonArray().apply { add(CommunityProtocol.KIND) })
                    add("#d", JsonArray().apply { add(CommunityProtocol.NAMESPACE) })
                    add("#t", JsonArray().apply { add(CommunityProtocol.NAMESPACE) })
                    addProperty("since", (nowMillis - CommunityProtocol.TTL_MILLIS).coerceAtLeast(0) / 1000)
                    addProperty("limit", 64)
                }
                val sent = webSocket.send(JsonArray().apply { add("REQ"); add(subscription); add(filter) }.toString())
                if (!sent || (outgoing != null && !webSocket.send("[\"EVENT\",$outgoing]"))) {
                    messages.close(); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > CommunityProtocol.MAX_EVENT_CHARS + 128 || !messages.trySend(text).isSuccess) {
                    messages.close(); webSocket.cancel()
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                messages.close(); webSocket.cancel()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                messages.close()
                // Cancellation also reaches this callback; never pass a URL-bearing exception to logs.
                if (!finishing.get()) reportFailure(t.javaClass.simpleName)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                messages.close(); webSocket.close(code, null)
            }
        })
        try {
            val completed = withTimeoutOrNull(exchangeTimeoutMillis) {
                withContext(Dispatchers.Default) {
                    var count = 0
                    for (text in messages) {
                        currentCoroutineContext().ensureActive()
                        if (++count > 128) break
                        val frame = parseFrame(text) ?: continue
                        val type = frame.getOrNull(0)?.stringOrNull() ?: continue
                        when (type) {
                            "EVENT" -> if (frame.size() == 3 && frame[1].stringOrNull() == subscription && events.size < 64) {
                                CommunityProtocol.verify(frame[2].toString(), nowMillis)?.let { events.add(it) }
                            }
                            "EOSE" -> if (frame.size() == 2 && frame[1].stringOrNull() == subscription) read = true
                            "OK" -> if (outgoingId != null && frame.size() == 4 && frame[1].stringOrNull() == outgoingId &&
                                frame[2].isJsonPrimitive && frame[2].asJsonPrimitive.isBoolean && frame[3].stringOrNull() != null) {
                                acknowledged = true; published = frame[2].asBoolean
                            }
                            "CLOSED" -> if (frame.size() == 3 && frame[1].stringOrNull() == subscription && frame[2].stringOrNull() != null) break
                        }
                        if (read && acknowledged) break
                    }
                }
            }
            if (completed == null) reportFailure("Timeout")
        } finally {
            finishing.set(true)
            try { socket.send(JsonArray().apply { add("CLOSE"); add(subscription) }.toString()) }
            finally { socket.cancel(); messages.cancel() }
        }
        return CommunityExchange(events, if (read) 1 else 0, if (published) 1 else 0)
    }

    private fun parseFrame(text: String): JsonArray? =
        CommunityJson.parse(text, CommunityProtocol.MAX_EVENT_CHARS + 128)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index in 0 until size()) get(index) else null
    private fun JsonElement.stringOrNull(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")
    }
}
