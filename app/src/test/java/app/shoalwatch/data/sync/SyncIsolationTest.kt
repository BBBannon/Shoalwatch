package app.shoalwatch.data.sync

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.api.ServerApiTestBase
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.api.LibraryGatewayImpl
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.api.SyncGatewayImpl
import app.shoalwatch.testId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Two accounts on one server, real `ServerApi` and OkHttp: every request carries the row's own account's token and user id, and nothing more of an account once it is removed. */
class SyncIsolationTest : ServerApiTestBase() {
    private class Seen(val token: String?, val userId: String?, val method: String, val path: String)

    private val a = Session(S1, U1)
    private val b = Session(S1, U2)
    private val tokens = mapOf(U1 to "tok-1", U2 to "tok-2")
    private val seen = CopyOnWriteArrayList<Seen>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var onUpdate: (RecordedRequest) -> Unit = {}
    private lateinit var outbox: OutboxStore
    private lateinit var sender: SyncSender

    @Before fun twoAccounts() {
        runBlocking { store.upsertAccount(Account(S1, U2, "anna", "tok-2"), makeActive = false) }
        outbox = OutboxStore(db, System::currentTimeMillis)
        val landing = object : SyncLanding {
            override suspend fun confirmed(s: Session, seq: Long) { outbox.take(seq) }
            override suspend fun overruled(s: Session, seq: Long) { outbox.take(seq) }
        }
        val gateway = SyncGatewayImpl(api, LibraryGatewayImpl(api))
        sender = SyncSender(outbox, gateway, landing, { store.cachedToken(it.serverId, it.userId) != null }, store::awaitWarmed, scope)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val token = request.getHeader("Authorization")?.let { Regex("Token=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
                seen += Seen(token, request.requestUrl!!.queryParameter("userId"), request.method!!, request.requestUrl!!.encodedPath)
                if (request.method == "GET") return json("""{"Played":false,"PlayCount":0,"PlaybackPositionTicks":0,"IsFavorite":false}""")
                onUpdate(request)
                return MockResponse().setResponseCode(204)
            }
        }
    }

    @After fun stop() = scope.cancel()

    private fun enqueueInterleaved(perAccount: Int) = runBlocking {
        repeat(perAccount) { i ->
            for (s in listOf(a, b)) {
                val item = testId("${s.userId}-$i")
                outbox.enqueue(s, Change(item, ItemKind.MOVIE, ChangeKind.PLAYED, flag = true, occurredAt = 1), OutboxStore.Marks(listOf(item), emptyList()), emptyList())
            }
        }
    }

    @Test fun everyRequestCarriesItsOwnRowsAccount() {
        enqueueInterleaved(4)
        assertEquals(DrainResult.EMPTY, runBlocking { withTimeout(20_000) { sender.drainAll() } })
        assertEquals(16, seen.size)
        for (r in seen) {
            assertTrue(r.userId == U1 || r.userId == U2)
            assertEquals("token and userId belong together", tokens[r.userId], r.token)
        }
        for ((user, n) in seen.groupingBy { it.userId }.eachCount()) assertEquals("$user", 8, n)
        assertEquals(0, count())
    }

    @Test fun afterRemovingAnAccountNotOneMoreRequestCarriesItsTokenAndTheOtherGoesOn() {
        enqueueInterleaved(4)
        var removedAt = -1
        onUpdate = { request ->
            if (request.requestUrl!!.queryParameter("userId") == U2 && removedAt < 0) {
                removedAt = seen.size
                runBlocking { store.removeAccount(S1, U2) }
                sender.forget(S1, U2)
            }
        }
        runBlocking { withTimeout(20_000) { sender.drainAll() } }
        runBlocking { withTimeout(20_000) { sender.kick(a).await() } } // whatever A has left
        assertTrue("B was removed during its drain", removedAt > 0)
        val after = seen.drop(removedAt)
        assertTrue("not one more request as B", after.none { it.token == "tok-2" || it.userId == U2 })
        assertEquals("A's four rows were all sent", 8, seen.count { it.userId == U1 })
        for (r in seen) assertEquals(tokens[r.userId], r.token)
        assertEquals(0, count())
    }

    private fun count(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_outbox").use { it.moveToFirst(); it.getInt(0) }
}
