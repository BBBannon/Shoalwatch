package app.shoalwatch.data

import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.KeyProvider
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Review I7: what the screens read (server lists, account lists, "who is signed in") never touches the Keystore,
 * and every Keystore operation that does happen (warming, saving, revoking, reading settings) runs on the
 * injected IO dispatcher, never on the caller's (main) thread.
 */
@RunWith(RobolectricTestRunner::class)
class TokenFreeReadsTest {
    /** Counts every use of the key (one per seal or open) and the thread it happened on; can be told to refuse. */
    private class CountingKeys : KeyProvider {
        private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val threads = CopyOnWriteArrayList<String>()
        @Volatile var forbidden = false
        override fun key(): SecretKey {
            check(!forbidden) { "a UI read touched the Keystore" }
            threads += Thread.currentThread().name
            return key
        }
    }

    private val keys = CountingKeys()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "test-io") }.asCoroutineDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore

    private val server = Server("s1", "Den", "10.10.3", ServerAddress.parse("https://den.example"), null, false, null, null, mapOf("X-Proxy" to "secret"))

    @Before fun open() {
        db = inMemoryDb()
        store = ServerStore(db, SecretBox(keys), io = io)
        runBlocking {
            store.upsertServer(server)
            store.upsertAccount(Account("s1", "u1", "ben", "tok-1"), makeActive = true)
            store.upsertAccount(Account("s1", "u2", "amy", "tok-2"), makeActive = false)
            store.warmTokens()
        }
    }

    @After fun close() {
        db.close()
        io.close()
    }

    @Test fun whatTheScreensReadNeverDecryptsAnything() = runBlocking {
        keys.forbidden = true
        val catalog = StoreCatalog(store)
        assertEquals(listOf("Den"), catalog.servers.first().map { it.name })
        assertEquals(listOf("amy", "ben"), catalog.accounts("s1").first().map { it.userName })
        assertEquals(AccountSummary("s1", "u1", "ben"), catalog.activeAccount.first())
        assertEquals(AccountSummary("s1", "u1", "ben"), catalog.awaitActive())
        assertEquals("the live token lookup needs no decryption either", "tok-1", store.cachedToken("s1", "u1"))
    }

    @Test fun everyKeystoreOperationRunsOnTheIoDispatcher() = runBlocking {
        val caller = Thread.currentThread().name
        keys.threads.clear()
        store.warmTokens()
        store.token("s1", "u1")
        store.tokens("s1")
        store.server("s1")
        store.servers.first()
        store.upsertAccount(Account("s1", "u3", "cat", "tok-3"), makeActive = false)
        store.upsertServer(server.copy(name = "Den 2"))
        assertTrue(keys.threads.isNotEmpty())
        assertTrue("never on the caller's thread ($caller): ${keys.threads}", keys.threads.all { it.startsWith("test-io") })
    }

    @Test fun anAccountWhoseTokenCannotBeReadIsStillHiddenAfterTheWarm() = runBlocking {
        // A row sealed with a different key (as after a lost Keystore key).
        val stranger = SecretBox { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        db.accountDao().upsert(app.shoalwatch.data.db.AccountEntity("s1", "u9", "ghost", stranger.seal("tok-9")))
        store.warmTokens()
        keys.forbidden = true
        assertEquals(listOf("amy", "ben"), store.accounts("s1").first().map { it.userName })
        assertNull(store.cachedToken("s1", "u9"))
    }
}
