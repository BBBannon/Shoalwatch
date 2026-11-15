package app.shoalwatch.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant
import javax.crypto.KeyGenerator

/**
 * Migration 1 to 2 against a REAL version-1 database. The v1 database is built from the exported schema
 * (`app/schemas/.../1.json`: its `createSql` statements and Room's `setupQueries`), not by Room, so this is what a phone
 * that installed Milestone 1 actually holds. Room then opens it with [Migration1To2]; opening validates the migrated tables
 * against 2.json and throws on any difference.
 *
 * (`MigrationTestHelper` was not used: the repository only wires the schema folder into `androidTest` assets, and reading
 * 1.json directly needs no extra build wiring and no instrumentation.)
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox { key }
    private val sealedToken = ByteArray(45) { (it * 7 + 3).toByte() } // stands in for SecretBox ciphertext: bytes must survive untouched
    private val sealedHeaders = ByteArray(40) { (255 - it).toByte() }
    private val opened = mutableListOf<AppDatabase>()

    @Before fun clean() { context.deleteDatabase(name) }

    @After fun close() { opened.forEach { it.close() }; context.deleteDatabase(name) }

    private fun schema(version: Int): JsonObject {
        val file = File("schemas/app.shoalwatch.data.db.AppDatabase/$version.json")
        assertTrue("exported schema ${file.absolutePath} must exist", file.exists())
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    /** The statements Room itself runs to create a database at [version]. */
    private fun creationSql(version: Int): List<String> {
        val db = schema(version)
        val entities = db["entities"]!!.jsonArray.map { it.jsonObject }
        val tableSql = entities.map { it["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", it["tableName"]!!.jsonPrimitive.content) }
        val indexSql = entities.flatMap { e ->
            (e["indices"] as? JsonArray).orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", e["tableName"]!!.jsonPrimitive.content) }
        }
        return tableSql + indexSql + db["setupQueries"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    /** Builds `shoalwatch` at version 1 with one server, one account (and the active marker) holding the sealed bytes above. */
    private fun createVersion1() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    creationSql(1).forEach(db::execSQL)
                    db.execSQL(
                        "INSERT INTO servers (id, name, version, homeAddress, awayAddress, allowCleartext, pinnedSpkiSha256, clientCertAlias, customHeaders) VALUES (?, 'Den', '10.10.3', 'https://home.example', NULL, 0, NULL, NULL, ?)",
                        arrayOf(S1, sealedHeaders),
                    )
                    db.execSQL("INSERT INTO accounts (serverId, userId, userName, accessToken) VALUES (?, ?, 'ann', ?)", arrayOf(S1, U1, sealedToken))
                    db.execSQL("INSERT INTO active_account (slot, serverId, userId) VALUES (0, ?, ?)", arrayOf(S1, U1))
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        helper.writableDatabase.version.let { assertEquals(1, it) }
        helper.close()
    }

    private fun openWithMigration(file: String = name): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, file).addMigrations(Migration1To2).allowMainThreadQueries().build()
            .also { opened += it }

    @Test fun aVersion1DatabaseOpensAndKeepsItsServersAndAccountsByteForByte() = runBlocking {
        createVersion1()
        val db = openWithMigration()
        db.openHelper.writableDatabase // forces the migration and Room's validation against 2.json
        assertEquals(2, db.openHelper.writableDatabase.version)
        val server = db.serverDao().get(S1)!!
        assertEquals("Den", server.name)
        assertEquals("https://home.example", server.homeAddress)
        assertArrayEquals(sealedHeaders, server.customHeaders)
        val account = db.accountDao().get(S1, U1)!!
        assertEquals("ann", account.userName)
        assertArrayEquals("the sealed token is untouched", sealedToken, account.accessToken)
        assertEquals(U1, db.accountDao().getActiveRow()!!.userId)
    }

    @Test fun theNewTablesAreUsableAfterMigration() = runBlocking {
        createVersion1()
        val db = openWithMigration()
        val library = LibraryStore(db, app.shoalwatch.data.db.library.LocalChangeLog(app.shoalwatch.TestMonotonic()))
        val s = Session(S1, U1)
        val film = ItemFixtures.film("m1")
        library.writePage(s, "resume", 0, listOf(app.shoalwatch.data.library.ItemWithUserData(film, null)), 1, Instant.parse("2026-11-14T10:00:00Z"), requestedAtSeq = 0)
        db.itemDao().setFavourite(S1, U1, film.id, true, 1)
        val back = db.listDao().getList(S1, U1, "resume", 10).single()
        assertEquals(film.id, back.item.itemId)
        assertEquals(ItemKind.MOVIE, back.item.kind)
        assertTrue(back.userData!!.favourite)
        assertEquals(1, db.itemDao().countItems(S1))
    }

    @Test fun foreignKeysAreEnforcedAfterMigration() = runBlocking {
        createVersion1()
        val db = openWithMigration()
        val library = LibraryStore(db, app.shoalwatch.data.db.library.LocalChangeLog(app.shoalwatch.TestMonotonic()))
        library.writePage(Session(S1, U1), "resume", 0, listOf(app.shoalwatch.data.library.ItemWithUserData(ItemFixtures.film("m1"), null)), 1, Instant.now(), requestedAtSeq = 0)
        db.itemDao().setFavourite(S1, U1, ItemFixtures.film("m1").id, true, 1)
        ServerStore(db, box).removeAccount(S1, U1)
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM user_data").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM list_entries").use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.itemDao().countItems(S1))
        ServerStore(db, box).removeServer(S1)
        assertEquals(0, db.itemDao().countItems(S1))
    }

    @Test fun aMigratedDatabaseAndAFreshOneHaveIdenticalSchemas() {
        createVersion1()
        val migrated = openWithMigration()
        val fresh = openWithMigration("fresh-test.db")
        assertEquals(schemaOf(fresh), schemaOf(migrated))
        assertTrue("the new tables are in it", schemaOf(migrated).any { it.startsWith("table items:") })
        context.deleteDatabase("fresh-test.db")
    }

    /** Every table and index in `sqlite_master` with its SQL, whitespace normalised (Room's bookkeeping table included). */
    private fun schemaOf(db: AppDatabase): List<String> =
        db.openHelper.writableDatabase.query("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' ORDER BY type, name").use { c ->
            buildList { while (c.moveToNext()) add("${c.getString(0)} ${c.getString(1)}: ${(c.getString(2) ?: "").replace(Regex("\\s+"), " ").trim()}") }
        }

    @Test fun theVersion2SchemaIsExportedAndNamesTheNewTables() {
        val tables = schema(2)["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("servers", "accounts", "active_account", "items", "user_data", "views", "list_entries", "list_state", "visible_items"), tables)
    }
}

/** Items for tests that do not need the full [LibraryTestBase]. */
private object ItemFixtures {
    fun film(label: String) = app.shoalwatch.data.library.Item(
        id = app.shoalwatch.testId(label), kind = ItemKind.MOVIE, name = "Film $label", sortName = null, parentId = null, seriesId = null,
        seriesName = null, seasonId = null, indexNumber = null, parentIndexNumber = null, overview = null, runtimeMs = null, year = null,
        premiereDate = null, communityRating = null, officialRating = null, childCount = null,
        images = app.shoalwatch.data.library.ItemImages(null, null, null, null),
    )
}
