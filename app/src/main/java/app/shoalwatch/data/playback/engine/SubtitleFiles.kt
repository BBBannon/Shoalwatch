package app.shoalwatch.data.playback.engine

import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import java.util.TreeSet

/**
 * Text subtitle files (SubRip, WebVTT, ASS/SSA) read by the app's own parsers ([SrtFile], [VttFile], [AssFile]), as untrusted
 * input: called off the main thread by `SubtitleLoader` with a size already checked. Media3's text parsers never see a server
 * file: four reviews each found another way to make them do quadratic work that nothing could stop. Ours are linear by
 * construction (see "Tracks and subtitles" in docs/ARCHITECTURE.md): one pass per line, no backtracking pattern, no look at
 * earlier cues, every token bounded, and the work checked for cancellation at least every [CHECK_EVERY_LINES] lines. Only
 * Media3's `Cue` is built here, for `SubtitleView` to draw. Nothing from the file is logged or put in an exception message.
 */
@OptIn(UnstableApi::class)
internal object SubtitleFiles {
    /** As `SubtitleLoader.MAX_BYTES`, checked again here: over it, refused. */
    const val MAX_BYTES = 8 * 1024 * 1024
    /** Cues (timing lines, or ASS `Dialogue:` lines, read or not) in one file: over it, refused. */
    const val MAX_CUES = 50_000
    /** Characters of one cue's text after tags are removed: the rest of that cue is dropped. */
    const val MAX_CUE_CHARS = 2_000
    /** Lines of one cue: the rest of that cue is dropped. */
    const val MAX_CUE_LINES = 16
    /** Characters of an ASS header line (`[Script Info]`, styles, any `Format:`), checked before it is split: over it, refused. */
    const val MAX_HEADER_LINE = 4_096
    /** ASS styles read: later ones are ignored. */
    const val MAX_STYLES = 256
    /** Characters of any id, style name or setting: a longer one is ignored (an unknown style, an unread setting). */
    const val MAX_TOKEN = 64
    /** Cues drawn at one instant (a hostile file could stack thousands). */
    const val MAX_CUES_AT_ONCE = 16
    /** Characters of text handed to the view in one frame: a cue that would pass it is left out of that frame. */
    const val MAX_FRAME_CHARS = 4_000
    /** Styled runs (bold, italic, underline changes) kept per cue: later style changes in that cue are ignored, the text kept. */
    const val MAX_STYLED_RUNS = 32
    /** How often (in cues) a parse also asks whether it has been given up. */
    const val CHECK_EVERY_CUES = 50
    /** How often (in lines, and timeline steps) a parse asks whether it has been given up. */
    const val CHECK_EVERY_LINES = 1_000
    /** A cue starting later than this is dropped; one ending later ends here. */
    const val MAX_TIME_MS = 48L * 60 * 60 * 1_000

    /**
     * Throws [IllegalArgumentException] (a fixed message) for anything unreadable: an unsupported format, a file over a cap,
     * a broken WebVTT header or ASS `Format:`, running out of memory, and [cancelled] turning true ("Stopped.").
     */
    fun parse(bytes: ByteArray, format: String, cancelled: () -> Boolean = { false }): CueTimeline = guarded {
        val reader: (SubtitleLines, Drafts) -> Unit = when (format) {
            "srt" -> SrtFile::read
            "vtt" -> VttFile::read
            "ass", "ssa" -> AssFile::read
            else -> throw IllegalArgumentException("Unsupported subtitle format.")
        }
        if (bytes.size > MAX_BYTES) throw unreadable()
        val drafts = Drafts(cancelled)
        reader(SubtitleLines(bytes, cancelled), drafts)
        CueTimeline.of(drafts, cancelled)
    }

    /** [block], with every failure as the fixed [IllegalArgumentException]: a stack overflow and running out of memory too. */
    internal inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        throw unreadable()
    } catch (e: StackOverflowError) {
        throw unreadable()
    } catch (e: OutOfMemoryError) {
        throw unreadable()
    }

    fun unreadable() = IllegalArgumentException("Unreadable subtitles.")
    fun tooMany() = IllegalArgumentException("Too many subtitles.")
    fun stopped() = IllegalArgumentException("Stopped.")
}

/** One cue as read: its times (ms), ASS layer (0 otherwise) and place in the file. */
internal class Draft(val startMs: Long, val endMs: Long, val layer: Int, val seq: Int, val cue: Cue)

/** The cues a parser has read, and whether an ASS file asked for effects this phone cannot draw. */
internal class Drafts(private val cancelled: () -> Boolean = { false }) {
    val list = ArrayList<Draft>()
    private var seen = 0
    var effects = false

