package io.github.derundevu.yaxc.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.testing.RegressionApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = RegressionApplication::class, sdk = [34])
class YaxcDatabaseMigrationTest {
    @Test fun version1RetainsManualProfiles() = migrateFrom(1)
    @Test fun version2RetainsLinkedProfiles() = migrateFrom(2)
    @Test fun version3RetainsUserAgents() = migrateFrom(3)
    @Test fun version4RetainsConfigurationSections() = migrateFrom(4)
    @Test fun version5RetainsExplicitSourcePositions() = migrateFrom(5)
    @Test fun version6RetainsCustomHeaders() = migrateFrom(6)
    @Test fun version7RetainsSubscriptionMetadata() = migrateFrom(7)
    @Test fun version8RetainsHardwareIdentity() = migrateFrom(8)

    private fun migrateFrom(version: Int) = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Yaxc
        val name = "migration-from-$version.db"
        app.deleteDatabase(name)
        val path = app.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        // These fixtures are reconstructed from the migration SQL, not historical Room snapshots.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            createLegacySchema(legacy, version)
            seedLegacyRows(legacy, version)
            legacy.version = version
        }
        val migrated = Room.databaseBuilder(app, YaxcDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(*YaxcDatabase.migrations)
            .build()
        try {
            // Opening Room performs its real v9 schema validation as well as running the chain.
            assertEquals(9, migrated.openHelper.writableDatabase.version)
            val dao = migrated.profileDao()
            assertEquals(manualOne, dao.find(manualOne.id))
            assertEquals(manualTwo, dao.find(manualTwo.id))
            assertEquals(if (version >= 4) retainedConfig else null, migrated.configDao().get())
            assertEquals(if (version >= 2) 4 else 2, dao.orderedPositions().size)
            assertEquals(listOf(29L, 11L) + if (version >= 2) listOf(37L) else emptyList(),
                dao.all().first().map { it.id })

            val sql = migrated.openHelper.writableDatabase
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            sql.query("PRAGMA index_info(profiles_link_id_foreign)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("link_id", cursor.getString(2))
                assertFalse(cursor.moveToNext())
            }
            sql.query("PRAGMA foreign_key_list(profiles)").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("links", cursor.getString(cursor.getColumnIndexOrThrow("table")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_update")))
                assertEquals("CASCADE", cursor.getString(cursor.getColumnIndexOrThrow("on_delete")))
            }
            if (version >= 2) {
                val links = migrated.linkDao().all().first().associateBy { it.id }
                assertEquals(setOf(4L, 17L), links.keys)
                val active = links.getValue(4)
                assertEquals("Synthetic subscription", active.name)
                assertEquals("http://127.0.0.1:18080/subscription", active.address)
                assertEquals(Link.Type.Subscription, active.type)
                assertTrue(active.isActive)
                assertEquals(if (version >= 5) 3 else 4, active.position)
                assertEquals(if (version >= 3) "fixture-agent/3" else null, active.userAgent)
                assertEquals(if (version >= 6) "X-Fixture: synthetic" else null, active.customHeaders)
                assertEquals(if (version >= 7) "upload=11; download=29; total=4096" else null,
                    active.subscriptionMetadata)
                assertEquals(if (version >= 8) "synthetic-migration-hwid" else null, active.xHwid)
                assertEquals(0L, active.lastRefreshedAt)
                assertFalse(links.getValue(17).isActive)
                assertEquals(if (version >= 5) listOf(17L, 4L) else listOf(4L, 17L),
                    migrated.linkDao().all().first().map { it.id })
                assertEquals(linkedActive, dao.find(37))
                assertEquals(linkedInactive, dao.find(52))

                // Both FK actions remain live after migration, without deleting other ownership groups.
                sql.execSQL("UPDATE links SET id = 104 WHERE id = 4")
                assertEquals(104L, dao.find(37).linkId)
                sql.execSQL("DELETE FROM links WHERE id = 104")
                assertEquals(listOf(29L, 11L, 52L), dao.orderedPositions().map { it.id })
                assertEquals(manualOne, dao.find(11))
                assertEquals(manualTwo, dao.find(29))
                assertEquals(linkedInactive, dao.find(52))
                assertEquals(if (version >= 4) retainedConfig else null, migrated.configDao().get())
            } else {
                assertTrue(migrated.linkDao().all().first().isEmpty())
                assertNull(migrated.configDao().get())
            }
            sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            migrated.close()
            app.deleteDatabase(name)
        }
    }

    private fun createLegacySchema(db: SQLiteDatabase, version: Int) {
        db.setForeignKeyConstraintsEnabled(true)
        if (version >= 2) {
            val columns = mutableListOf(
                "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT",
                "name TEXT NOT NULL", "address TEXT NOT NULL", "type INTEGER NOT NULL",
                "is_active INTEGER NOT NULL",
            )
            if (version >= 3) columns += "user_agent TEXT"
            if (version >= 5) columns += "position INTEGER NOT NULL DEFAULT 0"
            if (version >= 6) columns += "custom_headers TEXT"
            if (version >= 7) columns += "subscription_metadata TEXT"
            if (version >= 8) columns += "x_hwid TEXT"
            db.execSQL("CREATE TABLE links (${columns.joinToString()})")
        }
        val ownership = if (version >= 2) "link_id INTEGER," else ""
        val foreignKey = if (version >= 2)
            ", FOREIGN KEY(link_id) REFERENCES links(id) ON UPDATE CASCADE ON DELETE CASCADE" else ""
        db.execSQL("""
            CREATE TABLE profiles (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                $ownership
                "index" INTEGER NOT NULL,
                name TEXT NOT NULL,
                config TEXT NOT NULL
                $foreignKey
            )
        """.trimIndent())
        if (version >= 2) db.execSQL("CREATE INDEX profiles_link_id_foreign ON profiles(link_id)")
        if (version >= 4) db.execSQL("""
            CREATE TABLE configs (
                id INTEGER PRIMARY KEY NOT NULL,
                log TEXT NOT NULL, dns TEXT NOT NULL, inbounds TEXT NOT NULL,
                outbounds TEXT NOT NULL, routing TEXT NOT NULL,
                log_mode INTEGER NOT NULL, dns_mode INTEGER NOT NULL,
                inbounds_mode INTEGER NOT NULL, outbounds_mode INTEGER NOT NULL,
                routing_mode INTEGER NOT NULL
            )
        """.trimIndent())
    }

    private fun seedLegacyRows(db: SQLiteDatabase, version: Int) {
        if (version >= 2) {
            for ((id, active) in listOf(4L to true, 17L to false)) {
                val columns = mutableListOf("id", "name", "address", "type", "is_active")
                val values = mutableListOf<Any?>(id,
                    if (active) "Synthetic subscription" else "Synthetic inactive source",
                    "http://127.0.0.1:18080/subscription", if (active) 1 else 0, if (active) 1 else 0)
                if (version >= 3) { columns += "user_agent"; values += "fixture-agent/3" }
                if (version >= 5) { columns += "position"; values += if (active) 3 else 1 }
                if (version >= 6) { columns += "custom_headers"; values += "X-Fixture: synthetic" }
                if (version >= 7) {
                    columns += "subscription_metadata"; values += "upload=11; download=29; total=4096"
                }
                if (version >= 8) { columns += "x_hwid"; values += "synthetic-migration-hwid" }
                db.execSQL("INSERT INTO links (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})",
                    values.toTypedArray())
            }
        }
        val profiles = listOf(manualOne, manualTwo) +
            if (version >= 2) listOf(linkedActive, linkedInactive) else emptyList()
        for (profile in profiles) {
            if (version >= 2) db.execSQL(
                "INSERT INTO profiles (id, link_id, `index`, name, config) VALUES (?, ?, ?, ?, ?)",
                arrayOf<Any?>(profile.id, profile.linkId, profile.index, profile.name, profile.config),
            ) else db.execSQL(
                "INSERT INTO profiles (id, `index`, name, config) VALUES (?, ?, ?, ?)",
                arrayOf<Any?>(profile.id, profile.index, profile.name, profile.config),
            )
        }
        if (version >= 4) db.execSQL(
            "INSERT INTO configs VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(retainedConfig.id, retainedConfig.log, retainedConfig.dns, retainedConfig.inbounds,
                retainedConfig.outbounds, retainedConfig.routing, retainedConfig.logMode.value,
                retainedConfig.dnsMode.value, retainedConfig.inboundsMode.value,
                retainedConfig.outboundsMode.value, retainedConfig.routingMode.value),
        )
    }

    private companion object {
        val manualOne = Profile(11, null, 9, "Manual α", """{"outbounds":[{"tag":"manual-a"}]}""")
        val manualTwo = Profile(29, null, -2, "Manual β", """{"dns":{"servers":["127.0.0.1"]}}""")
        val linkedActive = Profile(37, 4, 13, "Active child", """{"remarks":"active synthetic"}""")
        val linkedInactive = Profile(52, 17, 21, "Inactive child", """{"remarks":"inactive synthetic"}""")
        val retainedConfig = Config(
            log = """{"loglevel":"warning"}""", dns = """{"servers":["127.0.0.2"]}""",
            inbounds = """[{"tag":"local-in","port":19080}]""",
            outbounds = """[{"tag":"direct","protocol":"freedom"}]""",
            routing = """{"rules":[{"type":"field","outboundTag":"direct","domain":["fixture.invalid"]}]}""",
            logMode = Config.Mode.Replace, dnsMode = Config.Mode.Merge,
            inboundsMode = Config.Mode.Disable, outboundsMode = Config.Mode.Merge,
            routingMode = Config.Mode.Replace,
        )
    }
}
