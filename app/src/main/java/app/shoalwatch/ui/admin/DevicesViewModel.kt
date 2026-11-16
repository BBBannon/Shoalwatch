package app.shoalwatch.ui.admin

import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.ServerDevice
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

data class DeviceConfirm(val deviceId: String, val name: String, val userNames: String) {
    override fun toString() = "DeviceConfirm(<redacted>)"
}

data class DevicesUiState(val frame: AdminFrame = AdminFrame(), val devices: List<ServerDevice> = emptyList(), val confirm: DeviceConfirm? = null) {
    override fun toString() = "DevicesUiState(<redacted>)"
}

/**
 * Devices signed in to the server, and removing one after confirmation. This phone's device is never offered (removing it would
 * sign out every account on this phone for that server; Sign out in Servers and accounts is the tool). Loads on open and Refresh.
 */
class DevicesViewModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val devices = MutableStateFlow<List<ServerDevice>>(emptyList())
    private val confirmState = MutableStateFlow<DeviceConfirm?>(null)

    private val poll = poller { session ->
        val list = admin.devices(session)
        if (guard.gate.value is AdminGate.Open) devices.value = list
        null
    }

    val state: StateFlow<DevicesUiState> = combine(frames, devices, confirmState) { frame, list, confirm ->
        if (frame.gate == AdminGate.Closed) DevicesUiState(frame = frame) else DevicesUiState(frame, list, confirm)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    fun refresh() = poll.kick()

    fun askRemove(deviceId: String) {
        val d = removable(deviceId) ?: return
        confirmState.value = DeviceConfirm(d.id, d.name, d.userNames.joinToString(", "))
    }

    /** Removes the confirmed device once, if it is still in the latest list and is not this phone (else GONE and a refresh). */
    fun confirmRemove() {
        val c = confirmState.value ?: return
        confirmState.value = null
        if (frames.value.gate !is AdminGate.Open) return
        if (removable(c.deviceId) == null) {
            work.fail(AdminProblem.GONE)
            poll.kick()
            return
        }
        work.run("device:${c.deviceId}", { s -> admin.removeDevice(s, c.deviceId) }) {
            notify(AdminNotice(R.string.admin_device_removed, listOf(c.name), R.string.admin_unnamed_device))
            poll.kick()
        }
    }

    fun dismissConfirm() {
        confirmState.value = null
    }

    override fun dropLoaded() {
        devices.value = emptyList()
        confirmState.value = null
    }

    private fun removable(id: String) = devices.value.firstOrNull { it.id == id && it.removable && !it.isThisDevice }
}
