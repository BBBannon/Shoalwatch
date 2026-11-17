package app.shoalwatch.ui.lock

import android.app.Activity
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ProfileSwitcher
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.servers.SwitchResult
import app.shoalwatch.security.AuthOutcome
import app.shoalwatch.security.DeviceAuth
import app.shoalwatch.security.Household
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.FakeCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * The switcher as a fake: [protected] profiles answer `NeedsPin` to [switchTo]; [switchWithPin] accepts [rightPin] (and then
 * makes the profile active in [catalog], when given), else answers [wrongAnswer]. Every call is recorded, with the PIN as typed.
 */
class FakeSwitcher(private val catalog: FakeCatalog? = null) : ProfileSwitcher {
    val protected = mutableSetOf<Session>()
    val gone = mutableSetOf<Session>()
    var rightPin = "4826"
    var wrongAnswer: PinCheck = PinCheck.Wrong(4)
    var failure: Exception? = null
    /** When set, [switchWithPin] waits for it (to test a second tap while one runs). */
    var hold: CompletableDeferred<Unit>? = null
    val switchToCalls = mutableListOf<Session>()
    val pinCalls = mutableListOf<Pair<Session, String>>()
    val pinArrays = mutableListOf<CharArray>()

    /** As the store does (not the catalog's refusing `setActive`, so `FakeCatalog.activated` stays the record of screens' calls). */
    private fun activate(s: Session) {
        catalog?.let { c -> c.activeFlow.value = c.accountList.value.first { it.serverId == s.serverId && it.userId == s.userId } }
    }

    override suspend fun switchTo(serverId: String, userId: String): SwitchResult {
        val s = Session(serverId, userId)
        switchToCalls += s
        failure?.let { throw it }
        return when (s) {
            in gone -> SwitchResult.Gone
            in protected -> SwitchResult.NeedsPin
            else -> SwitchResult.Switched.also { activate(s) }
        }
    }

    override suspend fun switchWithPin(serverId: String, userId: String, pin: CharArray): SwitchResult = try {
        val s = Session(serverId, userId)
        pinCalls += s to String(pin)
        pinArrays += pin
        hold?.await()
        failure?.let { throw it }
        when {
            s in gone -> SwitchResult.Gone
            String(pin) == rightPin -> SwitchResult.Switched.also { activate(s) }
            else -> SwitchResult.NotSwitched(wrongAnswer)
        }
    } finally {
        pin.fill('\u0000')
    }
}

/** The household check as a fake: [askNeeded] is set by the test; [confirm] accepts [rightPin] for anyone in [grownUpList]. */
class FakeHousehold(ask: Boolean = true, grownUps: List<AccountSummary> = emptyList()) : Household {
    val ask = MutableStateFlow(ask)
    val grownUpList = MutableStateFlow(grownUps)
    var rightPin = "4826"
    var wrongAnswer: PinCheck = PinCheck.Wrong(4)
    var failure: Exception? = null
    val confirms = mutableListOf<Pair<Session, String>>()

    override val askNeeded: StateFlow<Boolean> = this.ask
    override val grownUps: Flow<List<AccountSummary>> = grownUpList

    override suspend fun confirm(grownUp: Session, pin: CharArray): PinCheck = try {
        confirms += grownUp to String(pin)
        failure?.let { throw it }
        if (String(pin) == rightPin && grownUpList.value.any { Session(it.serverId, it.userId) == grownUp }) PinCheck.Correct else wrongAnswer
    } finally {
        pin.fill('\u0000')
    }
}

/** The phone's screen lock as a fake: answers [outcome] and counts the prompts. */
class FakeDeviceAuth(var outcome: AuthOutcome = AuthOutcome.SUCCESS, var secure: Boolean = true) : DeviceAuth {
    var prompts = 0
    override fun available() = secure
    override suspend fun authenticate(activity: Activity, title: String, subtitle: String): AuthOutcome {
        prompts++
        return outcome
    }
}

/**
 * Profile protection as a fake: [pins] holds each protected profile's PIN (as typed, for the test only). Every call is recorded
 * with the PIN as typed, and every array handed in is kept in [arrays] so a test can see it was zeroed. [failure] is thrown by
 * every call (a `HouseholdLockedException` for the data layer's refusal).
 */
class FakeProfileLocks : app.shoalwatch.security.ProfileProtection {
    val pins = MutableStateFlow<Map<Session, String>>(emptyMap())
    var wrongAnswer: PinCheck = PinCheck.Wrong(4)
    var failure: Exception? = null
    val calls = mutableListOf<String>()
    val arrays = mutableListOf<CharArray>()

    override val protectedProfiles: Flow<Set<Session>> = pins.map { it.keys }
    override fun isProtectedFlow(s: Session): Flow<Boolean> = pins.map { s in it }

    private fun answer(s: Session, typed: String): PinCheck = when (val right = pins.value[s]) {
        null -> PinCheck.NotProtected
        typed -> PinCheck.Correct
        else -> wrongAnswer
    }

    override suspend fun protect(s: Session, pin: CharArray): Boolean = zeroing(pin) {
        calls += "protect:${s.userId}:${String(pin)}"
        failure?.let { throw it }
        if (s in pins.value) return@zeroing false
        pins.value = pins.value + (s to String(pin))
        true
    }

    override suspend fun check(s: Session, pin: CharArray): PinCheck = zeroing(pin) {
        calls += "check:${s.userId}:${String(pin)}"
        failure?.let { throw it }
        answer(s, String(pin))
    }

    override suspend fun changePin(s: Session, old: CharArray, new: CharArray): PinCheck = zeroing(old) {
        zeroing(new) {
            calls += "changePin:${s.userId}:${String(old)}:${String(new)}"
            failure?.let { throw it }
            answer(s, String(old)).also { if (it == PinCheck.Correct) pins.value = pins.value + (s to String(new)) }
        }
    }

    override suspend fun unprotect(s: Session, pin: CharArray): PinCheck = zeroing(pin) {
        calls += "unprotect:${s.userId}:${String(pin)}"
        failure?.let { throw it }
        answer(s, String(pin)).also { if (it == PinCheck.Correct) pins.value = pins.value - s }
    }

    private inline fun <T> zeroing(pin: CharArray, block: () -> T): T {
        arrays += pin
        try {
            return block()
        } finally {
            pin.fill('\u0000')
        }
    }
}

/** The player's picture-in-picture host as a fake: records every wish (null when withdrawn) and every press of the button. */
class FakePipHost(private val refuse: Boolean = false) : app.shoalwatch.ui.player.PipHost {
    val pip = MutableStateFlow(false)
    override val inPip: StateFlow<Boolean> = pip
    val wishes = mutableListOf<app.shoalwatch.ui.player.PipWish?>()
    var onPlayPause: (() -> Unit)? = null
    var entered = 0

    val last: app.shoalwatch.ui.player.PipWish? get() = wishes.lastOrNull()

    override fun wish(w: app.shoalwatch.ui.player.PipWish?, onPlayPause: (() -> Unit)?) {
        wishes += w
        this.onPlayPause = onPlayPause
    }

    override fun enterNow(): Boolean {
        entered++
        return !refuse && last != null
    }
}
