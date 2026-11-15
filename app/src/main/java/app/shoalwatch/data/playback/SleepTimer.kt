package app.shoalwatch.data.playback

import app.shoalwatch.core.MonotonicClock

/** What the user can pick for the sleep timer. */
enum class SleepChoice(internal val minutes: Long?) { OFF(null), MIN_15(15), MIN_30(30), MIN_45(45), MIN_60(60), END_OF_ITEM(null) }

/** Pure: a timed choice ends by the monotonic clock (so changing the phone's clock does not move it); END_OF_ITEM is the controller's. */
object SleepTimer {
    fun start(choice: SleepChoice, nowMs: Long): SleepState = when (choice) {
        SleepChoice.OFF -> SleepState.Off
        SleepChoice.END_OF_ITEM -> SleepState.EndOfItem
        else -> SleepState.Minutes(nowMs + checkNotNull(choice.minutes) * 60_000)
    }

    fun isDue(state: SleepState, nowMs: Long): Boolean = state is SleepState.Minutes && nowMs >= state.endsAtMonoMs
}

/** The controller's sleep timer: the user's choice, and taking it when it is due (each due choice is taken once, then it is off). */
internal class SleepControl(private val clock: MonotonicClock) {
    var state: SleepState = SleepState.Off
        private set

    fun set(choice: SleepChoice) { state = SleepTimer.start(choice, clock.nowMs()) }

    val atEndOfItem: Boolean get() = state is SleepState.EndOfItem

    /** A timed choice whose time has come: true once, and the timer is off. */
    fun takeDue(): Boolean = SleepTimer.isDue(state, clock.nowMs()).also { if (it) state = SleepState.Off }

    /** The item ended with "end of this episode" chosen: true once, and the timer is off. */
    fun takeEndOfItem(): Boolean = atEndOfItem.also { if (it) state = SleepState.Off }
}