    /** Every timing line (or `Dialogue:` line) counts, whether or not it can be read; every 50th also asks [cancelled]. */
    fun count() {
        if (++seen > SubtitleFiles.MAX_CUES) throw SubtitleFiles.tooMany()
        if (seen % SubtitleFiles.CHECK_EVERY_CUES == 0 && cancelled()) throw SubtitleFiles.stopped()
    }

    /** Dropped when it ends before it starts, starts after [SubtitleFiles.MAX_TIME_MS], or has no text. */
    fun add(startMs: Long, endMs: Long, layer: Int, cue: Cue?) {
        if (cue == null || startMs < 0 || startMs > SubtitleFiles.MAX_TIME_MS || endMs <= startMs) return
        list += Draft(startMs, minOf(endMs, SubtitleFiles.MAX_TIME_MS), layer, list.size, cue)
    }
}

/**
 * All the cues of one text subtitle file in time order, for drawing at (position - delay). Built once: the cues sorted by
 * start (n log n), then a sweep over every distinct start and end that keeps the cues showing in a sorted set, so each
 * frame is the first [SubtitleFiles.MAX_CUES_AT_ONCE] of them in start order (file order for the same start), drawn in ASS
 * layer order. [frameAt] is a binary search, cheap enough for the main thread many times a second.
 */
class CueTimeline internal constructor(
    private val startsMs: LongArray,
    private val frames: List<SubtitleFrame>,
    /** `\k`, `\K`, `\kf`, `\ko`, `\t(`, `\move(`, `\fad(`, `\fade(`, `\p1`..`\p9`, `\clip(`, `\iclip(` or `\fn` found in the ASS events. */
    val usesEffectsThePhoneCannotDraw: Boolean,
) {
    /** How many changes of what is shown the file has. */
    val size: Int get() = frames.size

    fun frameAt(positionMs: Long): SubtitleFrame {
        if (positionMs < 0 || startsMs.isEmpty() || positionMs < startsMs[0]) return SubtitleFrame.NONE
        var lo = 0
        var hi = startsMs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (startsMs[mid] <= positionMs) lo = mid else hi = mid - 1
        }
        return frames[lo]
    }

    override fun toString() = "CueTimeline(<redacted>)"

    internal companion object {
        fun of(drafts: Drafts, cancelled: () -> Boolean = { false }): CueTimeline {
            val byStart = drafts.list.sortedWith(compareBy({ it.startMs }, { it.seq }))
            val n = byStart.size
            val times = LongArray(n * 2) { if (it < n) byStart[it].startMs else byStart[it - n].endMs }.also { it.sort() }
            val byEnd = (0 until n).sortedBy { byStart[it].endMs }
            val active = TreeSet<Int>() // positions in byStart: start order
            var nextStart = 0
            var nextEnd = 0
            val starts = ArrayList<Long>()
            val frames = ArrayList<SubtitleFrame>()
            for ((step, t) in times.withIndex()) {
                if (step % SubtitleFiles.CHECK_EVERY_LINES == 0 && cancelled()) throw SubtitleFiles.stopped()
                if (step > 0 && times[step - 1] == t) continue
                while (nextEnd < n && byStart[byEnd[nextEnd]].endMs <= t) active.remove(byEnd[nextEnd++])
                while (nextStart < n && byStart[nextStart].startMs <= t) {
                    if (byStart[nextStart].endMs > t) active.add(nextStart)
                    nextStart++
                }
                val shown = shown(active, byStart)
                val frame = if (shown.isEmpty()) SubtitleFrame.NONE else SubtitleFrame(shown)
                if (frames.isNotEmpty() && frame.isEmpty && frames.last().isEmpty) continue
                starts += t
                frames += frame
            }
            return CueTimeline(starts.toLongArray(), frames, drafts.effects)
        }

        /**
         * The first cues showing, in start order, while they number at most [SubtitleFiles.MAX_CUES_AT_ONCE] and hold at most
         * [SubtitleFiles.MAX_FRAME_CHARS] characters between them (review m-2: the view lays them all out on the main thread);
         * then in layer order for drawing.
         */
        private fun shown(active: TreeSet<Int>, byStart: List<Draft>): List<Cue> {
            val out = ArrayList<Draft>(SubtitleFiles.MAX_CUES_AT_ONCE)
            var chars = 0
            for (i in active) {
                val d = byStart[i]
                val length = d.cue.text?.length ?: 0
                if (out.size == SubtitleFiles.MAX_CUES_AT_ONCE || chars + length > SubtitleFiles.MAX_FRAME_CHARS) break
                out += d
                chars += length
            }
            return out.sortedBy { it.layer }.map { it.cue }
        }
    }
}
