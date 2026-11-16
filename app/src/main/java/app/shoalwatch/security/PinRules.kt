package app.shoalwatch.security

/** What a profile PIN may be (Milestone 7). */
object PinRules {
    const val MIN = 4
    const val MAX = 12

    /** 4 to 12 ASCII digits (not other scripts' digits, which a keyboard could also type). */
    fun valid(pin: CharArray): Boolean = pin.size in MIN..MAX && pin.all { it in '0'..'9' }
}

/** How long to wait after wrong PINs. The count is kept in Room; the timing is `ProfileLocks`'s (monotonic clock only). */
object PinThrottle {
    const val FREE_TRIES = 5
    private const val FIRST_WAIT_MS = 30_000L
    private const val LONGEST_WAIT_MS = 15 * 60_000L

    /** 0 below [FREE_TRIES]; then 30 s, doubling for each further wrong PIN, at most 15 minutes. */
    fun waitMs(failures: Int): Long {
        if (failures < FREE_TRIES) return 0
        val doublings = (failures - FREE_TRIES).coerceAtMost(10) // 30 s << 5 is already past the cap; this keeps the shift small
        return (FIRST_WAIT_MS shl doublings).coerceAtMost(LONGEST_WAIT_MS)
    }
}
