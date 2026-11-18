package app.shoalwatch.security

/**
 * Where Shoalwatch can be in front (Milestone 9): the phone's own activity, and the Android Auto car screen while parked.
 */
enum class Screen { PHONE, CAR }

/**
 * Leaving and returning when the app has two screens (Milestone 9; see "Android Auto (parked)" in docs/ARCHITECTURE.md). Each
 * screen tells its own [AppTrips] from [screen]; the [gate] hears "left" only when the LAST screen showing Shoalwatch leaves, and
 * "returned" only when the FIRST comes back. So the "Lock after" clock runs only while neither screen shows the app, a car screen
 * opened after "Lock after" locks (it is a return), and the phone coming back while the car shows the app is no return (the app
 * never left). A screen that says "left" or "returned" twice in a row counts once, as the gate's own second `onLeft` does.
 */
class AppFront(private val gate: AppTrips) {
    private val inFront = mutableSetOf<Screen>()

    /** The screens counted as showing the app now (for tests). */
    internal fun shown(): Set<Screen> = synchronized(inFront) { inFront.toSet() }

    fun screen(s: Screen): AppTrips = object : AppTrips {
        override fun onLeft() = left(s)
        override fun onReturned() = returned(s)
    }

    private fun left(s: Screen) = synchronized(inFront) {
        if (inFront.remove(s) && inFront.isEmpty()) gate.onLeft()
    }

    private fun returned(s: Screen) = synchronized(inFront) {
        val wasAway = inFront.isEmpty()
        if (inFront.add(s) && wasAway) gate.onReturned()
    }
}
