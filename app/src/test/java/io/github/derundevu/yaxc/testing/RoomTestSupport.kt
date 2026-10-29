package io.github.derundevu.yaxc.testing

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.database.YaxcDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment

/** Runs application-backed tests without scheduling refresh work or loading native services. */
class RegressionApplication : Yaxc() {
    override fun onCreate() = Unit
}

/** A real, isolated Room database shared by the application's repositories for each test. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
abstract class RoomTestSupport {
    protected open val useAndroidMainDispatcher = false
    protected lateinit var app: Yaxc
    protected lateinit var settings: Settings
    protected lateinit var database: YaxcDatabase
    private lateinit var databaseExecutor: ExecutorService

    @Before
    fun createIsolatedRoom() {
        app = RuntimeEnvironment.getApplication() as Yaxc
        factoryField.set(null, null)
        if (useAndroidMainDispatcher) {
            Dispatchers.setMain(Handler(Looper.getMainLooper()).asCoroutineDispatcher())
        }
        app.getSharedPreferences("vpn_tile", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("socksCredentialsInitialized", true)
            .putBoolean("xHwidInitialized", true)
            .putString("socksUsername", "regression-user")
            .putString("socksPassword", "regression-password")
            .putString("xHwid", "synthetic-regression-hwid")
            .commit()
        settings = Settings(app).apply {
            socksAddress = "127.0.0.1"
            socksPort = "19080"
            primaryDns = "192.0.2.53"
            secondaryDns = "198.51.100.53"
            primaryDnsV6 = "2001:db8::53"
            secondaryDnsV6 = "2001:db8::54"
            enableIpV6 = false
            transparentProxy = false
            tproxyHotspot = false
            tproxyTethering = false
            antifilterEnabled = false
        }
        databaseExecutor = Executors.newSingleThreadExecutor()
        database = Room.inMemoryDatabaseBuilder(app, YaxcDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(databaseExecutor)
            .setTransactionExecutor(databaseExecutor)
            .build()
        singletonField.set(null, database)
    }

    @After
    fun closeIsolatedRoom() {
        singletonField.set(null, null)
        factoryField.set(null, null)
        if (::database.isInitialized) database.close()
        if (::databaseExecutor.isInitialized) databaseExecutor.shutdownNow()
        if (::app.isInitialized) {
            app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
            app.getSharedPreferences("vpn_tile", Context.MODE_PRIVATE).edit().clear().commit()
        }
        if (useAndroidMainDispatcher) Dispatchers.resetMain()
    }

    private companion object {
        val singletonField = YaxcDatabase::class.java.getDeclaredField("db").apply {
            isAccessible = true
        }
        // AndroidX retains an Application singleton; a new Robolectric Application owns a new DB.
        val factoryField = ViewModelProvider.AndroidViewModelFactory::class.java
            .getDeclaredField("_instance").apply { isAccessible = true }
    }
}
