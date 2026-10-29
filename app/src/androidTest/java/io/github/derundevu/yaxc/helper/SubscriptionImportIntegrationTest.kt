package io.github.derundevu.yaxc.helper

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.YaxcDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SubscriptionImportIntegrationTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var database: YaxcDatabase
    private var previousDatabase: YaxcDatabase? = null
    private val singleton = YaxcDatabase::class.java.getDeclaredField("db").apply { isAccessible = true }

    @Before
    fun isolateRegressionApplication() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".regression")) { "Subscription tests require the isolated regression package" }
        val isolatedSettingsContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("subscription-import-regression-$name", mode)
        }
        isolatedSettingsContext.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
            .putBoolean("socksCredentialsInitialized", true)
            .putBoolean("xHwidInitialized", true)
            .putString("socksUsername", "synthetic-user")
            .putString("socksPassword", "synthetic-password")
            .putString("xHwid", "synthetic-import-device").commit()
        settings = Settings(isolatedSettingsContext).apply {
            primaryDns = "127.0.0.1"
            secondaryDns = "127.0.0.2"
            socksAddress = "127.0.0.1"
            socksPort = "19080"
            transparentProxy = false
        }
        database = Room.inMemoryDatabaseBuilder(context, YaxcDatabase::class.java).build()
        previousDatabase = singleton.get(null) as YaxcDatabase?
        singleton.set(null, database)
    }

    @After
    fun releaseIsolatedDatabase() {
        if (::database.isInitialized) {
            singleton.set(null, previousDatabase)
            database.close()
        }
        if (::context.isInitialized && context.packageName.endsWith(".regression")) {
            context.getSharedPreferences("subscription-import-regression-settings", Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    private fun helper() = SubscriptionRefreshHelper(context, settings)
    private fun config(name: String): JSONObject = JSONObject()
        .put("remarks", name)
        .put("outbounds", JSONArray().put(JSONObject().put("protocol", "freedom").put("tag", "custom-egress")))
        .put("future", JSONObject().put("counter", 9007199254740993L)
            .put("sequence", JSONArray().put("first").put(JSONObject.NULL).put("third")))

    private suspend fun import(body: String): Link {
        OneResponseServer(body).use { server ->
            val source = Link(name = "synthetic subscription", address = server.url)
            val helper = helper()
            helper.saveResolvedLink(source, helper.resolveProfiles(source), 50_000L)
            server.awaitServed()
            return source
        }
    }

    @Test
    fun rawJsonObjectKeepsUnicodeUnknownFieldsNullArraySlotsAndIntegerPrecision() = runBlocking {
        val source = import("\uFEFF \r\n\t${config("Профиль 🌍")}\r\n")
        val stored = database.profileDao().linkProfiles(source.id).single()
        assertEquals("Профиль 🌍", stored.name)
        assertEquals(source.id, stored.linkId)
        assertEquals(Link.Type.Json, database.linkDao().find(source.id)!!.type)
        val json = JSONObject(stored.config)
        assertFalse(json.has("remarks"))
        assertEquals("custom-egress", json.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        val future = json.getJSONObject("future")
        assertEquals(9007199254740993L, future.getLong("counter"))
        assertEquals(3, future.getJSONArray("sequence").length())
        assertTrue(future.getJSONArray("sequence").isNull(1))
        assertEquals("third", future.getJSONArray("sequence").getString(2))
    }

    @Test
    fun jsonArrayAndCrLfWrappedBase64RetainEveryProfileAndInputOrderContract() = runBlocking {
        val raw = JSONArray().put(config("Первый")).put(config("Second 🌍")).toString()
        val bodies = listOf(raw, " \r\n" + Base64.getMimeEncoder(28, "\r\n".toByteArray())
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8)) + "\r\n ")
        bodies.forEachIndexed { index, body ->
            val source = import(body)
            val stored = database.profileDao().linkProfiles(source.id)
            assertEquals(listOf("Second 🌍", "Первый"), stored.map { it.name })
            assertEquals(2, stored.map { it.id }.distinct().size)
            assertTrue(stored.all { it.linkId == source.id })
            assertEquals(if (index == 0) Link.Type.Json else Link.Type.Subscription, source.type)
            stored.forEach { assertEquals(9007199254740993L, JSONObject(it.config).getJSONObject("future").getLong("counter")) }
        }
    }

    @Test
    fun rawAndBase64VlessBatchesAcceptClipboardWhitespaceWithoutDroppingLines() = runBlocking {
        val raw = "\r\n \t${vless("First%20%2B%20One")} \r\n\r\n\t${vless("%D0%A2%D0%B5%D1%81%D1%82")}\t \r\n"
        val encoded = Base64.getMimeEncoder(32, "\r\n".toByteArray())
            .encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
        listOf(raw, encoded).forEach { body ->
            val source = import(body)
            val stored = database.profileDao().linkProfiles(source.id)
            assertEquals(listOf("Тест", "First + One"), stored.map { it.name })
            assertEquals(Link.Type.Subscription, source.type)
            stored.forEach { profile ->
                val outbound = JSONObject(profile.config).getJSONArray("outbounds").getJSONObject(0)
                assertEquals("vless", outbound.getString("protocol"))
                val endpoint = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
                assertEquals("127.0.0.1", endpoint.getString("address"))
                assertEquals(9443, endpoint.getInt("port"))
                assertEquals("00000000-0000-4000-8000-000000000001", endpoint.getJSONArray("users").getJSONObject(0).getString("id"))
                assertEquals("/synthetic?token=a+b", outbound.getJSONObject("streamSettings")
                    .getJSONObject("wsSettings").getString("path"))
            }
        }
    }

    @Test
    fun mixedInvalidOrNullBatchesRejectTheWholeRefreshAndLeaveOriginalRowsIntact() = runBlocking {
        val valid = config("must not partially apply").toString()
        val mixedNull = "[$valid,null]"
        val bodies = listOf(
            mixedNull, "[$valid,42]", "[$valid,{}]", "[$valid,{\"outbounds\":[null]}]",
            Base64.getEncoder().encodeToString(mixedNull.toByteArray(StandardCharsets.UTF_8)),
            "${vless("valid")}\r\nnot a profile", "[]", "null", "{broken",
        )
        bodies.forEach { body ->
            val source = import(config("original").toString())
            val before = database.profileDao().linkProfiles(source.id)
            val beforeSource = database.linkDao().find(source.id)
            OneResponseServer(body).use { server ->
                source.address = server.url
                database.linkDao().update(source)
                val expectedSource = database.linkDao().find(source.id)
                assertEquals(SubscriptionRefreshHelper.RefreshResult(1, 0, 1), helper().refreshLinks(source.id))
                server.awaitServed()
                assertEquals(expectedSource, database.linkDao().find(source.id))
                assertEquals(before, database.profileDao().linkProfiles(source.id))
                assertEquals(beforeSource!!.lastRefreshedAt, database.linkDao().find(source.id)!!.lastRefreshedAt)
            }
        }
    }

    private fun vless(name: String) = "vless://00000000-0000-4000-8000-000000000001@127.0.0.1:9443" +
        "?encryption=none&type=ws&security=tls&sni=localhost&path=%2Fsynthetic%3Ftoken%3Da%2Bb#$name"

    private class OneResponseServer(private val body: String) : Closeable {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val url = "http://127.0.0.1:${listener.localPort}/synthetic-subscription"
        private val served = executor.submit {
            listener.accept().use { socket ->
                socket.soTimeout = 5_000
                val reader = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                check(reader.readLine().startsWith("GET /synthetic-subscription "))
                var headerCount = 0
                while (true) {
                    check(headerCount++ < 100)
                    if (reader.readLine().isEmpty()) break
                }
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
                    write(bytes)
                    flush()
                }
            }
        }
        fun awaitServed() { served.get(5, TimeUnit.SECONDS) }
        override fun close() {
            listener.close()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
