package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.U3

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.core.AppError
import app.shoalwatch.testId
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class ServerStoreTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox { key }
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private val stale = mutableListOf<Pair<String, List<ServerAddress>>>()

    private val validPin = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })

    private fun server(id: String = S1, pin: String? = null) = Server(
        id = id,
        name = "Home server",
        version = "10.10.3",
        homeAddress = ServerAddress.parse("https://home.example:8920"),
        awayAddress = ServerAddress.parse("https://away.example"),
        allowCleartext = false,
        pinnedSpkiSha256 = pin,
        clientCertAlias = "my-cert",
        customHeaders = mapOf("X-Proxy-Key" to "headervalue-12345"),
    )

    private fun account(serverId: String = S1, userId: String = U1, token: String = "plain-token-ABCDEF") =
        Account(serverId, userId, "ben", token)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ServerStore(db, box, onClientsStale = { id, addresses -> stale += id to addresses })
    }

    @After fun close() = db.close()

    private fun rawBlobs(sql: String): List<ByteArray> = db.openHelper.readableDatabase.query(sql).use { c ->
        buildList { while (c.moveToNext()) add(c.getBlob(0)) }
    }

    private fun setColumn(table: String, column: String, value: Any) =
        db.openHelper.writableDatabase.execSQL("UPDATE $table SET $column = ?", arrayOf(value))

    private fun rawCount(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test fun serverRoundTrips() = runBlocking {
        store.upsertServer(server(pin = validPin))
        assertEquals(server(pin = validPin), store.server(S1))
        assertEquals(listOf(server(pin = validPin)), store.servers.first())
    }

    @Test fun missingServerIsNull() = runBlocking { assertNull(store.server("nope")) }

    @Test fun tokenAndHeadersAreStoredOnlyAsCiphertext() = runBlocking {
        store.upsertServer(server())
        store.upsertAccount(account(), makeActive = true)

        val token = rawBlobs("SELECT accessToken FROM accounts").single()
        val headers = rawBlobs("SELECT customHeaders FROM servers").single()
        assertFalse(token.contentEquals("plain-token-ABCDEF".toByteArray()))
        assertFalse(String(token, Charsets.ISO_8859_1).contains("plain-token-ABCDEF"))
        assertEquals("plain-token-ABCDEF", box.open(token))
        assertFalse(String(headers, Charsets.ISO_8859_1).contains("headervalue-12345"))
        assertFalse(String(headers, Charsets.ISO_8859_1).contains("X-Proxy-Key"))
        assertEquals(account().let { AccountSummary(it.serverId, it.userId, it.userName) }, store.active())
    }

    @Test fun removingServerRemovesItsAccountsAndActiveMarker() = runBlocking {
        store.upsertServer(server(S1)); store.upsertServer(server(S2))
        store.upsertAccount(account(S1, U1), makeActive = true)
        store.upsertAccount(account(S2, U2), makeActive = false)

        store.removeServer(S1)

        assertNull(store.server(S1))
        assertTrue(store.accounts(S1).first().isEmpty())
        assertEquals(1, rawCount("accounts"))
        assertNull(store.activeAccount.first())
        assertEquals(0, rawCount("active_account"))
    }

    @Test fun upsertingExistingServerKeepsItsAccounts() = runBlocking {
        store.upsertServer(server())
        store.upsertAccount(account(), makeActive = true)
        store.upsertServer(server().copy(name = "Renamed"))
        assertEquals("Renamed", store.server(S1)!!.name)
        assertEquals(listOf(account().let { AccountSummary(it.serverId, it.userId, it.userName) }), store.accounts(S1).first())
        assertNotNull(store.activeAccount.first())
    }

    @Test fun onlyOneAccountIsActive() = runBlocking {
        store.upsertServer(server())
        store.upsertAccount(account(userId = U1), makeActive = true)
        store.upsertAccount(account(userId = U2), makeActive = true)
        assertEquals(U2, store.activeAccount.first()!!.userId)
        assertEquals(1, rawCount("active_account"))

        store.upsertAccount(account(userId = U3), makeActive = false)
        assertEquals(U2, store.activeAccount.first()!!.userId)

        store.setActive(S1, U3)
        assertEquals(U3, store.active()!!.userId)
        assertEquals(S1, store.active()!!.serverId)
        assertEquals(1, rawCount("active_account"))
    }

    @Test fun removingActiveAccountClearsActive() = runBlocking {
        store.upsertServer(server())
        store.upsertAccount(account(), makeActive = true)
        store.removeAccount(S1, U1)
        assertNull(store.activeAccount.first())
        assertNull(store.active())
        assertEquals(0, rawCount("active_account"))
    }

    @Test fun reUpsertingActiveAccountKeepsItActive() = runBlocking {
        store.upsertServer(server())
        store.upsertAccount(account(token = "old"), makeActive = true)
        store.upsertAccount(account(token = "new"), makeActive = false)
        assertEquals(U1, store.activeAccount.first()!!.userId)
        assertEquals("the new token is the live one", "new", store.cachedToken(S1, U1))
    }

    @Test fun settingUnknownAccountActiveIsRejected() = runBlocking {
        store.upsertServer(server())
        try {
            store.setActive(S1, "ghost")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains("ghost"))
        }
    }

    @Test fun malformedPinIsRejectedOnUpsertWithoutEchoingIt() = runBlocking {
        try {
            store.upsertServer(server(pin = "not-a-real-pin-SECRETISH"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("The certificate pin is not valid.", e.message)
        }
        assertNull(store.server(S1))
    }

    @Test fun corruptStoredPinSurfacesAsAbsentAndNextConnectionIsUntrusted() = runBlocking {
        store.upsertServer(server(pin = validPin))
        setColumn("servers", "pinnedSpkiSha256", "corrupt")

        val loaded = store.server(S1)!!
        assertNull(loaded.pinnedSpkiSha256)
        assertTrue(loaded.pinInvalid)
        assertTrue(store.servers.first().single().pinInvalid)
        try {
            ServerNet.anonymous(listOf(loaded.homeAddress), loaded, DeviceIdentity("d", "Pixel", "Shoalwatch", "0.1"))
            fail("expected AppError")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.TLS_UNTRUSTED, e.kind)
        }

        // Saving other changes must not silently drop the (damaged) pin and widen trust.
        store.upsertServer(loaded.copy(name = "Renamed"))
        assertTrue(store.server(S1)!!.pinInvalid)

        // Re-trusting with a fresh pin recovers.
        store.upsertServer(loaded.copy(pinnedSpkiSha256 = validPin, pinInvalid = false))
        val fixed = store.server(S1)!!
        assertFalse(fixed.pinInvalid)
        assertEquals(validPin, fixed.pinnedSpkiSha256)
    }

    @Test fun accountWithUnreadableTokenIsSkippedNotCrashed() = runBlocking {
        store.upsertServer(server())
        store.upsertAccount(account(), makeActive = true)
        setColumn("accounts", "accessToken", ByteArray(40))
        store.warmTokens() // unreadable tokens are found when the store warms (lists never decrypt)
        assertTrue(store.accounts(S1).first().isEmpty())
        assertNull(store.activeAccount.first())
        assertNull(store.active())
    }

    @Test fun serverWithUnreadableHeadersLoadsWithNone() = runBlocking {
        store.upsertServer(server())
        setColumn("servers", "customHeaders", ByteArray(40))
        assertEquals(emptyMap<String, String>(), store.server(S1)!!.customHeaders)
    }

    @Test fun toStringsRevealNothingSensitive() {
        assertEquals("Account(ben@$S1)", account().toString())
        val s = server().toString()
        assertFalse(s.contains("example")); assertFalse(s.contains("headervalue")); assertFalse(s.contains("X-Proxy-Key"))
    }

    @Test fun netConfigUsesBothAddressesAndServerSecuritySettings() {
        val s = server(pin = validPin).copy(allowCleartext = true)
        val c = ServerNet.anonymous(listOf(s.homeAddress, s.awayAddress, null), s, DeviceIdentity("d", "Pixel", "Shoalwatch", "0.1"))
        assertEquals(setOf("home.example:8920", "away.example:443"), c.authorities)
        assertTrue(c.allowCleartext)
        assertEquals(validPin, c.pinnedSpkiSha256)
        assertEquals("my-cert", c.clientCertAlias)
        assertEquals(mapOf("X-Proxy-Key" to "headervalue-12345"), c.customHeaders)
        assertFalse("Token=" in c.authHeader()!!)
    }

    @Test fun changesThatAffectClientsReportTheServerAndItsAddresses() = runBlocking {
        val s = server()
        store.upsertServer(s)
        assertEquals(S1, stale.last().first)
        assertTrue(stale.last().second.containsAll(listOf(s.homeAddress, s.awayAddress)))

        stale.clear(); store.upsertAccount(account(), makeActive = true)
        assertEquals(S1, stale.single().first)
        assertTrue(stale.single().second.contains(s.homeAddress))
        stale.clear()

        store.removeAccount(S1, U1)
        assertEquals(S1, stale.single().first)
        assertTrue(stale.single().second.contains(s.homeAddress))

        stale.clear(); store.removeServer(S1)
        assertEquals(S1, stale.single().first)
        assertTrue(stale.single().second.contains(s.awayAddress))
    }

    @Test fun upsertReportsTheOldAddressesToo() = runBlocking {
        store.upsertServer(server())
        stale.clear()
        store.upsertServer(server().copy(homeAddress = ServerAddress.parse("https://new.example")))
        val reported = stale.single().second.map { it.authority }
        assertTrue("home.example:8920" in reported && "new.example:443" in reported)
    }

    @Test fun removingAServerTellsTheOnRemovedHook() = runBlocking {
        val removed = mutableListOf<String>()
        val hooked = ServerStore(db, box, onServerRemoved = { removed += it })
        hooked.upsertServer(server())
        hooked.upsertAccount(account(), makeActive = false)
        hooked.removeAccount(S1, U1)
        assertTrue(removed.isEmpty())
        hooked.removeServer(S1)
        assertEquals(listOf(S1), removed)
    }

    @Test fun savingAServerWithNoAddressLeftTellsTheOnRemovedHook() = runBlocking {
        val gone = mutableListOf<String>()
        val hooked = ServerStore(db, box, onServerRemoved = { gone += it })
        hooked.upsertServer(server())
        assertTrue(gone.isEmpty())
        hooked.upsertServer(server().copy(homeAddress = null, awayAddress = null))
        assertEquals(listOf(S1), gone)
    }

    // ---- ids must already be normalised (review F9)

    private fun rejected(block: suspend () -> Unit): String? = try {
        runBlocking { block() }
        null
    } catch (e: IllegalArgumentException) {
        e.message
    }

    @Test fun aServerIdThatIsNotTheNormalisedGuidIsRefusedWithoutEchoingIt() {
        val sneaky = "SNEAKY-" + "x".repeat(10)
        for (bad in listOf(sneaky, "0123ABCD456789EF0123456789ABCDEF", "0123abcd-4567-89ef-0123-456789abcdef", "")) {
            val message = rejected { store.upsertServer(server(id = bad)) }
            assertNotNull("rejected", message)
            assertFalse("the id is not echoed", bad.isNotEmpty() && bad in message!!)
        }
        assertEquals(0, rawCount("servers"))
        assertNull(rejected { store.upsertServer(server(id = testId("good"))) })
    }

    @Test fun anAccountWithAnIdThatIsNotNormalisedIsRefused() {
        runBlocking { store.upsertServer(server(id = testId("s"))) }
        assertNotNull(rejected { store.upsertAccount(account(serverId = testId("s"), userId = "not-a-guid"), makeActive = false) })
        assertNotNull(rejected { store.upsertAccount(account(serverId = "not-a-guid", userId = testId("u")), makeActive = false) })
        assertNotNull(rejected { store.upsertAccount(account(serverId = testId("s"), userId = testId("u").uppercase()), makeActive = false) })
        assertEquals(0, rawCount("accounts"))
        assertNull(rejected { store.upsertAccount(account(serverId = testId("s"), userId = testId("u")), makeActive = false) })
    }
}
