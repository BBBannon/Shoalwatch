package app.shoalwatch

import app.shoalwatch.core.SystemMonotonicClock
import app.shoalwatch.data.servers.ProfileSwitch
import app.shoalwatch.data.servers.ProfileSwitcher
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.security.HouseholdGuard
import app.shoalwatch.security.PinHasher
import app.shoalwatch.security.ProfileLocks
import kotlinx.coroutines.Dispatchers

/**
 * Profile protection and the app lock's wiring (kept out of [AppGraph], which is near its size limit): see "Profile PINs" and
 * "App lock" in docs/ARCHITECTURE.md. Exactly one [ProfileLocks] exists (it owns the check mutex and the wait anchors).
 */
internal class LockParts(graph: AppGraph) {
    val profileLocks = ProfileLocks(graph.database.profileDao(), PinHasher(graph.secretBox), SystemMonotonicClock, Dispatchers.IO)

    /** Switches proved by the target's own PIN, which `LockGate` lets through once. */
    val provedSwitches = ProvedSwitches(SystemMonotonicClock)

    /** The one way a screen changes the active profile: the store's own `setActive`, after the PIN for a protected profile. */
    val switcher: ProfileSwitcher = ProfileSwitch(
        graph.serverStore::setActive,
        { s -> graph.serverStore.awaitWarmed(); graph.serverStore.cachedToken(s.serverId, s.userId) != null },
        profileLocks, provedSwitches,
    )

    /** The household check, enforced by the catalog, the authenticator and the two download settings. */
    val householdGuard = HouseholdGuard(profileLocks, graph.serverCatalog, SystemMonotonicClock, graph.appScope)
}
