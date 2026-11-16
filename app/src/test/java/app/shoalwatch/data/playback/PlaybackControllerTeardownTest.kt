package app.shoalwatch.data.playback

import app.shoalwatch.ui.browse.AMY
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared-tablet rule: when the session changes or the account goes, everything of the old one is gone at once. */
internal class PlaybackControllerTeardownTest : PlaybackControllerTestBase() {
    @Test fun signOutReleasesAtOnceAndSendsNothing() = runTest {
        val c = controller(); openAndPlay(c)
        activePlayback.end(session.serverId, session.userId) // what ServerStore's onAccountGone does
        assertTrue(engine.released); assertNull(engine.metadataTitle)
        val s = c.state.value
        assertNull(s.session); assertNull(s.title); assertNull(s.itemId); assertNull(s.next); assertTrue(s.audioTracks.isEmpty())
        runCurrent(); assertTrue(reports.calls.none { it.startsWith("stopped") })
    }

    @Test fun accountSwitchSendsTheOldSessionsStopAsTheOldSessionOnly() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        c.end(EndReason.SESSION_CHANGED); runCurrent()
        assertEquals(listOf(session), reports.stopSessions)
        assertEquals(setOf(session), reports.sessions.toSet())
    }

    @Test fun anotherProfileBecomingActiveEndsPlaybackAtOnce() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        activePlayback.sessionChanged(AMY)
        assertTrue(engine.released); assertEquals(PlaybackState.CLOSED, c.state.value)
        runCurrent()
        assertEquals(listOf("stopped@30000"), stopCalls); assertEquals(listOf(session), reports.stopSessions)
    }

    @Test fun aNormalCloseSendsTheStopAndReleasesTheEngine() = runTest {
        val c = controller(); openAndPlay(c); at(30_000)
        c.end(null); runCurrent()
        assertTrue(engine.released); assertNull(engine.metadataTitle); assertEquals(listOf("stopped@30000"), stopCalls)
        c.open(ep2, StartFrom.RESUME); runCurrent()
        assertEquals("an ended controller loads nothing", 1, engine.loads.size)
    }

    @Test fun aLateResolveAfterEndIsDiscarded() = runTest {
        val held = CompletableDeferred<Unit>()
        gateway.infoGate = held
        val c = controller(); c.open(ep1, StartFrom.RESUME); runCurrent()
        assertEquals(1, playbackInfos)
        c.end(EndReason.SIGNED_OUT)
        held.complete(Unit); advanceTimeBy(1_000); runCurrent()
        assertTrue(engine.loads.isEmpty()); assertEquals(PlaybackState.CLOSED, c.state.value)
    }

    @Test fun aStaleScreenCannotStartAnotherProfilesPlayback() = runTest {
        activePlayback.sessionChanged(AMY) // handled while nothing played
        val c = controller(); c.open(ep1, StartFrom.RESUME); advanceTimeBy(1_000); runCurrent()
        assertTrue(engine.loads.isEmpty()); assertEquals(0, playbackInfos)
        assertEquals(PlaybackState.CLOSED, c.state.value)
    }

    @Test fun theScreenReachesOnlyTheEnginesVideoOutputAndEndingLetsTheSurfaceGo() = runTest {
        val c = controller(); openAndPlay(c)
        assertSame(engine.video, c.video)
        c.video.attach(FakeVideoOutput.holder())
        assertTrue(engine.video.attached)
        c.end(null)
        assertFalse(engine.video.attached)
    }
}
