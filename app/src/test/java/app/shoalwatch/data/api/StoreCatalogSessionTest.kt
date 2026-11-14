package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class StoreCatalogSessionTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun theActiveSessionWaitsForTheWarmGateThenFollowsTheActiveAccountWithoutAToken() = runBlocking {
        val db = inMemoryDb()
        try {
            val store = ServerStore(db, SecretBox { key })
            store.upsertServer(Server(S1, "Den", "10.10.3", ServerAddress.parse("https://example.org"), null, false, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            val seen = java.util.concurrent.CopyOnWriteArrayList<Session?>()
            val job = launch(kotlinx.coroutines.Dispatchers.Default) { StoreCatalog(store).activeSession().toList(seen) }
            delay(100)
            assertTrue("nothing before the first warm", seen.isEmpty())
            store.warmTokens()
            while (seen.isEmpty()) delay(10)
            assertEquals(Session(S1, U1), seen.first())
            store.removeAccount(S1, U1)
            while (seen.size < 2) delay(10)
            assertEquals(null, seen.last())
            job.cancel()
        } finally {
            db.close()
        }
    }
}
