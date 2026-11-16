package app.shoalwatch.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.Housekeeping
import app.shoalwatch.data.downloads.StorageView
import app.shoalwatch.data.downloads.WatchedSummary
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A snackbar line for [session] only (the screen drops one whose session is no longer on screen). */
data class StorageMessage(val kind: Kind, val count: Int = 0, val session: Session? = null) {
    enum class Kind { DELETED, KEPT_FOR_OTHERS, FAILED }

    override fun toString() = "StorageMessage($kind, $count)"
}

/** [view] null while loading or after a failure ([localFailure]: "Try again"). [confirm] is only ever the session's own. */
data class StorageUiState(
    val session: Session? = null, val view: StorageView? = null, val loading: Boolean = false, val localFailure: Boolean = false,
    val confirm: StorageConfirm? = null,
)

sealed interface StorageConfirm {
    data class Watched(val summary: WatchedSummary) : StorageConfirm
    data class Series(val seriesId: String, val name: String, val bytes: Long) : StorageConfirm { override fun toString() = "Series(<redacted>)" }
    data class Film(val itemId: String, val name: String, val bytes: Long) : StorageConfirm { override fun toString() = "Film(<redacted>)" }
}

/**
 * Storage (`downloads/storage`), for the ACTIVE session only (see "Storage" in docs/ARCHITECTURE.md). It restarts when the session
 * changes: the new session's first state is empty and loading, and nothing read for another session is shown. A confirm carries
 * the session it was opened for and is dropped (never acted on) once another session is on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StorageViewModel(catalog: ServerCatalog, private val housekeeping: Housekeeping, private val downloads: Downloads) : ViewModel() {
    private val attempt = MutableStateFlow(0)
    private val confirmFor = MutableStateFlow<Pair<Session, StorageConfirm>?>(null)
    private val channel = Channel<StorageMessage>(Channel.BUFFERED)

    val messages: Flow<StorageMessage> = channel.receiveAsFlow()

    private class Content(val session: Session, val view: StorageView?, val failed: Boolean)

    private val content: Flow<Content?> = combine(catalog.activeSession(), attempt) { s, _ -> s }.flatMapLatest { s ->
        if (s == null) flowOf(null) else flow {
            emit(Content(s, null, failed = false))
            emitAll(housekeeping.storage(s).map { Content(s, it, false) }.catch { e -> if (e is CancellationException) throw e; emit(Content(s, null, failed = true)) })
        }
    }

    val state: StateFlow<StorageUiState> = combine(catalog.activeSession(), content, confirmFor) { s, c, ask ->
        val mine = c?.takeIf { it.session == s }
        StorageUiState(
            session = s, view = mine?.view, loading = s != null && mine?.view == null && mine?.failed != true,
            localFailure = mine?.failed == true, confirm = ask?.takeIf { it.first == s }?.second,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StorageUiState())

    init {
        // A confirm opened for one session is dropped the moment another is on screen; it never comes back for the old one.
        viewModelScope.launch { catalog.activeSession().collect { s -> if (confirmFor.value?.first != s) confirmFor.value = null } }
    }

    /** Reads what "Delete watched" would do first, so the confirm says it (and that shared ones go for everyone). */
    fun askDeleteWatched() {
        val s = state.value.session ?: return
        viewModelScope.launch {
            val summary = try {
                housekeeping.watchedSummary(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch send(s, StorageMessage.Kind.FAILED)
            }
            if (summary.count > 0 && state.value.session == s) confirmFor.value = s to StorageConfirm.Watched(summary)
        }
    }

    /** Only a series this profile owns episodes of (the row has no delete otherwise). */
    fun askDeleteSeries(seriesId: String) {
        val now = state.value
        val s = now.session ?: return
        val use = now.view?.series?.firstOrNull { it.series.item.id == seriesId }?.takeIf { it.ownedEpisodes > 0 } ?: return
        confirmFor.value = s to StorageConfirm.Series(seriesId, use.series.item.name, use.deletableBytes)
    }

    /** Only a film this profile owns. */
    fun askDeleteFilm(itemId: String) {
        val now = state.value
        val s = now.session ?: return
        val use = now.view?.films?.firstOrNull { it.film.item.id == itemId }?.takeIf { it.ownedByMe } ?: return
        confirmFor.value = s to StorageConfirm.Film(itemId, use.film.item.name, use.bytes)
    }

    fun cancel() { confirmFor.value = null }

    fun tryAgain() { attempt.value++ }

    /** As the session the confirm was opened for, and only while it is still the one on screen. */
    fun confirm() {
        val (s, ask) = confirmFor.value ?: return
        confirmFor.value = null
        if (s != state.value.session) return
        viewModelScope.launch {
            try {
                when (ask) {
                    is StorageConfirm.Watched -> {
                        val n = housekeeping.deleteWatched(s)
                        // Nothing freed (each kept for another owner) says so instead of "Deleted".
                        if (n > 0 && ask.summary.bytes == 0L) send(s, StorageMessage.Kind.KEPT_FOR_OTHERS) else send(s, StorageMessage.Kind.DELETED, n)
                    }
                    is StorageConfirm.Series -> {
                        val n = housekeeping.deleteSeries(s, ask.seriesId)
                        if (n == 0) send(s, StorageMessage.Kind.KEPT_FOR_OTHERS) else send(s, StorageMessage.Kind.DELETED, n)
                    }
                    is StorageConfirm.Film -> when (downloads.remove(s, ask.itemId)) {
                        is RemoveOutcome.Deleted -> send(s, StorageMessage.Kind.DELETED, 1)
                        RemoveOutcome.KeptForOthers -> send(s, StorageMessage.Kind.KEPT_FOR_OTHERS)
                        RemoveOutcome.NotYours -> send(s, StorageMessage.Kind.FAILED)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(s, StorageMessage.Kind.FAILED)
            }
        }
    }

    private fun send(s: Session, kind: StorageMessage.Kind, count: Int = 0) {
        channel.trySend(StorageMessage(kind, count, s))
    }
}
