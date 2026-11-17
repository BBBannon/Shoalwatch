package app.shoalwatch

import android.content.Context
import app.shoalwatch.core.SystemMonotonicClock
import app.shoalwatch.data.servers.ProfileSwitch
import app.shoalwatch.data.servers.ProfileSwitcher
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.security.ClearProof
import app.shoalwatch.security.DeviceAuth
import app.shoalwatch.security.FrameworkDeviceAuth
import app.shoalwatch.security.HouseholdGuard
import app.shoalwatch.security.LockGate
import app.shoalwatch.security.LockPrefs
import app.shoalwatch.security.PinHasher
import app.shoalwatch.security.ProfileLocks
import kotlinx.coroutines.Dispatchers

/**
 * Profile protection and the app lock's wiring (kept out of [AppGraph], which is near its size limit): see "Profile PINs" and
 * "App lock" in docs/ARCHITECTURE.md. Exactly one [ProfileLocks] exists (it owns the check mutex and the wait anchors).
 */
internal class LockParts(graph: AppGraph) {
    val profileLocks: ProfileLocks = ProfileLocks(graph.database.profileDao(), PinHasher(graph.secretBox), SystemMonotonicClock, Dispatchers.IO) {
        householdGuard.require()
    }

    /** Switches proved by the target's own PIN, which `LockGate` lets through once. */
    val provedSwitches = ProvedSwitches(SystemMonotonicClock)

    /** The one way a screen changes the active profile: the store's own `setActive`, after the PIN for a protected profile. */
    val switcher: ProfileSwitcher = ProfileSwitch(
        graph.serverStore::setActive,
        { s -> graph.serverStore.awaitWarmed(); graph.serverStore.cachedToken(s.serverId, s.userId) != null },
        profileLocks, provedSwitches,
    )

    /** "Forgot PIN": removes a profile's protection, only with the proof its own server password was just accepted. */
    val clearProtection: suspend (ClearProof) -> Unit = { profileLocks.clear(it) }

    /** The household check, enforced by the catalog, the authenticator and the two download settings. */
    val householdGuard: HouseholdGuard = HouseholdGuard(profileLocks, graph.serverCatalog, SystemMonotonicClock, graph.appScope) { lockGate.isOpenFor(it) }

    /** The app lock settings, in the private `security_prefs` file (excluded from backup like every preferences file). */
    val lockPrefs = LockPrefs(graph.appContext.getSharedPreferences("security_prefs", Context.MODE_PRIVATE))

    /** The phone's own screen lock prompt. */
    val deviceAuth: DeviceAuth = FrameworkDeviceAuth(graph.appContext)

    /** The one lock state for the app; started by `ShoalwatchApp`, told about leaving and returning by `MainActivity`. */
    val lockGate: LockGate = LockGate(
        lockPrefs.settings, profileLocks, graph.serverCatalog, provedSwitches, householdGuard, deviceAuth::available,
        SystemMonotonicClock, graph.appScope,
    ) { lockPrefs.setAppLock(false) }
}
