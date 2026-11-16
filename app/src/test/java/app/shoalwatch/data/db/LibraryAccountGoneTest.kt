package app.shoalwatch.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** The store tells whoever remembers per-account state (the library's remembered failures) when an account is removed or signed out. */
@RunWith(RobolectricTestRunner::class)
class LibraryAccountGoneTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    private val gone = mutableListOf<Pair<String, String>>()
    private val signedIn = mutableListOf<Pair<String, String>>()
    private val store = ServerStore(db, SecretBox { key }, onAccountGone = { server, user -> gone += server to user }, onAccountSignedIn = { server, user -> signedIn += server to user })

    @After fun close() = db.close()

    @Test fun removingAnAccountAndRejectingATokenBothAnnounceIt() = runBlocking {
        store.upsertServer(Server(S1, "Den", "10.10.3", ServerAddress.parse("https://den.example"), null, false, null, null, emptyMap()))
        store.upsertAccount(Account(S1, U1, "ann", "tok-1"), makeActive = true)
        store.upsertAccount(Account(S1, U2, "bob", "tok-2"), makeActive = false)
        store.removeAccount(S1, U2)
        store.invalidateToken(S1, U1)
        assertEquals(listOf(S1 to U2, S1 to U1), gone)
    }

    @Test fun signingInAgainAnnouncesIt() = runBlocking {
        store.upsertServer(Server(S1, "Den", "10.10.3", ServerAddress.parse("https://den.example"), null, false, null, null, emptyMap()))
        store.upsertAccount(Account(S1, U1, "ann", "tok-1"), makeActive = true)
        store.invalidateToken(S1, U1)
        store.upsertAccount(Account(S1, U1, "ann", "tok-2"), makeActive = true)
        assertEquals(listOf(S1 to U1, S1 to U1), signedIn)
    }
}
