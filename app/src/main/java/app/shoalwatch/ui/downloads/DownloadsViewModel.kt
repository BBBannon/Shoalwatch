package app.shoalwatch.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.OfflineSeries
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The Downloads tab: the offline library of the ACTIVE session through the `Downloads` seam (which applies the session and
 * sharing rule), the queue's status and whether the server can be reached. It restarts when the session changes: the new
 * session's first state is empty and loading, and content read for another session is dropped by comparing sessions, so
 * nothing from the old profile lingers. This screen makes no network call of its own. Each time the session's server becomes
 * reachable while the tab is open (including on opening), [reconfirm] asks for it (`SharingRecheck.request`, rate limited: the
 * strict sharing rule's re-check, so other profiles see shared copies and a later "no" hides them). The app runs the same
 * re-ask whichever screen is open; this only makes it prompt when the tab is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel(
    private val catalog: ServerCatalog,
    private val connections: ServerConnections,
    private val downloads: Downloads,
    private val reconfirm: suspend (Session) -> Unit = {},
) : ViewModel() {
    private val attempt = MutableStateFlow(0)
    private val confirm = MutableStateFlow<Pair<Session, RemoveRequest>?>(null)
    private val channel = Channel<SessionMessage>(Channel.BUFFERED)

    /** For the snackbar; the screen shows one only while its session is the one on screen. */
    val messages: Flow<SessionMessage> = channel.receiveAsFlow()

    private class Content(val session: Session, val library: OfflineLibrary?, val failed: Boolean)

    private val content: Flow<Content?> = combine(catalog.activeSession(), attempt) { s, _ -> s }.flatMapLatest { s ->
        if (s == null) flowOf(null) else flow {
            emit(Content(s, null, failed = false))
            emitAll(downloads.library(s).map { Content(s, it, false) }.catch { e -> if (e is CancellationException) throw e; emit(Content(s, null, failed = true)) })
        }
    }

    private val online: Flow<Pair<Session, Boolean>?> = catalog.activeSession().flatMapLatest { s ->
        if (s == null) flowOf(null) else connections.connection(s.serverId).map { s to (it is Connection.Online) }
    }

    val state: StateFlow<DownloadsUiState> = combine(catalog.activeSession(), content, online, downloads.queue, confirm) { s, c, on, queue, ask ->
        val mine = c?.takeIf { it.session == s }
        DownloadsUiState(
            session = s,
            library = mine?.library ?: DownloadsUiState.EMPTY_LIBRARY,
            queue = queue,
            online = on?.takeIf { it.first == s }?.second == true,
            loading = s != null && mine?.library == null && mine?.failed != true,
            localFailure = mine?.failed == true,
            confirmRemove = ask?.takeIf { it.first == s }?.second,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DownloadsUiState())

    init {
        viewModelScope.launch {
            online.distinctUntilChanged().filter { it?.second == true }.collect { on -> on?.let { safely { reconfirm(it.first) } } }
        }
    }

    fun pause(itemId: String) = act { s -> downloads.pause(s, itemId) }
    fun resume(itemId: String) = act { s -> downloads.resume(s, itemId) }
    fun retry(itemId: String) = act { s -> downloads.retry(s, itemId) }
    fun pauseAll() = act { s -> downloads.pauseAll(s) }
    fun resumeAll() = act { s -> downloads.resumeAll(s) }

    /** A film or a queued download: its size is said only once it is complete (Tasks 13-16 review M-8). */
    fun askRemove(itemId: String) {
        val now = state.value
        val s = now.session ?: return
        val entry = now.library.inProgress.firstOrNull { it.download.itemId == itemId } ?: now.library.films.firstOrNull { it.download.itemId == itemId }
        confirm.value = s to removeOne(itemId, entry)
    }

    /** "Delete downloaded series": this profile's own episodes of it, read for this session (nothing when it owns none). */
    fun askRemoveSeries(seriesId: String) {
        val s = state.value.session ?: return
        viewModelScope.launch {
            val series = try { downloads.series(s, seriesId).first() } catch (e: CancellationException) { throw e } catch (e: Exception) { return@launch }
            val request = removeOwned(RemoveKind.SERIES, series.seasons.flatMap { it.episodes }) ?: return@launch
            if (state.value.session == s) confirm.value = s to request
        }
    }

    fun cancelRemove() { confirm.value = null }

    fun confirmRemove() {
        val (s, request) = confirm.value ?: return
        confirm.value = null
        if (s != state.value.session) return
        act { removeEach(downloads, s, request)?.let { channel.trySend(SessionMessage(s, it)) } }
    }

    /** Try again after a failed read: subscribes the session's library again. */
    fun tryAgain() { attempt.value++ }

    /** As the session on screen now; an AppError's own words go to the snackbar for that session. */
    private fun act(block: suspend (Session) -> Unit) {
        val s = state.value.session ?: return
        viewModelScope.launch {
            try {
                block(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.trySend(SessionMessage(s, text = (e as? AppError ?: AppError.of(AppError.Kind.UNKNOWN)).userMessage))
            }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) = try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { Unit }
}

/**
 * One downloaded series (`downloads/series/{seriesId}`), for the active session; restarts when it changes, and nothing of
 * the old session lingers. A malformed id reads as an empty series: "This is no longer available".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSeriesViewModel(private val seriesId: String, catalog: ServerCatalog, private val downloads: Downloads) : ViewModel() {
    private val confirm = MutableStateFlow<Pair<Session, RemoveRequest>?>(null)
    private val channel = Channel<SessionMessage>(Channel.BUFFERED)

    /** For the snackbar; the screen shows one only while its session is the one on screen. */
    val messages: Flow<SessionMessage> = channel.receiveAsFlow()

    private val content: Flow<Pair<Session, OfflineSeries?>?> = catalog.activeSession().flatMapLatest { s ->
        if (s == null) flowOf(null) else flow {
            emit(s to null)
            emitAll(downloads.series(s, seriesId).map<OfflineSeries, Pair<Session, OfflineSeries?>> { s to it }.catch { e -> if (e is CancellationException) throw e; emit(s to OfflineSeries(null, emptyList())) })
        }
    }

    val state: StateFlow<OfflineSeriesUiState> = combine(catalog.activeSession(), content, confirm) { s, c, ask ->
        val mine = c?.takeIf { it.first == s }
        OfflineSeriesUiState(session = s, series = mine?.second, loading = s != null && mine?.second == null, confirmRemove = ask?.takeIf { it.first == s }?.second)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OfflineSeriesUiState())

    private val episodes get() = state.value.series?.seasons.orEmpty().flatMap { it.episodes }

    /** "Delete download" on an episode row (shown to its owners only; the store refuses anyone else). */
    fun askRemove(itemId: String) {
        val s = state.value.session ?: return
        confirm.value = s to removeOne(itemId, episodes.firstOrNull { it.download.itemId == itemId })
    }

    /** "Delete downloaded season": this profile's own episodes of it ([seasonId] null: the episodes with no season). */
    fun askRemoveSeason(seasonId: String?) {
        val s = state.value.session ?: return
        val season = state.value.series?.seasons?.firstOrNull { it.season?.item?.id == seasonId } ?: return
        confirm.value = s to (removeOwned(RemoveKind.SEASON, season.episodes) ?: return)
    }

    fun cancelRemove() { confirm.value = null }

    fun confirmRemove() {
        val (s, request) = confirm.value ?: return
        confirm.value = null
        if (s != state.value.session) return
        viewModelScope.launch {
            try {
                removeEach(downloads, s, request)?.let { channel.trySend(SessionMessage(s, it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.trySend(SessionMessage(s, text = (e as? AppError ?: AppError.of(AppError.Kind.UNKNOWN)).userMessage))
            }
        }
    }
}
