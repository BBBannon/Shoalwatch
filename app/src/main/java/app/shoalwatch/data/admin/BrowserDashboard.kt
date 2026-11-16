package app.shoalwatch.data.admin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

enum class DashboardResult { OPENED, OFFLINE, NO_BROWSER }

fun interface DashboardOpener {
    /**
     * Opens [session]'s server dashboard in the browser from [context] (the application, so a new task; an Activity also works).
     * No token, header, query or user name is passed.
     */
    suspend fun open(session: Session, context: Context): DashboardResult
}

/**
 * "Open the full dashboard in your browser": the server's own web client at `{verified address}/web/`, the address the selector
 * verified just now (base path kept). Nothing of the sign-in goes with it — no token, header, query, fragment or user name — so
 * the browser asks for its own sign-in (SPEC §3.4). The UI only ever sees the [DashboardResult].
 */
class BrowserDashboard(
    private val selector: ServerConnections,
    private val main: CoroutineDispatcher = Dispatchers.Main,
) : DashboardOpener {
    override suspend fun open(session: Session, context: Context): DashboardResult {
        val online = selector.awaitOnline(session.serverId, AWAIT_MS) ?: return DashboardResult.OFFLINE
        val url = online.address.value.toHttpUrl().newBuilder().addPathSegment("web").addPathSegment("").build()
        // Defence in depth: a verified address never carries these, and the dashboard link must not either.
        if (url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) return DashboardResult.OFFLINE
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addCategory(Intent.CATEGORY_BROWSABLE)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return withContext(main) {
            try {
                context.startActivity(intent)
                DashboardResult.OPENED
            } catch (_: ActivityNotFoundException) {
                DashboardResult.NO_BROWSER
            }
        }
    }

    private companion object {
        const val AWAIT_MS = 5_000L
    }
}
