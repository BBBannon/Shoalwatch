package app.shoalwatch.data.playback

import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.data.servers.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one record of who is playing: ended at once, exactly once, by the store hooks and the active-session flow. */
class ActivePlaybackTest {
    private val signedIn = mutableSetOf(BEN, AMY)
    /** Work posted to the main thread from elsewhere, run by [runMain]; [onMain] says whether the caller is already on it. */
    private val posted = ArrayDeque<() -> Unit>()
    private var onMain = true
    private val active = ActivePlayback({ it in signedIn }, { block -> if (onMain) block() else posted.addLast(block) })
    private val ended = mutableListOf<String>()

    private fun begin(s: Session, name: String) = active.begin(s) { reason -> ended += "$name:$reason" }
    private fun runMain() { while (posted.isNotEmpty()) posted.removeFirst()() }

    @Test fun endForTheRegisteredAccountEndsItSynchronouslyAndExactlyOnce() {
        begin(BEN, "ben")
        active.end(BEN.serverId, BEN.userId)
        assertEquals(listOf("ben:SIGNED_OUT"), ended)
        active.end(BEN.serverId, BEN.userId)
        active.sessionChanged(AMY)
        assertEquals(listOf("ben:SIGNED_OUT"), ended)
    }

    @Test fun endForAnotherAccountDoesNothing() {
        begin(BEN, "ben")
        active.end(AMY.serverId, AMY.userId)
        active.end("0123456789abcdef0123456789abcdef")
        assertTrue(ended.isEmpty())
    }

    @Test fun removingTheServerEndsAnySessionOfIt() {
        begin(AMY, "amy")
        active.end(AMY.serverId)
        assertEquals(listOf("amy:SIGNED_OUT"), ended)
    }

    @Test fun releaseThenEndDoesNothing() {
        val token = begin(BEN, "ben")
        active.release(token)
        active.end(BEN.serverId, BEN.userId)
        active.sessionChanged(AMY)
        assertTrue(ended.isEmpty())
    }

    @Test fun anOldTokenCannotReleaseANewerPlayback() {
        val old = begin(BEN, "first")
        begin(BEN, "second")
        active.release(old)
        active.end(BEN.serverId, BEN.userId)
        assertEquals(listOf("first:SESSION_CHANGED", "second:SIGNED_OUT"), ended)
    }

    @Test fun registeringASecondSessionEndsTheFirst() {
        begin(BEN, "ben")
        begin(AMY, "amy")
        assertEquals(listOf("ben:SESSION_CHANGED"), ended)
    }

    @Test fun anotherActiveSessionEndsPlaybackAsSessionChangedWhileTheOldAccountIsStillSignedIn() {
        begin(BEN, "ben")
        active.sessionChanged(BEN)
        assertTrue("the same session is no change", ended.isEmpty())
        active.sessionChanged(AMY)
        assertEquals(listOf("ben:SESSION_CHANGED"), ended)
    }

    @Test fun aSessionChangeBecauseTheAccountWentIsSignedOut() {
        begin(BEN, "ben")
        signedIn -= BEN // the token is gone before the active account changes
        active.sessionChanged(null)
        assertEquals(listOf("ben:SIGNED_OUT"), ended)
    }

    @Test fun endFromAnotherThreadHopsToTheMainThread() {
        begin(BEN, "ben")
        onMain = false
        active.end(BEN.serverId, BEN.userId)
        assertTrue("not on the caller's thread", ended.isEmpty())
        runMain()
        assertEquals(listOf("ben:SIGNED_OUT"), ended)
    }

    @Test fun aHopThatArrivesAfterANewPlaybackBeganDoesNotEndTheNewOne() {
        begin(BEN, "ben")
        onMain = false
        active.end(BEN.serverId, BEN.userId)
        onMain = true
        val old = begin(AMY, "amy") // registered before the hop ran: Ben's playback ends as SESSION_CHANGED here
        runMain()
        assertEquals(listOf("ben:SESSION_CHANGED"), ended)
        active.release(old)
    }
}
