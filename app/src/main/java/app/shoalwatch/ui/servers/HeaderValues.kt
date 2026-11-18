package app.shoalwatch.ui.servers

import app.shoalwatch.data.servers.ServerCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The custom-header values on the connection settings screen. The catalog hides them everywhere else, so they are read only
 * through `ServerCatalog.headerValues`, which asks the household check in the data layer (review M-4). A read that is refused
 * (the grown-up's pass ended) keeps what is already shown.
 */
internal class HeaderValues(private val catalog: ServerCatalog, private val serverId: String, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = current.asStateFlow()

    fun refresh() {
        scope.launch { read()?.let { current.value = it } }
    }

    /** One value, read fresh, else the one shown. */
    suspend fun value(name: String): String? = read()?.get(name) ?: current.value[name]

    private suspend fun read(): Map<String, String>? = try {
        catalog.headerValues(serverId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
