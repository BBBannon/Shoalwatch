package app.shoalwatch.data.api

import app.shoalwatch.data.servers.ApproveOutcome
import app.shoalwatch.data.servers.QuickConnectApprover
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Approving a Quick Connect code from Settings (Milestone 10), through the real `ServerApi` against MockWebServer. */
class QuickConnectApproverTest : ServerApiTestBase() {
    private val active by lazy { MutableStateFlow<Session?>(session) }
    private val approver by lazy { QuickConnectApprover(api, active, activeWaitMs = 300) }

    private fun approve(code: String) = runBlocking { approver.approve(session, code) }

    @Test fun theCodeIsCheckedThenSentOnceAsAQueryValue() {
        web.enqueue(json("true"))
        web.enqueue(json("true"))
        assertEquals(ApproveOutcome.APPROVED, approve(" 123 456 "))
        assertEquals("/QuickConnect/Enabled", web.takeRequest(3, TimeUnit.SECONDS)!!.path)
        val r = web.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", r.method)
        assertEquals("/QuickConnect/Authorize?code=123456", r.path)
    }

    @Test fun aBadCodeSendsNothing() {
        for (bad in listOf("", "12345", "1234567", "12345!", "../../x")) assertEquals(bad, ApproveOutcome.BAD_CODE, approve(bad))
        assertEquals(0, web.requestCount)
        assertNull(QuickConnectApprover.normalise("12 34"))
        assertEquals("Ab12Cd", QuickConnectApprover.normalise("Ab-12-Cd"))
    }

    @Test fun aSwitchedOffServerIsToldApartAndNotAskedToAuthorise() {
        web.enqueue(json("false"))
        assertEquals(ApproveOutcome.SWITCHED_OFF, approve("123456"))
        assertEquals(1, web.requestCount)
    }

    @Test fun anUnknownCodeIsNotFound() {
        web.enqueue(json("true"))
        web.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ApproveOutcome.NOT_FOUND, approve("123456"))
    }

    @Test fun refusedAndFailedAreSaid() {
        web.enqueue(json("true"))
        web.enqueue(MockResponse().setResponseCode(403))
        assertEquals(ApproveOutcome.REFUSED, approve("123456"))
        web.enqueue(json("true"))
        web.enqueue(MockResponse().setResponseCode(500))
        assertEquals(ApproveOutcome.FAILED, approve("123456"))
        assertEquals("never retried", 4, web.requestCount)
    }

    @Test fun nothingIsAuthorisedOnceTheProfileIsNoLongerActive() {
        web.enqueue(json("true"))
        active.value = null
        assertEquals(ApproveOutcome.REFUSED, approve("123456"))
        assertEquals(1, web.requestCount)
    }

    /** Emulator walkthrough, 21/11/2026: the first approval after an app start failed, as the active profile was not yet heard. */
    @Test fun anActiveProfileHeardJustAfterTheTapStillApproves() = runBlocking {
        web.enqueue(json("true"))
        web.enqueue(json("true"))
        active.value = null
        launch { delay(100); active.value = session }
        assertEquals(ApproveOutcome.APPROVED, approver.approve(session, "123456"))
        assertEquals(2, web.requestCount)
    }

    @Test fun offlineSendsNothing() {
        selector.answer = { null }
        assertEquals(ApproveOutcome.OFFLINE, approve("123456"))
        assertEquals(0, web.requestCount)
    }
}
