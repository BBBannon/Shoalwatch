package app.shoalwatch.ui

/**
 * Lets the user pick a client certificate from the system `KeyChain`, which needs an Activity. The Activity-bound
 * implementation is created by the screen and handed to a ViewModel for one call, so a ViewModel never holds an Activity.
 */
fun interface ClientCertChooser {
    /** The chosen alias, or null if the user cancelled. */
    suspend fun choose(): String?
}
