package app.shoalwatch.data.discover

import android.content.Intent
import android.net.Uri

/**
 * The intent that opens an online trailer: `ACTION_VIEW` on the validated address with `CATEGORY_BROWSABLE` (so only an app that
 * accepts links from a browser can take it: the address came from the server and is not trusted, final review M2) and nothing
 * else (no extras, no flags, no grants), so no token or header can ride along and the other app gets no access to this one. Null
 * for anything that is not a printable `http` or `https` address of at most [DiscoverLimits.URL] characters. Built here, outside
 * the screens, which never parse an address or build an intent themselves (AdminBoundaryTest).
 */
fun trailerIntent(url: String): Intent? {
    if (url.isEmpty() || url.length > DiscoverLimits.URL) return null
    val uri = Uri.parse(url)
    if (uri.scheme != "http" && uri.scheme != "https") return null
    if (uri.host.isNullOrEmpty()) return null
    return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
}
