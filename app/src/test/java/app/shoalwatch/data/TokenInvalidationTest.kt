package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.data.servers.StoredToken
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** A rejected sign-in (401) takes the account out of use everywhere at once, without deleting its row. */
@RunWith(RobolectricTestRunner::class)
class TokenInvalidationTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val address = ServerAddress.parse("https://example.org")
    private val stale = mutableListOf<String>()
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore

    @Before fun open() {
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key }, { id, _ -> stale += id })
        runBlocking {
            store.upsertServer(Server(S1, "Den", "10.10.3", address, null, false, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.upsertAccount(Account(S1, U2, "amy", "tok-2"), makeActive = false)
            store.warmTokens()
        }
        stale.clear()
    }

    @After fun close() = db.close()

    @Test fun anInvalidatedAccountIsOutOfUseEverywhereButItsRowIsKept() = runBlocking {
        store.invalidateToken(S1, U1)
        assertNull(store.cachedToken(S1, U1))
        assertEquals("the other account is untouched", "tok-2", store.cachedToken(S1, U2))
        assertEquals(listOf(U2), store.accounts(S1).first().map { it.userId })
        assertNull(store.activeAccount.first())
        assertNull(store.active())
        assertNull(StoreCatalog(store).activeSession().first())
        assertTrue("the row stays; signing in again overwrites it", store.token(S1, U1) is StoredToken.Found)
        assertEquals("cached clients for the server are dropped", listOf(S1), stale)
    }

    @Test fun aReloadDoesNotBringTheRejectedTokenBack() = runBlocking {
        store.invalidateToken(S1, U1)
        store.warmTokens()
        assertNull(store.cachedToken(S1, U1))
        assertNull(store.active())
    }

    @Test fun signingInAgainRestoresTheAccount() = runBlocking {
        store.invalidateToken(S1, U1)
        store.upsertAccount(Account(S1, U1, "ben", "tok-new"), makeActive = true)
        assertEquals("tok-new", store.cachedToken(S1, U1))
        assertEquals(Session(S1, U1), StoreCatalog(store).activeSession().first())
        store.warmTokens()
        assertEquals("tok-new", store.cachedToken(S1, U1))
    }

    @Test fun aRejectionOfAnOlderSignInDoesNotSignOutTheNewOne() = runBlocking {
        val before = store.tokenGeneration(S1, U1)
        store.upsertAccount(Account(S1, U1, "ben", "tok-new"), makeActive = true)
        stale.clear()
        store.invalidateToken(S1, U1, ifGeneration = before)
        assertEquals("tok-new", store.cachedToken(S1, U1))
        assertTrue("nothing was evicted", stale.isEmpty())
        store.invalidateToken(S1, U1, ifGeneration = store.tokenGeneration(S1, U1))
        assertNull(store.cachedToken(S1, U1))
    }
}
