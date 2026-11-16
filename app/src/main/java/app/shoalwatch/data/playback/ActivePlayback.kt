package app.shoalwatch.data.playback

import android.os.Handler
import android.os.Looper
import app.shoalwatch.data.servers.Session

/** Why playback was ended from outside: another session took over (the old account still signed in), or the account or server went. */
enum class EndReason { SESSION_CHANGED, SIGNED_OUT }

/**
 * The one record of who is playing (shared tablet: see "Progress reports and teardown" in docs/ARCHITECTURE.md). The
 * controller registers ([begin], on the main thread) and releases; the graph's store hooks ([end]) and the active-session
 * flow ([sessionChanged]) end it. Ending clears the record first and then calls `onEnd` exactly once, on the main thread:
 * synchronously when the caller is already on it, otherwise posted there. The controller's `onEnd` must release the engine,
 * the media session, the queue, the position and the title at once; a final stop for the old session is sent only for
 * [EndReason.SESSION_CHANGED], and only as that session.
 *
 * Fails closed: a [begin] for anything but the active session last given to [sessionChanged] (another profile, nobody, or
 * before the active session is known at all) is never registered and ends at once through its own `onEnd`, before anything
 * can play. The active-session flow says each change only once, so a screen still holding an old session must not start it.
 *
 * [isSignedIn]: whether that account still has its sign-in on this phone, so a change of active session caused by the
 * account going is [EndReason.SIGNED_OUT], never a reason to send anything. [onMain]: runs a block on the main thread.
 */
class ActivePlayback(
    private val isSignedIn: (Session) -> Boolean,
    private val onMain: (() -> Unit) -> Unit = ::runOnMain,
) {
    class Token internal constructor(internal val session: Session)

    private class Holder(val token: Token, val onEnd: (EndReason) -> Unit)

    /** Main thread only. */
    private var current: Holder? = null

    /** Main thread only: the session [sessionChanged] last named; [activeKnown] false until it has been told once. */
    private var active: Session? = null
    private var activeKnown = false

    /**
     * Main thread. A playback already registered is ended first (another one is taking over). A [session] that is not the active
     * one is refused: `onEnd` is called at once and the returned token is never registered.
     */
    fun begin(session: Session, onEnd: (EndReason) -> Unit): Token {
        val token = Token(session)
        if (!activeKnown || active != session) {
            onEnd(reasonFor(session))
            return token
        }
        val old = current
        current = Holder(token, onEnd)
        old?.let { it.onEnd(reasonFor(it.token.session)) }
        return token
    }

    /** Whether a playback is registered now (for tests). */
    internal val registered: Boolean get() = current != null

    /** Main thread. Only the holder's own token releases it. */
    fun release(token: Token) {
        if (current?.token === token) current = null
    }

    /** Account ([userId]) or whole server gone: ends synchronously on the main thread if it is that session's. */
    fun end(serverId: String, userId: String? = null) = onMain {
        endIf(EndReason.SIGNED_OUT) { it.serverId == serverId && (userId == null || it.userId == userId) }
    }

    /** The active session is now [active] (null: none). Playback of any other session ends. */
    fun sessionChanged(active: Session?) = onMain {
        this.active = active
        activeKnown = true
        val playing = current?.token?.session ?: return@onMain
        if (playing != active) endIf(reasonFor(playing)) { it == playing }
    }

    private fun endIf(reason: EndReason, matches: (Session) -> Boolean) {
        val holder = current ?: return
        if (!matches(holder.token.session)) return
        current = null
        holder.onEnd(reason)
    }

    private fun reasonFor(old: Session) = if (isSignedIn(old)) EndReason.SESSION_CHANGED else EndReason.SIGNED_OUT
}

private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/** At once on the main thread, else posted to it. */
private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}
