package app.shoalwatch.ui.admin

import androidx.annotation.StringRes
import app.shoalwatch.R
import app.shoalwatch.data.admin.AdminPlayMethod
import app.shoalwatch.data.admin.ConversionReason
import app.shoalwatch.data.admin.LibraryKind
import app.shoalwatch.data.admin.NowPlaying
import app.shoalwatch.data.admin.PlayingKind
import app.shoalwatch.data.admin.Severity
import app.shoalwatch.data.admin.TaskOutcome
import app.shoalwatch.data.admin.WatchSession
import app.shoalwatch.ui.present.Strings
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/**
 * Admin answers in plain words (pure; real resources through [Strings]). Numbers and times are always ASCII digits, whatever
 * the phone's language, and times are in [zone]. Names that were empty after `displayText` get a neutral word via [name].
 */
class AdminText(private val s: Strings, private val zone: ZoneId) {
    private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withDecimalStyle(DecimalStyle.STANDARD)
    private val clockTime = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK).withDecimalStyle(DecimalStyle.STANDARD)
    private val longDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withDecimalStyle(DecimalStyle.STANDARD)

    /** "Just now", "5 min ago", "Today at 14:02", "Yesterday at 23:50", "3 Nov 2026", or "Never" for null. */
    fun ago(at: Instant?, now: Instant): String {
        if (at == null) return s.get(R.string.admin_never)
        val elapsed = Duration.between(at, now)
        if (elapsed < Duration.ofMinutes(1)) return s.get(R.string.admin_time_just_now)
        if (elapsed < Duration.ofHours(1)) return s.get(R.string.admin_time_minutes, elapsed.toMinutes().toString())
        val day = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (day) {
            today -> s.get(R.string.admin_time_today, hourMinute.format(at.atZone(zone)))
            today.minusDays(1) -> s.get(R.string.admin_time_yesterday, hourMinute.format(at.atZone(zone)))
            else -> longDate.format(at.atZone(zone))
        }
    }

    /** "14:02:05", for "Last updated". */
    fun clock(at: Instant): String = clockTime.format(at.atZone(zone))

    /** "12:30 of 45:00", or "1:02:03 of 2:00:00" when the runtime is an hour or more; null if either part is unknown. */
    fun progress(positionMs: Long?, runtimeMs: Long?): String? {
        if (positionMs == null || runtimeMs == null) return null
        val hours = runtimeMs >= HOUR_MS || positionMs >= HOUR_MS
        return s.get(R.string.admin_session_progress, duration(positionMs, hours), duration(runtimeMs, hours))
    }

    /** "Harbour Lights · S2 E3 · Episode 3" for an episode; the title otherwise ("Untitled" when empty). */
    fun nowPlayingLine(n: NowPlaying): String = episodeParts(n)?.let { (series, season, episode, title) ->
        s.get(R.string.admin_session_episode, series, season, episode, title)
    } ?: title(n)

    /** The same, as TalkBack should read it: "Harbour Lights, season 2, episode 3, Episode 3". */
    fun nowPlayingSpoken(n: NowPlaying): String = episodeParts(n)?.let { (series, season, episode, title) ->
        s.get(R.string.admin_session_episode_spoken, series, season, episode, title)
    } ?: title(n)

    /** Whether the server is converting it, and why, in one sentence; null when the server did not say how it is played. */
    fun method(n: NowPlaying): String? = when (n.method) {
        null -> null
        AdminPlayMethod.DIRECT_PLAY -> s.get(R.string.admin_method_direct_play)
        AdminPlayMethod.DIRECT_STREAM -> s.get(R.string.admin_method_direct_stream)
        AdminPlayMethod.TRANSCODE -> {
            val audioOnly = n.videoDirect == true && n.audioDirect == false
            val subject = s.get(if (audioOnly) R.string.admin_convert_audio_only else R.string.admin_convert_video)
            val reason = n.reasons.firstOrNull { it != ConversionReason.OTHER }?.let(::reasonId)
            if (reason == null) s.get(R.string.admin_convert_no_reason, subject) else s.get(R.string.admin_convert_sentence, subject, s.get(reason))
        }
    }

    /** "Living room TV · Jellyfin Android TV 0.17.0" (without the version when there is none, without the app when there is none). */
    fun deviceLine(session: WatchSession): String {
        val device = name(session.deviceName, R.string.admin_unnamed_device)
        return when {
            session.client.isEmpty() -> device
            session.clientVersion.isEmpty() -> s.get(R.string.admin_session_device_no_version, device, session.client)
            else -> s.get(R.string.admin_session_device, device, session.client, session.clientVersion)
        }
    }

    fun libraryKind(kind: LibraryKind): String = s.get(
        when (kind) {
            LibraryKind.FILMS -> R.string.admin_kind_films
            LibraryKind.TV -> R.string.admin_kind_tv
            LibraryKind.MUSIC -> R.string.admin_kind_music
            LibraryKind.COLLECTIONS -> R.string.admin_kind_collections
            LibraryKind.MIXED -> R.string.admin_kind_mixed
            LibraryKind.OTHER -> R.string.admin_kind_other
        },
    )

    fun severity(severity: Severity): String = s.get(
        when (severity) {
            Severity.INFORMATION -> R.string.admin_severity_information
            Severity.WARNING -> R.string.admin_severity_warning
            Severity.ERROR -> R.string.admin_severity_error
            Severity.CRITICAL -> R.string.admin_severity_critical
        },
    )

    fun outcome(outcome: TaskOutcome): String = s.get(
        when (outcome) {
            TaskOutcome.COMPLETED -> R.string.admin_outcome_completed
            TaskOutcome.FAILED -> R.string.admin_outcome_failed
            TaskOutcome.CANCELLED -> R.string.admin_outcome_cancelled
            TaskOutcome.ABORTED -> R.string.admin_outcome_aborted
        },
    )

    fun problem(problem: AdminProblem): String = s.get(
        when (problem) {
            AdminProblem.OFFLINE -> R.string.admin_problem_offline
            AdminProblem.NETWORK -> R.string.admin_problem_network
            AdminProblem.REFUSED -> R.string.admin_problem_refused
            AdminProblem.GONE -> R.string.admin_problem_gone
            AdminProblem.SERVER -> R.string.admin_problem_server
            AdminProblem.CONNECTION -> R.string.admin_problem_connection
            AdminProblem.OTHER -> R.string.admin_problem_other
        },
    )

    /** [raw] (already safe display text), or the neutral word [fallback] when it is empty. */
    fun name(raw: String, @StringRes fallback: Int): String = raw.ifEmpty { s.get(fallback) }

    private fun title(n: NowPlaying) = name(n.title, R.string.admin_untitled)

    private data class EpisodeParts(val series: String, val season: String, val episode: String, val title: String)

    private fun episodeParts(n: NowPlaying): EpisodeParts? {
        if (n.kind != PlayingKind.EPISODE) return null
        val series = n.seriesName ?: return null
        val season = n.season ?: return null
        val episode = n.episode ?: return null
        return EpisodeParts(series, season.toString(), episode.toString(), title(n))
    }

    private fun duration(ms: Long, hours: Boolean): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (hours) "$h:${two(m)}:${two(sec)}" else "${total / 60}:${two(sec)}"
    }

    private fun two(n: Long) = if (n < 10) "0$n" else n.toString()

    private fun reasonId(reason: ConversionReason): Int? = when (reason) {
        ConversionReason.VIDEO_FORMAT -> R.string.admin_reason_video_format
        ConversionReason.AUDIO_FORMAT -> R.string.admin_reason_audio_format
        ConversionReason.FILE_TYPE -> R.string.admin_reason_file_type
        ConversionReason.SUBTITLES -> R.string.admin_reason_subtitles
        ConversionReason.BITRATE -> R.string.admin_reason_bitrate
        ConversionReason.HDR -> R.string.admin_reason_hdr
        ConversionReason.AUDIO_CHANNELS -> R.string.admin_reason_audio_channels
        ConversionReason.DIRECT_PLAY_FAILED -> R.string.admin_reason_direct_play_failed
        ConversionReason.OTHER -> null
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}

/** Trimmed, case-insensitive, runs of white space as one space. */
fun typedMatches(typed: CharSequence, word: String): Boolean = normalise(typed) == normalise(word)

private fun normalise(text: CharSequence): String = text.trim().split(Regex("\\s+")).joinToString(" ").lowercase(Locale.ROOT)
