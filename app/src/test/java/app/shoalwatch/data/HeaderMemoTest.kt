package app.shoalwatch.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import app.shoalwatch.testId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/** Opening the sealed custom headers is memoised by ciphertext, so a request does not pay the Keystore each time. */
@RunWith(RobolectricTestRunner::class)
class HeaderMemoTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val keyCalls = AtomicInteger()
    private val box = SecretBox { keyCalls.incrementAndGet(); key }
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore

    private fun server(id: String = S1, headers: Map<String, String> = mapOf("X-Proxy-Key" to "one")) =
        Server(id, "Den", "10.10.3", ServerAddress.parse("https://den.example"), null, false, null, null, headers)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        store = ServerStore(db, box)
    }

    @After fun close() = db.close()

    @Test fun repeatedReadsOfAnUnchangedServerOpenTheHeadersOnce() = runBlocking {
        store.upsertServer(server())
        keyCalls.set(0)
        repeat(20) { assertEquals(mapOf("X-Proxy-Key" to "one"), store.server(S1)!!.customHeaders) }
        assertEquals(1, keyCalls.get())
    }

    @Test fun changedHeadersAreSeenImmediatelyAtOneMoreOpen() = runBlocking {
        store.upsertServer(server())
        store.server(S1)
        store.upsertServer(server(headers = mapOf("X-Proxy-Key" to "two")))
        keyCalls.set(0)
        repeat(5) { assertEquals(mapOf("X-Proxy-Key" to "two"), store.server(S1)!!.customHeaders) }
        assertEquals(1, keyCalls.get())
    }

    @Test fun otherSettingsAreReadFromTheRowEveryTime() = runBlocking {
        store.upsertServer(server())
        store.server(S1)
        store.upsertServer(server().let { Server(it.id, "Renamed", it.version, it.homeAddress, it.awayAddress, true, null, null, it.customHeaders) })
        val read = store.server(S1)!!
        assertEquals("Renamed", read.name)
        assertTrue(read.allowCleartext)
    }

    @Test fun anUnreadableBlobIsNotCachedAsSuccess() = runBlocking {
        store.upsertServer(server())
        val row = db.serverDao().get(S1)!!
        val damaged = row.customHeaders.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x55).toByte() }
        db.openHelper.writableDatabase.execSQL("UPDATE servers SET customHeaders = ? WHERE id = ?", arrayOf<Any>(damaged, S1))
        keyCalls.set(0)
        repeat(3) { assertEquals(emptyMap<String, String>(), store.server(S1)!!.customHeaders) }
        assertEquals("each read of a damaged blob tries again", 3, keyCalls.get())
        // and putting the good blob back works at once
        db.openHelper.writableDatabase.execSQL("UPDATE servers SET customHeaders = ? WHERE id = ?", arrayOf<Any>(row.customHeaders, S1))
        assertEquals(mapOf("X-Proxy-Key" to "one"), store.server(S1)!!.customHeaders)
    }

    @Test fun removingAServerForgetsItsOpenedHeaders() = runBlocking {
        store.upsertServer(server())
        store.upsertServer(server(S2))
        store.server(S1)
        store.removeServer(S2)
        keyCalls.set(0)
        store.server(S1)
        assertEquals("the cache was cleared by the removal", 1, keyCalls.get())
    }

    @Test fun theListFlowAlsoUsesTheMemo() = runBlocking {
        store.upsertServer(server())
        store.servers.first()
        keyCalls.set(0)
        repeat(5) { store.servers.first() }
        assertEquals(0, keyCalls.get())
    }

    @Test fun theCacheIsBounded() = runBlocking {
        val ids = (1..20).map { testId("memo$it") }
        ids.forEach { store.upsertServer(server(it, mapOf("H" to it))) }
        ids.forEach { store.server(it) }
        keyCalls.set(0)
        store.server(ids.first()) // evicted by now
        assertEquals(1, keyCalls.get())
        keyCalls.set(0)
        store.server(ids.last()) // most recent is still cached
        assertEquals(0, keyCalls.get())
    }
}
