package app.shoalwatch.data.images

import coil3.size.Dimension
import coil3.size.Size

/** What an artwork request may ask for and what a response may be. Pure, so every rule is tested on the JVM. */
object ArtworkLimits {
    const val MAX_BYTES = 8L * 1024 * 1024
    const val MAX_SIDE = 8192
    const val MAX_PIXELS = 40_000_000L
    const val QUALITY = 90
    const val DEFAULT_WIDTH = 480
    val BUCKETS = intArrayOf(160, 320, 480, 720, 1280)

    enum class Kind(val mime: String) { JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp") }

    /** The smallest bucket at least [requestedPx] wide (the largest if it is bigger; the smallest for nonsense); [DEFAULT_WIDTH]'s bucket if unknown. */
    fun bucketFor(requestedPx: Int?): Int {
        val wanted = requestedPx ?: DEFAULT_WIDTH
        return BUCKETS.firstOrNull { it >= wanted } ?: BUCKETS.last()
    }

    fun bucketFor(size: Size): Int = bucketFor((size.width as? Dimension.Pixels)?.px)

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

    /** Declared dimensions within [MAX_SIDE] a side and [MAX_PIXELS] in all (decompression-bomb guard, checked before any pixels are allocated). */
    fun dimensionsAcceptable(width: Int, height: Int): Boolean =
        width in 1..MAX_SIDE && height in 1..MAX_SIDE && width.toLong() * height <= MAX_PIXELS

    /** `art:<serverId>:<itemId>:<TYPE>:<tag>:<bucket>`: from the validated ref only, so never an address, and not the user (see "Artwork"). */
    fun cacheKey(ref: ArtworkRef, bucket: Int): String = "art:${ref.session.serverId}:${ref.itemId}:${ref.type.name}:${ref.tag}:$bucket"

    private const val WEBP_HEAD = 12
    private const val JPEG_PADDING = 16
    private val JPEG_MAGIC = intArrayOf(0xFF, 0xD8, 0xFF).toBytes()
    private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).toBytes()
    private val PNG_IEND = intArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82).toBytes()
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)

    private fun IntArray.toBytes() = ByteArray(size) { this[it].toByte() }
    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && regionEquals(0, prefix)
    private fun ByteArray.regionEquals(offset: Int, other: ByteArray) = other.indices.all { this[offset + it] == other[it] }
    private fun riffLength(b: ByteArray): Long = (0..3).fold(0L) { acc, i -> acc or ((b[4 + i].toLong() and 0xFF) shl (8 * i)) }
}
