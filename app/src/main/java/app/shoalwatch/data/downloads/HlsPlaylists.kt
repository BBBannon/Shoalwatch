package app.shoalwatch.data.downloads

import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.StreamSource

/**
 * The bounded HLS parser and the app's own local playlist writer (pure). A server's playlist is untrusted: see "Download call
 * path" in docs/ARCHITECTURE.md for every rule. Nothing from a playlist ever reaches a refusal reason, a log or a file name.
 */
internal object HlsPlaylists {
    sealed interface Parsed {
        /** The first variant of a master playlist: a validated `/videos/...` media playlist path. */
        data class Master(val variant: StreamSource) : Parsed
        data class Media(val targetSeconds: Int, val segments: List<Segment>) : Parsed
        /** [reason] is a fixed word, never content. */
        data class Refused(val reason: String) : Parsed
    }

    data class Segment(val source: StreamSource, val durationMs: Long)

    const val MAX_CHARS = 2 * 1024 * 1024
    const val MAX_SEGMENTS = 20_000
    private const val MAX_LINE = 4_096
    private const val MAX_SEGMENT_MS = 60_000L
    private val DURATION = Regex("[0-9]{1,6}(\\.[0-9]{1,9})?")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /**
     * [playlistPath] is the validated server path (relative to the verified address, so it starts `/videos/`) the text came from:
     * relative URIs are resolved against its folder, then must pass `MediaPaths.checkServerPath` (which also strips credential
     * query names). Refused: more than [MAX_CHARS] characters or [MAX_SEGMENTS] segments; no `#EXTM3U` first; any control
     * character other than the line ends; a master whose first variant is unusable or not `.m3u8`; a media playlist that is not
     * VOD (`#EXT-X-PLAYLIST-TYPE:VOD` or `#EXT-X-ENDLIST`), has no `#EXT-X-TARGETDURATION` in 1..60, a duration outside
     * 0 < d <= 60 s, a URI without a duration, no segment, `#EXT-X-KEY` other than `METHOD=NONE`, `#EXT-X-MAP`,
     * `#EXT-X-BYTERANGE`, `#EXT-X-DISCONTINUITY`, an absolute URI or path (scheme, `//` or a leading `/`: the base path would
     * be lost), a `.` or `..` segment, a path outside `/videos/`, or a segment not ending `.ts`. Lines are cut to 4,096 characters.
     */
    fun parse(text: String, playlistPath: String): Parsed {
        if (text.length > MAX_CHARS) return Parsed.Refused("size")
        val folder = folderOf(playlistPath) ?: return Parsed.Refused("path")
        if (text.any { it.isISOControl() && it != '\n' && it != '\r' }) return Parsed.Refused("control")
        val lines = text.removePrefix("﻿").split('\n').map { it.removeSuffix("\r").take(MAX_LINE).trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "#EXTM3U") return Parsed.Refused("header")
        return if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) master(lines, folder) else media(lines, folder)
    }

    private fun master(lines: List<String>, folder: String): Parsed {
        val at = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        val uri = lines.drop(at + 1).firstOrNull { !it.startsWith("#") } ?: return Parsed.Refused("variant")
        val source = resolve(uri, folder, hls = true) ?: return Parsed.Refused("variant")
        if (!source.path.endsWith(".m3u8")) return Parsed.Refused("variant")
        return Parsed.Master(source)
    }

    private fun media(lines: List<String>, folder: String): Parsed {
        var target: Int? = null
        var vod = false
        var pending: Long? = null
        val segments = ArrayList<Segment>()
        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXTINF:") -> pending = durationMs(line.removePrefix("#EXTINF:").substringBefore(',')) ?: return Parsed.Refused("duration")
                line.startsWith("#EXT-X-TARGETDURATION:") -> target = line.substringAfter(':').toIntOrNull()?.takeIf { it in 1..60 } ?: return Parsed.Refused("target")
                line == "#EXT-X-ENDLIST" || line == "#EXT-X-PLAYLIST-TYPE:VOD" -> vod = true
                line.startsWith("#EXT-X-KEY") -> if (line != "#EXT-X-KEY:METHOD=NONE") return Parsed.Refused("key")
                line.startsWith("#EXT-X-MAP") -> return Parsed.Refused("map")
                line.startsWith("#EXT-X-BYTERANGE") -> return Parsed.Refused("byterange")
                line.startsWith("#EXT-X-DISCONTINUITY") -> return Parsed.Refused("discontinuity")
                line.startsWith("#") -> Unit // other tags and comments carry nothing the app uses
                else -> {
                    val ms = pending ?: return Parsed.Refused("orphan")
                    val source = resolve(line, folder, hls = false)?.takeIf { it.path.endsWith(".ts") } ?: return Parsed.Refused("uri")
                    if (segments.size == MAX_SEGMENTS) return Parsed.Refused("count")
                    segments += Segment(source, ms)
                    pending = null
                }
            }
        }
        return when {
            !vod -> Parsed.Refused("live")
            target == null -> Parsed.Refused("target")
            segments.isEmpty() -> Parsed.Refused("empty")
            else -> Parsed.Media(target, segments)
        }
    }

    /**
     * The app's own VOD playlist for a converted download: `seg-00000.ts`... named by index only, each duration in
     * milliseconds as seconds with three decimals (so it parses back exactly).
     */
    fun writeLocal(segmentsMs: List<Long>): String {
        require(segmentsMs.isNotEmpty() && segmentsMs.size <= MAX_SEGMENTS && segmentsMs.all { it in 1..MAX_SEGMENT_MS }) { "Invalid segments." }
        val target = ((segmentsMs.max() + 999) / 1000).coerceAtLeast(1)
        return buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:").append(target).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            segmentsMs.forEachIndexed { i, ms ->
                append("#EXTINF:").append(ms / 1000).append('.').append("%03d".format(ms % 1000)).append(",\n")
                append("seg-%05d.ts".format(i)).append('\n')
            }
            append("#EXT-X-ENDLIST\n")
        }
    }

    /** "/videos/a/b/" for "/videos/a/b/main.m3u8", or null when the playlist path itself is not a valid server path. */
    private fun folderOf(playlistPath: String): String? {
        val checked = MediaPaths.checkServerPath(playlistPath, hls = true) ?: return null
        if (checked.query.isNotEmpty() || checked.path != playlistPath) return null
        return checked.path.substringBeforeLast('/') + "/"
    }

    private fun resolve(uri: String, folder: String, hls: Boolean): StreamSource? {
        if (uri.startsWith("/") || SCHEME.containsMatchIn(uri.substringBefore('?').substringBefore('/'))) return null
        return MediaPaths.checkServerPath(folder + uri, hls)
    }

    private fun durationMs(raw: String): Long? {
        val text = raw.trim()
        if (!DURATION.matches(text)) return null
        val ms = text.toBigDecimal().movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
        return ms.takeIf { it in 1..MAX_SEGMENT_MS }
    }
}
