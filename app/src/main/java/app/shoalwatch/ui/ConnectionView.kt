package app.shoalwatch.ui

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Connection

/**
 * How a [Connection] is shown: the screen picks the wording for [kind] from string resources; [detail] is
 * plain-English text already (an [AppError] message), set only for [Kind.OFFLINE]. No address is ever in it.
 */
data class ConnectionView(
    val kind: Kind,
    /** For [Kind.CHECKING]: what it was a moment ago ([Kind.HOME] or [Kind.AWAY]), shown subtly. */
    val previous: Kind? = null,
    /** For [Kind.OFFLINE]: the security problem to act on, or null for ordinary unreachability. */
    val reason: AppError.Kind? = null,
    val detail: String? = null,
) {
    enum class Kind { HOME, AWAY, CHECKING, OFFLINE }

    /** The certificate is untrusted or changed: the user must review it in the server's settings. */
    val needsCertificateReview: Boolean
        get() = reason == AppError.Kind.TLS_PIN_CHANGED || reason == AppError.Kind.TLS_UNTRUSTED

    val certificateChanged: Boolean get() = reason == AppError.Kind.TLS_PIN_CHANGED
}

fun Connection.toView(): ConnectionView = when (this) {
    is Connection.Online -> ConnectionView(if (isHome) ConnectionView.Kind.HOME else ConnectionView.Kind.AWAY)
    is Connection.Checking -> ConnectionView(
        ConnectionView.Kind.CHECKING,
        previous = previous?.let { if (it.isHome) ConnectionView.Kind.HOME else ConnectionView.Kind.AWAY },
    )
    is Connection.Offline -> ConnectionView(
        ConnectionView.Kind.OFFLINE,
        reason = reason,
        detail = AppError.of(reason ?: AppError.Kind.NETWORK).userMessage,
    )
}
