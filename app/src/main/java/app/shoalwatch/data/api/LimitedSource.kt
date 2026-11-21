package app.shoalwatch.data.api

import app.shoalwatch.core.AppError
import okio.Buffer
import okio.ForwardingSource
import okio.Source
import java.io.IOException

/** Fails with `SERVER_ERROR` on the first byte past [max], asking the server for no more than one byte beyond it. */
internal class LimitedSource(delegate: Source, private val max: Long) : ForwardingSource(delegate) {
    private var total = 0L

    /** Set when the connection itself failed while reading, which tells that apart from an IOException of the reader's own. */
    var networkFailure: IOException? = null
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        val n = try {
            super.read(sink, minOf(byteCount, max - total + 1))
        } catch (e: IOException) {
            networkFailure = e
            throw e
        }
        if (n > 0) {
            total += n
            if (total > max) throw AppError.of(AppError.Kind.SERVER_ERROR)
        }
        return n
    }
}
