package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.api.ServerApiTestBase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Milestone 2 check I4: a sign-in the server rejected (401) stays rejected after a restart, so its dead token is never loaded or
 * sent again, until the user signs in again.
 */
class RejectedSignInTest : ServerApiTestBase() {
    private fun restart(): ServerStore = ServerStore(db, SecretBox { key }, clients::evict).also { runBlocking { it.warmTokens() } }

    private fun rejectedRows(): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM rejected_sign_ins").use { it.moveToFirst(); it.getInt(0) }

    @Test fun aRejectedTokenIsNotLoadedAfterARestartAndNoRequestCarriesIt() {
        runBlocking { store.invalidateToken(S1, U1) }
        assertEquals(1, rejectedRows())
        val after = restart()
        assertNull("the dead token is not loaded", after.cachedToken(S1, U1))
        assertTrue("nor listed as usable", (S1 to U1) !in after.usableAccounts.value)
        val api = ServerApi(after, selector, clients, device)
        val before = web.requestCount
        val e = failure { runBlocking { api.get(session, "/Things", emptyList(), things()) } }
        assertEquals(AppError.Kind.NOT_SIGNED_IN, e.kind)
        assertEquals("zero requests for it", before, web.requestCount)
    }

    @Test fun signingInAgainBringsItBack() {
        runBlocking { store.invalidateToken(S1, U1) }
        val after = restart()
        runBlocking { after.upsertAccount(Account(S1, U1, "ben", "tok-new"), makeActive = true) }
        assertEquals(0, rejectedRows())
        assertEquals("tok-new", restart().cachedToken(S1, U1))
    }

    @Test fun removingTheAccountTakesTheRecordWithIt() {
        runBlocking { store.invalidateToken(S1, U1) }
        runBlocking { store.removeAccount(S1, U1) }
        assertEquals(0, rejectedRows())
    }

    @Test fun aRejectionOfAnOlderSignInChangesNothing() {
        val old = store.tokenGeneration(S1, U1)
        runBlocking { store.upsertAccount(Account(S1, U1, "ben", "tok-2"), makeActive = true) }
        runBlocking { store.invalidateToken(S1, U1, ifGeneration = old) }
        assertEquals(0, rejectedRows())
        assertEquals("tok-2", restart().cachedToken(S1, U1))
    }

    @Test fun serverStoreStaysWithinItsLineBudget() {
        val lines = File("src/main/java/app/shoalwatch/data/servers/ServerStore.kt").readLines().size
        assertTrue("ServerStore.kt has $lines lines", lines <= 396)
    }
}
