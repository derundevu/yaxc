package io.github.derundevu.yaxc.helper

import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class HttpHelperIntegrationTest : RoomTestSupport() {
    @Test
    fun fetchPreservesUtf8BodyAndHeaderValuesAndGetUsesTheSameTransport() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val expected = "  Тест 🌍\r\nsecond line\n"
            val first = server.enqueue(expected, headers = mapOf("X-Synthetic-Metadata" to "kept"))
            val response = HttpHelper.fetch(server.url)
            assertEquals(expected, response.body)
            assertEquals(listOf("kept"), response.headers.entries.single {
                it.key.equals("x-synthetic-metadata", ignoreCase = true)
            }.value)
            assertEquals("yaxc/${BuildConfig.VERSION_NAME}", first.awaitRequest().headers["user-agent"])
            val second = server.enqueue("second")
            assertEquals("second", HttpHelper.get(server.url, "explicit-agent", mapOf("X-Test" to "synthetic")))
            assertEquals("explicit-agent", second.awaitRequest().headers["user-agent"])
            assertEquals("synthetic", second.awaitRequest().headers["x-test"])
        }
    }

    @Test
    fun headerPrecedenceIsCaseInsensitiveOnTheWireAndBlankOverridesUseGlobalSettings() = runBlocking {
        SubscriptionHttpServer().use { server ->
            settings.userAgent = "global-agent"
            settings.xHwid = "synthetic-global-device"
            val headers = HttpHelper.buildSubscriptionHeaders(settings,
                "X-HWID: superseded\r\nx-hwid: custom-device\r\nx-device-os: custom-os\r\nX-Test: first\r\nx-test: last\r\nempty:", "  ")
            val exchange = server.enqueue("ok")
            HttpHelper.fetch(server.url, HttpHelper.resolveSubscriptionUserAgent(settings, " \t "), headers)
            val request = exchange.awaitRequest()
            assertEquals("global-agent", request.headers["user-agent"])
            assertEquals("custom-device", request.headers["x-hwid"])
            assertEquals("custom-os", request.headers["x-device-os"])
            assertEquals("last", request.headers["x-test"])
            assertFalse(request.headers.containsKey("empty"))
            assertEquals(1, headers.keys.count { it.equals("x-hwid", ignoreCase = true) })
            val fallback = server.enqueue("ok")
            HttpHelper.fetch(server.url, headers = HttpHelper.buildSubscriptionHeaders(settings, null))
            assertEquals("synthetic-global-device", fallback.awaitRequest().headers["x-hwid"])
            val overridden = server.enqueue("ok")
            HttpHelper.fetch(server.url, headers = HttpHelper.buildSubscriptionHeaders(settings,
                "X-HWID: first\r\nx-hwid: second", "synthetic-explicit-device"))
            assertEquals("synthetic-explicit-device", overridden.awaitRequest().headers["x-hwid"])
        }
    }

    @Test
    fun nonOkResponsesRemainErrorsEvenWhenBodyLooksLikeAValidSubscription() = runBlocking {
        SubscriptionHttpServer().use { server ->
            listOf(204, 400, 401, 403, 404, 429, 500, 503).forEach { status ->
                server.enqueue("{\"outbounds\":[{\"protocol\":\"freedom\"}]}", status)
                val failure = try {
                    HttpHelper.fetch(server.url)
                    null
                } catch (error: IOException) { error }
                assertNotNull("HTTP $status must not be imported", failure)
                assertEquals("HTTP Error: $status", failure!!.message)
            }
            assertEquals(8, server.requestCount.get())
        }
    }

    @Test
    fun cancelledFetchPropagatesCancellationInsteadOfReturningItsCompletedBody() = runBlocking {
        SubscriptionHttpServer().use { server ->
            val exchange = server.enqueue("must not return", held = true)
            val pending = async(Dispatchers.IO) { HttpHelper.fetch(server.url) }
            exchange.awaitRequest()
            pending.cancel(CancellationException("synthetic cancellation"))
            exchange.release()
            val failure = try { pending.await(); null } catch (error: CancellationException) { error }
            assertNotNull(failure)
            pending.join()
            assertEquals(1, server.requestCount.get())
        }
    }
}
