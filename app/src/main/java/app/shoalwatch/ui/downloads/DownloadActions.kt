package app.shoalwatch.ui.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.DownloadPlan
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadTarget
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the download sheet is. Every step carries its session, so a step for another session is never shown or acted on. */
sealed interface DownloadSheetState {
    val session: Session

    data class Quality(override val session: Session, val target: DownloadTarget) : DownloadSheetState
    data class Preparing(override val session: Session) : DownloadSheetState
    /** [films]: the target is a film (the title says "film", not "episode"). */
    data class Confirm(val plan: DownloadPlan, val films: Boolean = false) : DownloadSheetState { override val session get() = plan.session }
    data class Failed(override val session: Session, val message: String) : DownloadSheetState
}

/**
 * Like `ItemActions`: the one place screens call `Downloads.prepare` and `start`. Per-session work: [current] is the session on
 * screen now, and a plan prepared for another one (the account switched while preparing) is dropped with its message. A
 * default quality skips the quality step. Confirm starts once, only with enough space. Failures are the `AppError`'s own words
 * (offline: "You're offline. Connect to download.").
 */
class DownloadActions(private val scope: CoroutineScope, private val downloads: Downloads, private val current: () -> Session?) {
    private val _sheet = MutableStateFlow<DownloadSheetState?>(null)
    val sheet: StateFlow<DownloadSheetState?> = _sheet.asStateFlow()
    private val channel = Channel<SessionMessage>(Channel.BUFFERED)
    val messages: Flow<SessionMessage> = channel.receiveAsFlow()
    private var target: DownloadTarget? = null
    private var films = false
    private var preparing: Job? = null
    private var starting = false

    /** Opens the sheet for [target]: the quality step, unless a default quality is set. [film]: the target is a film. */
    fun ask(s: Session, target: DownloadTarget, film: Boolean = false) {
        this.target = target
        films = film
        val default = downloads.settings.value.defaultQuality
        if (default != null) prepare(s, target, default) else _sheet.value = DownloadSheetState.Quality(s, target)
    }

    /** The quality chosen ([remember]: "Use this every time", which sets the default): prepares the plan, then the confirm step. */
    fun choose(quality: DownloadQuality, remember: Boolean = false) {
        val step = _sheet.value as? DownloadSheetState.Quality ?: return
        if (remember) downloads.setDefaultQuality(quality)
        prepare(step.session, step.target, quality)
    }

    private fun prepare(s: Session, target: DownloadTarget, quality: DownloadQuality) {
        _sheet.value = DownloadSheetState.Preparing(s)
        preparing?.cancel()
        preparing = scope.launch {
            val next = try {
                DownloadSheetState.Confirm(downloads.prepare(s, target, quality), films)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DownloadSheetState.Failed(s, (e as? AppError ?: AppError.of(AppError.Kind.UNKNOWN)).userMessage)
            }
            // The account changed while preparing: the sheet and its message go with the old session.
            _sheet.value = if (current() == s && _sheet.value is DownloadSheetState.Preparing) next else null
        }
    }

    /** Starts the confirmed plan once, as its own session; nothing without enough space or for another session. */
    fun confirm() {
        val step = _sheet.value as? DownloadSheetState.Confirm ?: return
        val plan = step.plan
        if (!plan.enoughSpace || starting || current() != plan.session) return
        starting = true
        _sheet.value = null
        scope.launch {
            try {
                downloads.start(plan.session, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.trySend(SessionMessage(plan.session, text = (e as? AppError ?: AppError.of(AppError.Kind.UNKNOWN)).userMessage))
            } finally {
                starting = false
            }
        }
    }

    fun dismiss() {
        preparing?.cancel()
        _sheet.value = null
        target = null
    }

    /** The session on screen changed: whatever the sheet held for the old one goes. */
    fun sessionChanged(s: Session?) { if (_sheet.value?.session != s) dismiss() }
}

/**
 * The download states a browsing screen shows, for the ACTIVE session only: empty at once when the session changes (nothing of
 * the old profile lingers), then [of]'s answer; a failure reads as none.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun downloadStatuses(
    catalog: app.shoalwatch.data.servers.ServerCatalog,
    scope: CoroutineScope,
    of: (Session) -> Flow<Map<String, app.shoalwatch.data.downloads.DownloadInfo>>,
): StateFlow<Map<String, app.shoalwatch.data.downloads.DownloadInfo>> =
    catalog.activeSession().flatMapLatest { s ->
        if (s == null) flowOf(emptyMap()) else flow {
            emit(emptyMap())
            emitAll(of(s).catch { e -> if (e is CancellationException) throw e; emit(emptyMap()) })
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
