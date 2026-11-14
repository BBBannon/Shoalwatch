package app.offlinefin

import android.content.Context

/**
 * Holds the app's lazy singletons (no DI framework). Empty for now: later tasks add the
 * database, secret store and HTTP stack here.
 */
class AppGraph(@Suppress("unused") private val appContext: Context)
