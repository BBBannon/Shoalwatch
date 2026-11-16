package app.shoalwatch.ui.present

import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness

/** Which compact banner a stale list shows (the wording and the action live in `FreshnessBanner`). */
enum class BannerKind {
    /** Offline, network or timeout: "showing what's saved", Retry. */
    OFFLINE,

    /** Signed out or sign-in expired: Sign in. */
    SIGN_IN,

    /** Certificate untrusted or changed: Review (the server's connection settings). */
    CERTIFICATE,

    /** Anything else: "Couldn't refresh", Retry. */
    GENERIC,
}

/** What a [Freshness] means for the screen: a thin progress line while [refreshing] and a banner when the saved copy is all there is. */
data class FreshnessUi(val refreshing: Boolean = false, val banner: BannerKind? = null)

fun Freshness.ui(): FreshnessUi = when (this) {
    Freshness.Fresh -> FreshnessUi()
    Freshness.Refreshing -> FreshnessUi(refreshing = true)
    is Freshness.Stale -> FreshnessUi(banner = reason.banner())
}

fun AppError.Kind.banner(): BannerKind = when (this) {
    AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT -> BannerKind.OFFLINE
    AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN -> BannerKind.SIGN_IN
    AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED -> BannerKind.CERTIFICATE
    else -> BannerKind.GENERIC
}

/** A single item the server no longer shows this user: nothing to show and "not found". Not a banner. */
fun Freshness.isGone(hasItem: Boolean): Boolean = !hasItem && this is Freshness.Stale && reason == AppError.Kind.SERVER_NOT_FOUND
