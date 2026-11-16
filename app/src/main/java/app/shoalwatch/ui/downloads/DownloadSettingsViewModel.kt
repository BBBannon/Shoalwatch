package app.shoalwatch.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadSettings
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.StorageChoice
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Download settings: the device's (mobile data, where new downloads go, the default quality) and the ACTIVE server's "Share
 * downloads between profiles". [cards] are the SD cards mounted now (the SD option is absent without one).
 */
data class DownloadSettingsUiState(
    val session: Session? = null,
    val serverName: String? = null,
    val settings: DownloadSettings = DownloadSettings(),
    val cards: List<StorageChoice> = emptyList(),
    val sharing: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSettingsViewModel(catalog: ServerCatalog, private val downloads: Downloads) : ViewModel() {
    private val cards = MutableStateFlow(readCards())

    private val server = combine(catalog.activeSession(), catalog.servers) { s, servers -> s to servers.firstOrNull { it.id == s?.serverId }?.name }

    val state: StateFlow<DownloadSettingsUiState> = server.flatMapLatest { (s, name) ->
        val sharing = if (s == null) flowOf(false) else downloads.sharing(s.serverId)
        combine(downloads.settings, cards, sharing) { settings, cards, shared -> DownloadSettingsUiState(s, name, settings, cards, shared) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettingsUiState())

    fun setMobileData(on: Boolean) = downloads.setMobileData(on)

    fun setStorage(kind: StorageKind, volumeId: String?) {
        cards.value = readCards()
        if (kind == StorageKind.INTERNAL || cards.value.any { it.volumeId == volumeId }) downloads.setStorage(kind, volumeId)
    }

    fun setDefaultQuality(q: DownloadQuality?) = downloads.setDefaultQuality(q)

    fun setSharing(on: Boolean) {
        val s = state.value.session ?: return
        viewModelScope.launch { try { downloads.setSharing(s, on) } catch (e: CancellationException) { throw e } catch (e: Exception) { Unit } }
    }

    /** Read again when the screen comes back (a card may have been put in or taken out). */
    fun refreshCards() { cards.value = readCards() }

    private fun readCards(): List<StorageChoice> = downloads.storageChoices().filter { it.kind == StorageKind.REMOVABLE && it.available }
}
