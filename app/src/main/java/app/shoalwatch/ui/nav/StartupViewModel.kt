package app.shoalwatch.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.servers.ServerCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Which screen the app opens on. Nothing is shown until it is known, so there is no flash of the wrong screen.
 * [Failed] means the saved servers could not be read: that is never treated as "nothing saved".
 */
enum class StartupState { Loading, Welcome, Home, Failed }

/**
 * Waits for saved sign-ins to be loaded (`awaitWarmed`, via `awaitActive`) and then decides: nothing saved at
 * all means the Welcome / Add server screen, anything else means Home (which copes with "nobody signed in").
 * A read that throws becomes [StartupState.Failed] with a retry (which also retries a failed token warm).
 */
class StartupViewModel(private val catalog: ServerCatalog) : ViewModel() {
    private val mutableState = MutableStateFlow(StartupState.Loading)
    val state: StateFlow<StartupState> = mutableState

    init {
        load()
    }

    fun retry() {
        mutableState.value = StartupState.Loading
        load(retryWarm = true)
    }

    private fun load(retryWarm: Boolean = false) {
        viewModelScope.launch {
            mutableState.value = try {
                if (retryWarm) catalog.retryWarmIfFailed()
                val active = catalog.awaitActive()
                val anyServer = catalog.servers.first().isNotEmpty()
                if (active == null && !anyServer) StartupState.Welcome else StartupState.Home
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w("Startup", "Reading saved servers failed", e)
                StartupState.Failed
            }
        }
    }
}
