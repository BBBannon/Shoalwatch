package app.shoalwatch

import app.shoalwatch.core.MonotonicClock

/** A monotonic clock a test moves by hand. */
class TestMonotonic(var ms: Long = 1_000_000L) : MonotonicClock {
    override fun nowMs() = ms
}
