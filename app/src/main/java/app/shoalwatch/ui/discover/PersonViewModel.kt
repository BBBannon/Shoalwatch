package app.shoalwatch.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.phaseFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PersonUiState(
    /** The profile everything below belongs to; null while nobody is signed in. */
    val session: Session? = null,
    /** The person's own page: arrived, waiting, offline, gone or failed. Their works have a phase of their own. */
    val phase: ListsPhase = ListsPhase.LOADING,
    val person: Item? = null,
    val works: PagedState<ItemWithUserData> = PagedState(),
) {
    override fun toString() = "PersonUiState(<redacted>)"
}

/**
 * A person's page (Milestone 11, live): their picture and overview, then the films and shows they appear in a page at a time as
 * the end comes into view. Restarts when the profile changes and nothing of the old one lingers.
 */
class PersonViewModel(private val personId: String, catalog: ServerCatalog, private val discover: Discover) : ViewModel() {
    private data class Head(val session: Session?, val phase: ListsPhase, val person: Item?)

    private val head = MutableStateFlow(Head(null, ListsPhase.LOADING, null))
    private val works = PagedLoader<ItemWithUserData>(viewModelScope) { it.item.id }

    val state: StateFlow<PersonUiState> = combine(head, works.state) { h, w -> PersonUiState(h.session, h.phase, h.person, w) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PersonUiState())

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                head.value = Head(s, if (s == null) ListsPhase.READY else ListsPhase.LOADING, null)
                if (s == null) works.restart(null) else {
                    works.restart { start -> discover.personWorks(s, personId, start) }
                    readHead(s)
                }
            }
        }
    }

    private suspend fun readHead(s: Session) {
        try {
            val p = discover.person(s, personId)
            head.update { if (it.session == s) it.copy(phase = ListsPhase.READY, person = p) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            head.update { if (it.session == s) it.copy(phase = phaseFor(e), person = null) else it }
        }
    }

    /** The end of their works is in view. */
    fun onEndVisible() = works.loadMore()

    /** Try again (offline or failed): the page and the works again. */
    fun retry() {
        val s = head.value.session ?: return
        head.update { it.copy(phase = ListsPhase.LOADING) }
        works.restart { start -> discover.personWorks(s, personId, start) }
        viewModelScope.launch { readHead(s) }
    }

    /** A later page of works failed: try that page again. */
    fun retryMore() = works.retry()
}
