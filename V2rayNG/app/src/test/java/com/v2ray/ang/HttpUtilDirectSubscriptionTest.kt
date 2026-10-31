package com.v2ray.ang

import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.HttpUtil
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Proxy

class HttpUtilDirectSubscriptionTest {
    @Test fun directClientBoundsWholeCallWithoutProxyOrAutomaticRedirects() {
        val client = HttpUtil.directSubscriptionClient(750)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertEquals(750, client.callTimeoutMillis)
        assertEquals(750, client.connectTimeoutMillis)
        assertEquals(750, client.readTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test fun wholeBudgetIncludesRedirectsAndDoesNotRestartPerRequest() = runBlocking {
        var now = 100L
        val timeouts = mutableListOf<Long>()
        val requests = mutableListOf<Request>()
        val result = HttpUtil.getSubscriptionDirect(UrlContentRequest("https://catalog.example/start"), 1_100, { now }) { remaining ->
            timeouts += remaining
            Call.Factory { request -> FakeCall(request) { call, callback ->
                requests += request
                if (requests.size == 1) {
                    now += 400
                    callback.onResponse(call, response(request, 302, location = "/final"))
                } else callback.onResponse(call, response(request, 200, "downloaded"))
            } }
        }
        assertEquals("downloaded", result.body)
        assertEquals(listOf(1000L, 600L), timeouts)
        assertEquals(listOf("/start", "/final"), requests.map { it.url.encodedPath })
    }

    @Test fun evenLongSearchBudgetCapsOneSourceAtFifteenSeconds() = runBlocking {
        var observed = 0L
        HttpUtil.getSubscriptionDirect(UrlContentRequest("https://catalog.example/"), 180_000, { 0 }) { timeout ->
            observed = timeout
            Call.Factory { request -> FakeCall(request) { call, callback -> callback.onResponse(call, response(request, 200)) } }
        }
        assertEquals(15_000L, observed)
    }

    @Test fun cancellingOwnerCancelsTheActualHttpCall() = runBlocking {
        lateinit var call: FakeCall
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            HttpUtil.getSubscriptionDirect(UrlContentRequest("https://catalog.example/"), 1000, { 0 }) {
                Call.Factory { request -> FakeCall(request) { _, _ -> }.also { call = it } }
            }
        }
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
    }

    @Test fun expiredBudgetAndUnsafeUrlsNeverCreateANetworkCall() = runBlocking {
        for ((url, deadline) in listOf("https://catalog.example/" to 0L,
            "http://catalog.example/" to 1000L, "https://user:password@catalog.example/" to 1000L)) {
            try {
                HttpUtil.getSubscriptionDirect(UrlContentRequest(url), deadline, { 0 }) { fail("Network must not start") }
                fail("Download must fail")
            } catch (_: IOException) { }
        }
    }

    @Test fun crossOriginRedirectDropsPrivateHeadersAndNeverSendsProxyAuthentication() = runBlocking {
        val requests = mutableListOf<Request>()
        HttpUtil.getSubscriptionDirect(UrlContentRequest("https://one.example/", requestHeaders =
            """{"Authorization":"Bearer test","Cookie":"test=1","X-Private":"private","Proxy-Authorization":"proxy"}"""),
            1000, { 0 }) {
            Call.Factory { request -> FakeCall(request) { call, callback ->
                requests += request
                callback.onResponse(call, if (requests.size == 1) response(request, 302, location = "https://two.example/")
                    else response(request, 200))
            } }
        }
        assertEquals("Bearer test", requests.first().header("Authorization"))
        assertNull(requests.first().header("Proxy-Authorization"))
        for (name in listOf("Authorization", "Cookie", "X-Private", "Proxy-Authorization")) {
            assertNull(requests.last().header(name))
        }
    }

    @Test fun httpsDowngradeAndBodyFinishingAfterDeadlineAreRejected() = runBlocking {
        for (downgrade in listOf(true, false)) {
            var now = 0L
            var requests = 0
            try {
                HttpUtil.getSubscriptionDirect(UrlContentRequest("https://catalog.example/"), 1000, { now }) {
                    Call.Factory { request -> FakeCall(request) { call, callback ->
                        requests++
                        if (downgrade) callback.onResponse(call, response(request, 302, location = "http://catalog.example/"))
                        else { now = 1000; callback.onResponse(call, response(request, 200)) }
                    } }
                }
                fail("Unsafe or late result must not import")
            } catch (_: IOException) { }
            assertEquals(1, requests)
        }
    }

    @Test fun oversizedKnownAndUnknownLengthBodiesAreRejectedBeforeImport() = runBlocking {
        for (knownLength in listOf(true, false)) {
            val payload = ByteArray(HttpUtil.MAX_DIRECT_SUBSCRIPTION_BYTES.toInt() + 1) { 'x'.code.toByte() }.toResponseBody()
            // Unknown length models the stream OkHttp exposes after gzip decompression or chunking.
            val body = if (knownLength) payload else object : ResponseBody() {
                override fun contentType() = payload.contentType()
                override fun contentLength() = -1L
                override fun source() = payload.source()
            }
            val error = assertThrows(IOException::class.java) { runBlocking {
                HttpUtil.getSubscriptionDirect(UrlContentRequest("https://catalog.example/"), 1000, { 0 }) {
                    Call.Factory { request -> FakeCall(request) { call, callback ->
                        callback.onResponse(call, response(request, 200).newBuilder().body(body).build())
                    } }
                }
            } }
            assertEquals("Subscription response exceeds size limit", error.message)
        }
    }

    @Test fun bodyAtExactByteLimitIsAcceptedWithoutTruncation() = runBlocking {
        val payload = "x".repeat(HttpUtil.MAX_DIRECT_SUBSCRIPTION_BYTES.toInt())
        val result = HttpUtil.getSubscriptionDirect(UrlContentRequest("https://catalog.example/"), 1000, { 0 }) {
            Call.Factory { request -> FakeCall(request) { call, callback ->
                callback.onResponse(call, response(request, 200, payload))
            } }
        }
        assertEquals(payload, result.body)
    }

    private fun response(request: Request, code: Int, body: String = "test", location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .headers(Headers.Builder().apply { location?.let { add("Location", it) } }.build())
            .body(body.toResponseBody()).build()

    // Delegate unused metadata APIs to an unexecuted real call; all I/O remains controlled below.
    private class FakeCall(private val value: Request, private val handler: (FakeCall, Callback) -> Unit) :
        Call by OkHttpClient().newCall(value) {
        private var executed = false
        private var cancelled = false
        override fun request() = value
        override fun execute(): Response = error("Must enqueue for cancellation")
        override fun enqueue(responseCallback: Callback) { executed = true; handler(this, responseCallback) }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(value, handler)
    }
}
