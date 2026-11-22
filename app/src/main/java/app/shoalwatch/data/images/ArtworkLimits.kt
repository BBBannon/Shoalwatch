package app.shoalwatch.data.images

import coil3.size.Dimension
import coil3.size.Size

/** What an artwork request may ask for and what a response may be. Pure, so every rule is tested on the JVM. */
object ArtworkLimits {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 40_000_000L
    const val MAX_DECODED_PIXELS = 4_000_000L
    private const val MAX_SAMPLE = 64
    const val QUALITY = 90
    const val DEFAULT_WIDTH = 480
    val BUCKETS = intArrayOf(160, 320, 480, 720, 1280)
    /** Milestone 11: photos in the viewer are asked for at screen size, within a square of one of these (so 1,920 at most a side). */
    val PHOTO_BUCKETS = intArrayOf(720, 1280, 1920)

    enum class Kind(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp") }

    /** The smallest bucket at least [requestedPx] wide (the largest if it is bigger; the smallest for nonsense); [DEFAULT_WIDTH]'s bucket if unknown. */
    fun bucketFor(requestedPx: Int?): Int {
        val wanted = requestedPx ?: DEFAULT_WIDTH
        return BUCKETS.firstOrNull { it >= wanted } ?: BUCKETS.last()
    }

    fun bucketFor(size: Size): Int = bucketFor((size.width as? Dimension.Pixels)?.px)

    /** As [bucketFor], but a [ArtworkType.PHOTO] uses [PHOTO_BUCKETS] and the longer side of the slot (a photo is fitted, not cropped). */
    fun bucketFor(type: ArtworkType, size: Size): Int {
        if (type != ArtworkType.PHOTO) return bucketFor(size)
        val longest = listOfNotNull((size.width as? Dimension.Pixels)?.px, (size.height as? Dimension.Pixels)?.px).maxOrNull() ?: PHOTO_BUCKETS[1]
        return photoBucket(longest)
    }

    /** The photo size bucket (720, 1280 or 1920) for a screen whose longer side is [longestSidePx]: the smallest that is at least that, else 1920. */
    fun photoBucket(longestSidePx: Int): Int = PHOTO_BUCKETS.firstOrNull { it >= longestSidePx } ?: PHOTO_BUCKETS.last()

    /** The picture request's query: `tag`, `maxWidth` (and for a photo `maxHeight`, so it fits a square of [bucket]), quality and format. */
    fun query(type: ArtworkType, tag: String, bucket: Int, format: String): List<Pair<String, String>> = buildList {
        add("tag" to tag)
        add("maxWidth" to bucket.toString())
        if (type == ArtworkType.PHOTO) add("maxHeight" to bucket.toString())
        add("quality" to QUALITY.toString())
        add("format" to format)
    }

    /** The kind named by a Content-Type header (case-insensitive, parameters ignored), or null if it is not one of the three. */
    fun kindOfContentType(header: String?): Kind? {
        val type = header?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return Kind.entries.firstOrNull { it.mime == type }
    }

    /** The kind the first bytes show (JPEG `FF D8 FF`, the PNG signature, `RIFF....WEBP`), or null. Never trusts a header. */
    fun sniff(bytes: ByteArray): Kind? = when {
        bytes.startsWith(JPEG_MAGIC) -> Kind.JPEG
        bytes.startsWith(PNG_MAGIC) -> Kind.PNG
        bytes.size >= WEBP_HEAD && bytes.startsWith(RIFF) && bytes.regionEquals(8, WEBP) -> Kind.WEBP
        else -> null
    }

    /** Whether [bytes] end where a whole image of [kind] ends (JPEG end marker, PNG IEND chunk, the RIFF length), so a truncated download is never cached. */
    fun isComplete(kind: Kind, bytes: ByteArray): Boolean = when (kind) {
        Kind.JPEG -> {
            var end = bytes.size
            while (end > 0 && bytes[end - 1] == 0.toByte() && bytes.size - end < JPEG_PADDING) end--
            end >= 2 && bytes[end - 2] == 0xFF.toByte() && bytes[end - 1] == 0xD9.toByte()
        }
        Kind.PNG -> bytes.size >= PNG_IEND.size && bytes.regionEquals(bytes.size - PNG_IEND.size, PNG_IEND)
        Kind.WEBP -> bytes.size >= WEBP_HEAD && riffLength(bytes) + 8 == bytes.size.toLong()
    }

    /**
     * Whether [bytes] are an animated PNG (an `acTL` chunk before the image data) or an animated WebP (the animation flag of the
     * `VP8X` header). Both are refused, not shown as a still: detection is a few bytes and cannot be fooled into a loop.
     */
    fun isAnimated(kind: Kind, bytes: ByteArray): Boolean = when (kind) {
        Kind.JPEG -> false
        Kind.WEBP -> bytes.size > WEBP_FLAGS && bytes.regionEquals(WEBP_HEAD, VP8X) && (bytes[WEBP_FLAGS].toInt() and WEBP_ANIMATION) != 0
        Kind.PNG -> hasChunkBeforeImageData(bytes, ACTL)
    }

    private fun hasChunkBeforeImageData(png: ByteArray, wanted: ByteArray): Boolean {
        var offset = PNG_MAGIC.size.toLong()
        while (offset + CHUNK_OVERHEAD <= png.size) {
            val at = offset.toInt()
            val length = (0..3).fold(0L) { acc, i -> (acc shl 8) or (png[at + i].toLong() and 0xFF) }
            if (png.regionEquals(at + 4, wanted)) return true
            if (png.regionEquals(at + 4, IDAT) || png.regionEquals(at + 4, IEND)) return false
            offset += CHUNK_OVERHEAD + length
        }
        return false
    }

    /** Declared dimensions within [MAX_SIDE] a side and [MAX_PIXELS] in all (decompression-bomb guard, checked before any pixels are allocated). */
    fun dimensionsAcceptable(width: Int, height: Int): Boolean =
        width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS

    /**
     * The power-of-two `inSampleSize` for decoding a [width] x [height] image for a [bucket]-wide slot: the largest that keeps the
     * width at least the bucket (so it is under twice the bucket), raised until the decoded pixels fit [MAX_DECODED_PIXELS].
     * Null if it cannot fit at any sensible sample size.
     */
    fun sampleSize(width: Int, height: Int, bucket: Int): Int? {
        var sample = 1
        while (width / (sample * 2) >= bucket) sample *= 2
        while (ceilDiv(width, sample).toLong() * ceilDiv(height, sample) > MAX_DECODED_PIXELS) {
            sample *= 2
            if (sample > MAX_SAMPLE) return null
        }
        return sample
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b

    /** `art:<serverId>:<itemId>:<TYPE>:<tag>:<bucket>`: from the validated ref only, so never an address, and not the user (see "Artwork"). */
    fun cacheKey(ref: ArtworkRef, bucket: Int): String = "art:${ref.session.serverId}:${ref.itemId}:${ref.type.name}:${ref.tag}:$bucket"

    private const val WEBP_HEAD = 12
    private const val WEBP_FLAGS = 20
    private const val WEBP_ANIMATION = 0x02
    private const val CHUNK_OVERHEAD = 12
    private const val JPEG_PADDING = 16
    private val JPEG_MAGIC = intArrayOf(0xFF, 0xD8, 0xFF).toBytes()
    private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).toBytes()
    private val PNG_IEND = intArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82).toBytes()
    private val VP8X = "VP8X".toByteArray(Charsets.US_ASCII)
    private val ACTL = "acTL".toByteArray(Charsets.US_ASCII)
    private val IDAT = "IDAT".toByteArray(Charsets.US_ASCII)
    private val IEND = "IEND".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun IntArray.toBytes() = ByteArray(size) { this[it].toByte() }
    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && regionEquals(0, prefix)
    private fun ByteArray.regionEquals(offset: Int, other: ByteArray) = other.indices.all { this[offset + it] == other[it] }
    private fun riffLength(b: ByteArray): Long = (0..3).fold(0L) { acc, i -> acc or ((b[4 + i].toLong() and 0xFF) shl (8 * i)) }
}
