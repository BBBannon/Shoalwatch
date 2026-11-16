package app.shoalwatch.core

import android.util.Log
import app.shoalwatch.BuildConfig

/** Debug-only logging. Everything passes through [Redact.text]; release builds log nothing. */
object AppLog {
    fun d(tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, Redact.text(msg))
    }

    /** [t] is logged as its full class name only; its message can hold hosts or URLs. */
    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        Log.w(tag, format(msg, t))
    }

    internal fun format(msg: String, t: Throwable?): String =
        Redact.text(msg) + (t?.let { " (${it.javaClass.name})" } ?: "")
}
