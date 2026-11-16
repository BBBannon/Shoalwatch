package app.shoalwatch.core

import android.os.SystemClock

/**
 * Milliseconds from a source that only ever moves forward and is not the wall clock: setting the phone's clock back or forward
 * does not change it. For expiry and retry spacing, never for anything shown to a person or saved.
 */
fun interface MonotonicClock {
    fun nowMs(): Long
}

/** Time since boot, counting deep sleep (`SystemClock.elapsedRealtime`). */
object SystemMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.elapsedRealtime()
}
