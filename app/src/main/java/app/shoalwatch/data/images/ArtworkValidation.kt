package app.shoalwatch.data.images

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.images.ArtworkRejectedException.Reason
import java.io.ByteArrayInputStream

/** The one set of checks on a whole picture, for a fresh download and for a saved entry alike. */
internal object ArtworkValidation {
    private const val TAG = "Artwork"

    /**
     * The kind of [bytes], or an [ArtworkRejectedException] naming the first check that failed. [declared] is the kind the
     * Content-Type named (null for a saved entry, which has no header); when given it must EQUAL what the first bytes show.
     * Order: first bytes, header agreement, complete, not animated, declared dimensions (header only, no pixels).
     */
    fun check(declared: ArtworkLimits.Kind?, bytes: ByteArray): ArtworkLimits.Kind {
        val kind = ArtworkLimits.sniff(bytes) ?: throw refuse(Reason.NOT_AN_IMAGE)
        if (declared != null && declared != kind) throw refuse(Reason.CONTENT_TYPE)
        if (!ArtworkLimits.isComplete(kind, bytes)) throw refuse(Reason.INCOMPLETE)
        if (ArtworkLimits.isAnimated(kind, bytes)) throw refuse(Reason.ANIMATED)
        val bounds = ArtworkBounds.read(ByteArrayInputStream(bytes))
        if (bounds == null || !ArtworkLimits.dimensionsAcceptable(bounds.first, bounds.second)) throw refuse(Reason.DIMENSIONS)
        return kind
    }

    fun refuse(reason: Reason): ArtworkRejectedException {
        AppLog.d(TAG, "artwork refused: $reason")
        return ArtworkRejectedException(reason)
    }
}
