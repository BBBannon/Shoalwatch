package app.shoalwatch.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.Housekeeping
import app.shoalwatch.data.downloads.ProfileSettings
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.downloads.DownloadSettings
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.StorageChoice
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import androidx.compose.foundation.text.input.TextFieldState
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.security.Household
import app.shoalwatch.security.HouseholdLockedException
import app.shoalwatch.ui.lock.GrownUpAsk
import app.shoalwatch.ui.lock.GrownUpFlow
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
 * downloads between profiles" and "Delete shared downloads", and the ACTIVE profile's own "When a download finishes playing". [cards] are the SD cards mounted now (the SD option is absent without one).
 */
data class DownloadSettingsUiState(
    val session: Session? = null,
    val serverName: String? = null,
    val settings: DownloadSettings = DownloadSettings(),
    val cards: List<StorageChoice> = emptyList(),
    val sharing: Boolean = false,
    /** The ACTIVE profile's own "When a download finishes playing". */
    val afterFinish: AfterFinish = AfterFinish.ASK,
    /** The active server's "Delete shared downloads" (shown only while [sharing] is on). */
    val deleteShared: SharedDelete = SharedDelete.ANYONE,
    /** The ACTIVE profile's own "Download next episodes automatically": 0 (Off), 1, 2, 3 or 5. */
    val autoNext: Int = 0,
    /** The data layer refused a household change (a race with the check): "A grown-up's PIN is needed. Try again." */
    val householdRace: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSettingsViewModel(
    catalog: ServerCatalog,
    private val downloads: Downloads,
    private val housekeeping: Housekeeping,
    /** "Share downloads between profiles" and "Delete shared downloads" ask a grown-up's PIN first (null: never asks). */
    household: Household? = null,
) : ViewModel() {
    private val cards = MutableStateFlow(readCards())
    private val race = MutableStateFlow(false)
    private val grownUp = household?.let { GrownUpFlow(it, viewModelScope) }

    val grownUpAsk: StateFlow<GrownUpAsk?> = grownUp?.ask ?: MutableStateFlow(null)
    val grownUpPin: TextFieldState = grownUp?.pin ?: TextFieldState()

    private val server = combine(catalog.activeSession(), catalog.servers) { s, servers -> s to servers.firstOrNull { it.id == s?.serverId }?.name }

    val state: StateFlow<DownloadSettingsUiState> = server.flatMapLatest { (s, name) ->
        val sharing = if (s == null) flowOf(false) else downloads.sharing(s.serverId)
        val profile = if (s == null) flowOf(ProfileSettings()) else housekeeping.profileSettings(s)
        val rule = if (s == null) flowOf(SharedDelete.ANYONE) else housekeeping.sharedDelete(s.serverId)
        combine(downloads.settings, cards, sharing, profile, rule) { settings, cards, shared, mine, deleteShared ->
            DownloadSettingsUiState(s, name, settings, cards, shared, mine.afterFinish, deleteShared, mine.autoNextCount)
        }.combine(race) { state, r -> state.copy(householdRace = r) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadSettingsUiState())

    fun setMobileData(on: Boolean) = downloads.setMobileData(on)

    fun setStorage(kind: StorageKind, volumeId: String?) {
        cards.value = readCards()
        if (kind == StorageKind.INTERNAL || cards.value.any { it.volumeId == volumeId }) downloads.setStorage(kind, volumeId)
    }

    fun setDefaultQuality(q: DownloadQuality?) = downloads.setDefaultQuality(q)

    /** A household setting (both directions): a grown-up's PIN first when one is needed. */
    fun setSharing(on: Boolean) {
        val s = state.value.session ?: return
        household { downloads.setSharing(s, on) }
    }

    /** For the active profile only, as itself. */
    fun setAfterFinish(value: AfterFinish) {
        val s = state.value.session ?: return
        viewModelScope.launch { try { housekeeping.setAfterFinish(s, value) } catch (e: CancellationException) { throw e } catch (e: Exception) { Unit } }
    }

    /** For the active profile only, as itself: 0, 1, 2, 3 or 5. */
    fun setAutoNext(count: Int) {
        val s = state.value.session ?: return
        viewModelScope.launch { try { housekeeping.setAutoNext(s, count) } catch (e: CancellationException) { throw e } catch (e: Exception) { Unit } }
    }

    /** Per server, as sharing itself, and a household setting: a grown-up's PIN first when one is needed. */
    fun setDeleteShared(value: SharedDelete) {
        val s = state.value.session ?: return
        household { housekeeping.setSharedDelete(s, value) }
    }

    fun chooseGrownUp(who: AccountSummary) { grownUp?.choose(who) }
    fun submitGrownUp() { grownUp?.submit() }
    fun dismissGrownUp() { grownUp?.dismiss() }

    /** Runs [change] after the grown-up check; a refusal from the data layer (a race) shows its line and changes nothing. */
    private fun household(change: suspend () -> Unit) {
        race.value = false
        val run = {
            viewModelScope.launch {
                try {
                    change()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HouseholdLockedException) {
                    race.value = true
                } catch (e: Exception) {
                    Unit
                }
            }
            Unit
        }
        grownUp?.guard(run) ?: run()
    }

    /** Read again when the screen comes back (a card may have been put in or taken out). */
    fun refreshCards() { cards.value = readCards() }

    private fun readCards(): List<StorageChoice> = downloads.storageChoices().filter { it.kind == StorageKind.REMOVABLE && it.available }
}
