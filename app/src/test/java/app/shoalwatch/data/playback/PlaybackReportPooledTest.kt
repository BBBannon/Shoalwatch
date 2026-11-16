package app.shoalwatch.data.playback

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playback reports keep OkHttp's own recovery of a kept-alive connection the server dropped: a progress report written to a
 * stale pooled socket is sent again on a fresh connection and succeeds, as before Milestone 6. (Only admin actions opt in to
 * being sent once; see `AdminGatewayRulesTest.anActionOnAPooledConnectionIsSentOnce`.)
 */
class PlaybackReportPooledTest : PlaybackGatewayTestBase() {
    private val report = PlaybackReport(ITEM, SOURCE, PSID, PlayMethod.DIRECT_PLAY, positionMs = 61_500, paused = false, audioIndex = 1, subtitleIndex = null)

    @Test fun aReportOnAStalePooledConnectionIsRecovered() {
        web.enqueue(MockResponse().setResponseCode(204)) // keeps the connection alive in the pool
        runBlocking { gateway.reportProgress(session, report) }
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.reportProgress(session, report) } // does not throw: recovered on a fresh connection
        assertEquals("the first report, the dropped one and its recovery", 3, web.requestCount)
    }

    @Test fun aPingAndAStoppedReportAreRecoveredToo() {
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.reportStart(session, report) }
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.ping(session, PSID) }
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.reportStopped(session, report) }
        assertEquals(5, web.requestCount)
    }
}
