package app.shoalwatch.data.playback

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
