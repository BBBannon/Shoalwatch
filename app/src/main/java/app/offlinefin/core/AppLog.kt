package app.offlinefin.core

import android.util.Log
import app.offlinefin.BuildConfig

/** Debug-only logging. Everything passes through [Redact.text]; release builds log nothing. */
object AppLog {
    fun d(tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(tag, Redact.text(msg))
    }

    /** [t] is logged as class name plus redacted message only, never as a stack trace. */
    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        val detail = t?.let { " (${it.javaClass.simpleName}: ${Redact.text(it.message.orEmpty())})" }.orEmpty()
        Log.w(tag, Redact.text(msg) + detail)
    }
}
