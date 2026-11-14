package app.shoalwatch.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator

/** The cold-start gate and cancellation-safe removal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WarmGateTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val blockOpen = AtomicBoolean(false)
    private val opening = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val box = SecretBox {
        if (blockOpen.getAndSet(false)) { // only the first read after arming blocks
            opening.countDown()
            release.await()
        }
        key
    }
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private val server = Server("s1", "Den", "10.10.3", ServerAddress.parse("https://den.example"), null, false, null, null, emptyMap())

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ServerStore(db, box)
        runBlocking { store.upsertServer(server) }
    }

    @After fun close() { release.countDown(); db.close() }

    @Test fun awaitWarmedWaitsForTheFirstWarm() = runTest {
        val gate = async { store.awaitWarmed() }
        runCurrent()
        assertFalse("must not complete before a warm", gate.isCompleted)
        store.warmTokens()
        runCurrent()
        assertTrue(gate.isCompleted)
    }

    @Test fun aFailingWarmDoesNotCrashAndStillOpensTheGate() = runBlocking {
        db.close() // every query now fails (a cold start: nothing is cached yet)
        val result = runCatching { store.warmTokens() }
        assertTrue("warm must not throw: ${result.exceptionOrNull()?.javaClass}", result.isSuccess)
        withTimeout(1_000) { store.awaitWarmed() }
        // Fails closed: the live lookup is empty, so authenticated requests are refused as NOT_SIGNED_IN.
        assertNull(store.cachedToken("s1", "u1"))
    }

    @Test fun aFailedWarmIsRetriedOnRequestAndASuccessfulOneIsNot() = runBlocking {
        store.upsertAccount(Account("s1", "u1", "ben", "tok"), makeActive = false)
        var loads = 0
        var failing = true
        val cold = ServerStore(db, box, accountSource = { loads++; if (failing) throw IllegalStateException("db") else db.accountDao().getAll() })
        cold.warmTokens()
        assertNull(cold.cachedToken("s1", "u1"))
        assertTrue("still failing is reported", cold.retryWarmIfFailed())
        failing = false
        assertFalse("healed", cold.retryWarmIfFailed())
        assertEquals("tok", cold.cachedToken("s1", "u1"))
        cold.retryWarmIfFailed()
        assertEquals("a warm that worked is not repeated", 3, loads)
    }

    @Test fun anAccountReadWaitsForTheWarm() = runBlocking(Dispatchers.Default) {
        store.upsertAccount(Account("s1", "u1", "ben", "tok"), makeActive = true)
        val read = async { store.awaitActive() }
        delay(300) // real time: Room answers on its own threads
        assertFalse("must wait for the warm", read.isCompleted)
        store.warmTokens()
        assertEquals("u1", withTimeout(5_000) { read.await() }?.userId)
    }

    @Test fun cancellingARemovalWhileItWaitsForTheLockStillClearsTheToken() = runBlocking(Dispatchers.Default) {
        store.upsertAccount(Account("s1", "u1", "ben", "tok-1"), makeActive = false)
        blockOpen.set(true)
        val warm = launch { store.warmTokens() } // holds the lock, blocked inside the key provider
        withTimeout(5_000) { while (opening.count > 0L) delay(5) }
        val removal = launch { store.removeAccount("s1", "u1") }
        delay(100) // the removal is now queued behind the warm
        removal.cancel()
        release.countDown()
        warm.join()
        removal.join()
        assertNull(store.cachedToken("s1", "u1"))
    }

    @Test fun cancellingAServerRemovalWhileItWaitsForTheLockStillClearsItsTokens() = runBlocking(Dispatchers.Default) {
        store.upsertAccount(Account("s1", "u1", "ben", "tok-1"), makeActive = false)
        blockOpen.set(true)
        val warm = launch { store.warmTokens() }
        withTimeout(5_000) { while (opening.count > 0L) delay(5) }
        val removal = launch { store.removeServer("s1") }
        delay(100)
        removal.cancel()
        release.countDown()
        warm.join()
        removal.join()
        assertNull(store.cachedToken("s1", "u1"))
        assertNull(store.server("s1"))
    }
}
