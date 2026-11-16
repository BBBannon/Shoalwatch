package app.shoalwatch.data.admin

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BrowserDashboardTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val connections = FakeConnections()
    private val dashboard = BrowserDashboard(connections, Dispatchers.Unconfined)
    private val session = Session(S1, U1)

    private fun openAt(text: String?): Pair<DashboardResult, Intent?> {
        connections.onlineAnswer = text?.let { Connection.Online(address(it), isHome = true) }
        val result = runBlocking { dashboard.open(session, app) }
        return result to shadowOf(app).nextStartedActivity
    }

    @Test fun opensTheWebClientOnTheVerifiedAddress() {
        val (result, intent) = openAt("http://127.0.0.1:8096")
        assertEquals(DashboardResult.OPENED, result)
        intent!!
        assertEquals("http://127.0.0.1:8096/web/", intent.dataString)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull("no extras", intent.extras)
        assertTrue("not started from an activity", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun keepsTheBasePath() {
        assertEquals("https://example.org/jellyfin/web/", openAt("https://example.org/jellyfin").second!!.dataString)
    }

    @Test fun offlineOpensNothing() {
        val (result, intent) = openAt(null)
        assertEquals(DashboardResult.OFFLINE, result)
        assertNull(intent)
    }

    @Test fun noBrowser() {
        shadowOf(app).checkActivities(true)
        assertEquals(DashboardResult.NO_BROWSER, openAt("http://127.0.0.1:8096").first)
    }

    @Test fun nothingOfTheSignInIsPassed() {
        val intent = openAt("http://127.0.0.1:8096").second!!
        val text = intent.toUri(Intent.URI_INTENT_SCHEME)
        for (secret in listOf("tok-1", "Token", "api_key", "ben", U1)) assertFalse(text.contains(secret))
        assertNull(intent.data!!.query)
        assertNull(intent.data!!.fragment)
    }
}
