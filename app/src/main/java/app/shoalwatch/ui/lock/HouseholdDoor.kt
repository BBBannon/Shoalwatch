package app.shoalwatch.ui.lock

import app.shoalwatch.security.Household
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where a [HouseholdDoor] stands: still checking, asking a grown-up, open, or shut (the check was refused or dismissed). */
enum class Door { Checking, Asking, Open, Shut }

/**
 * A whole screen that is a household setting, whichever way it was opened (security review M8 F2: the certificate "Review"
 * buttons opened connection settings without the check the Servers list asks). The screen shows nothing of its data until
 * [state] is [Door.Open]: at once while [Household.allows] (no protected profile, a protected profile is active and open, or a
 * grown-up's pass is live), otherwise after a right PIN through [grownUp]. [household] null (tests only) is always open.
 */
internal class HouseholdDoor(private val household: Household?, private val scope: CoroutineScope) {
    val grownUp: GrownUpFlow? = household?.let { GrownUpFlow(it, scope) }
    private val current = MutableStateFlow(if (household == null) Door.Open else Door.Checking)
    val state: StateFlow<Door> = current.asStateFlow()
    val isOpen: Boolean get() = current.value == Door.Open

    init {
        if (household != null && grownUp != null) {
            scope.launch {
                if (household.allows()) {
                    current.value = Door.Open
                    return@launch
                }
                current.value = Door.Asking
                grownUp.guard { scope.launch { current.value = if (household.allows()) Door.Open else Door.Shut } }
            }
        }
    }

    /** The grown-up dialog was dismissed: the screen closes, unless it was already open. */
    fun dismiss() {
        grownUp?.dismiss()
        if (!isOpen) current.value = Door.Shut
    }
}
