package app.offlinefin.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.offlinefin.data.servers.ServerCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which screen the app opens on. Nothing is shown until it is known, so there is no flash of the wrong screen. */
enum class StartupState { Loading, Welcome, Home }

/**
 * Waits for saved sign-ins to be loaded (`awaitWarmed`, via `awaitActive`) and then decides: nothing saved at
 * all means the Welcome / Add server screen, anything else means Home (which copes with "nobody signed in").
 */
class StartupViewModel(private val catalog: ServerCatalog) : ViewModel() {
    private val mutableState = MutableStateFlow(StartupState.Loading)
    val state: StateFlow<StartupState> = mutableState

    init {
        viewModelScope.launch {
            val active = catalog.awaitActive()
            val anyServer = catalog.servers.first().isNotEmpty()
            mutableState.value = if (active == null && !anyServer) StartupState.Welcome else StartupState.Home
        }
    }
}
