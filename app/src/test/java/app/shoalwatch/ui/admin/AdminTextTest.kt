package app.shoalwatch.ui.admin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AdminTextTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val words = AdminText(Strings(context.resources), ZoneId.of("Europe/London"))
    private val defaultLocale: Locale = Locale.getDefault()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)
    private fun t(iso: String): Instant = Instant.parse(iso)

    @After fun restoreLocale() = Locale.setDefault(defaultLocale)

    private fun playing(
        kind: PlayingKind = PlayingKind.EPISODE,
        method: AdminPlayMethod? = AdminPlayMethod.TRANSCODE,
        video: Boolean? = false,
        audio: Boolean? = true,
        reasons: List<ConversionReason> = emptyList(),
        title: String = "Episode 3",
    ) = NowPlaying(kind, title, "Harbour Lights", 2, 3, 750_000, 2_700_000, false, method, video, audio, reasons)

    @Test fun times() {
        val now = t("2026-11-15T12:00:00Z")
        assertEquals(text(R.string.admin_never), words.ago(null, now))
        assertEquals(text(R.string.admin_time_just_now), words.ago(t("2026-11-15T11:59:30Z"), now))
        assertEquals(text(R.string.admin_time_just_now), words.ago(t("2026-11-15T12:00:20Z"), now))
        assertEquals(text(R.string.admin_time_minutes, "5"), words.ago(t("2026-11-15T11:55:00Z"), now))
        assertEquals(text(R.string.admin_time_minutes, "59"), words.ago(t("2026-11-15T11:00:30Z"), now))
        assertEquals(text(R.string.admin_time_today, "09:00"), words.ago(t("2026-11-15T09:00:00Z"), now))
        assertEquals(text(R.string.admin_time_yesterday, "23:50"), words.ago(t("2026-11-14T23:50:00Z"), now))
        assertEquals("3 Nov 2026", words.ago(t("2026-11-03T10:00:00Z"), now))
    }

    @Test fun acrossMidnight() {
        val now = t("2026-11-16T00:10:00Z")
        assertEquals(text(R.string.admin_time_minutes, "40"), words.ago(t("2026-11-15T23:30:00Z"), now))
        assertEquals(text(R.string.admin_time_yesterday, "22:00"), words.ago(t("2026-11-15T22:00:00Z"), now))
        assertEquals("14 Nov 2026", words.ago(t("2026-11-14T22:00:00Z"), now))
    }

    @Test fun acrossTheEndOfSummerTime() {
        // British Summer Time ended at 01:00 UTC on 25 October 2026.
        val now = t("2026-10-25T12:00:00Z")
        assertEquals("01:30 BST is today", text(R.string.admin_time_today, "01:30"), words.ago(t("2026-10-25T00:30:00Z"), now))
        assertEquals("22:30 UTC is 23:30 BST the day before", text(R.string.admin_time_yesterday, "23:30"), words.ago(t("2026-10-24T22:30:00Z"), now))
    }

    @Test fun digitsStayAsciiInAnyLanguage() {
        Locale.setDefault(Locale("ar", "EG"))
        val arabic = AdminText(Strings(context.resources), ZoneId.of("Europe/London"))
        val now = t("2026-11-15T12:00:00Z")
        val all = listOf(
            arabic.ago(t("2026-11-15T09:05:00Z"), now), arabic.ago(t("2026-11-03T10:00:00Z"), now), arabic.ago(t("2026-11-15T11:48:00Z"), now),
            arabic.clock(now), arabic.progress(3_723_000, 7_200_000)!!,
        )
        for (line in all) assertTrue(line, line.filter { it.isDigit() }.all { it in '0'..'9' })
        assertTrue(all[1].contains("3 Nov 2026"))
    }

    @Test fun progress() {
        assertEquals(text(R.string.admin_session_progress, "12:30", "45:00"), words.progress(750_000, 2_700_000))
        assertEquals(text(R.string.admin_session_progress, "1:02:03", "2:00:00"), words.progress(3_723_000, 7_200_000))
        assertEquals(text(R.string.admin_session_progress, "0:05:00", "2:00:00"), words.progress(300_000, 7_200_000))
        assertNull(words.progress(null, 7_200_000))
        assertNull(words.progress(1_000, null))
    }

    @Test fun howItIsPlayed() {
        val video = text(R.string.admin_convert_video)
        val reasons = mapOf(
            ConversionReason.VIDEO_FORMAT to R.string.admin_reason_video_format, ConversionReason.AUDIO_FORMAT to R.string.admin_reason_audio_format,
            ConversionReason.FILE_TYPE to R.string.admin_reason_file_type, ConversionReason.SUBTITLES to R.string.admin_reason_subtitles,
            ConversionReason.BITRATE to R.string.admin_reason_bitrate, ConversionReason.HDR to R.string.admin_reason_hdr,
            ConversionReason.AUDIO_CHANNELS to R.string.admin_reason_audio_channels,
            ConversionReason.DIRECT_PLAY_FAILED to R.string.admin_reason_direct_play_failed,
        )
        for ((reason, id) in reasons) {
            assertEquals(reason.name, text(R.string.admin_convert_sentence, video, text(id)), words.method(playing(reasons = listOf(reason))))
        }
        assertEquals("the first reason that can be said", text(R.string.admin_convert_sentence, video, text(R.string.admin_reason_hdr)),
            words.method(playing(reasons = listOf(ConversionReason.OTHER, ConversionReason.HDR, ConversionReason.BITRATE))))
        assertEquals(text(R.string.admin_convert_no_reason, video), words.method(playing(reasons = listOf(ConversionReason.OTHER))))
        assertEquals(text(R.string.admin_convert_no_reason, video), words.method(playing()))
        assertEquals(text(R.string.admin_convert_no_reason, text(R.string.admin_convert_audio_only)), words.method(playing(video = true, audio = false)))
        assertEquals(text(R.string.admin_method_direct_play), words.method(playing(method = AdminPlayMethod.DIRECT_PLAY)))
        assertEquals(text(R.string.admin_method_direct_stream), words.method(playing(method = AdminPlayMethod.DIRECT_STREAM)))
        assertNull(words.method(playing(method = null)))
    }

    @Test fun whatIsPlaying() {
        assertEquals(text(R.string.admin_session_episode, "Harbour Lights", "2", "3", "Episode 3"), words.nowPlayingLine(playing()))
        assertEquals(text(R.string.admin_session_episode_spoken, "Harbour Lights", "2", "3", "Episode 3"), words.nowPlayingSpoken(playing()))
        assertEquals("The Silent Harbour", words.nowPlayingLine(playing(kind = PlayingKind.FILM, title = "The Silent Harbour")))
        assertEquals(text(R.string.admin_untitled), words.nowPlayingLine(playing(kind = PlayingKind.OTHER, title = "")))
        assertEquals(text(R.string.admin_session_episode, "Harbour Lights", "2", "3", text(R.string.admin_untitled)), words.nowPlayingLine(playing(title = "")))
    }

    @Test fun deviceLine() {
        fun session(device: String, client: String, version: String) =
            WatchSession("a1", "kid", device, client, version, null, null, canControl = false, canMessage = false, isThisDevice = false)
        assertEquals(text(R.string.admin_session_device, "Living room TV", "Jellyfin Android TV", "0.17.0"), words.deviceLine(session("Living room TV", "Jellyfin Android TV", "0.17.0")))
        assertEquals(text(R.string.admin_session_device_no_version, "Living room TV", "Kodi"), words.deviceLine(session("Living room TV", "Kodi", "")))
        assertEquals(text(R.string.admin_unnamed_device), words.deviceLine(session("", "", "")))
    }

    @Test fun everyWord() {
        val kinds = mapOf(
            LibraryKind.FILMS to R.string.admin_kind_films, LibraryKind.TV to R.string.admin_kind_tv, LibraryKind.MUSIC to R.string.admin_kind_music,
            LibraryKind.COLLECTIONS to R.string.admin_kind_collections, LibraryKind.MIXED to R.string.admin_kind_mixed, LibraryKind.OTHER to R.string.admin_kind_other,
        )
        assertEquals(LibraryKind.entries.toSet(), kinds.keys)
        for ((kind, id) in kinds) assertEquals(text(id), words.libraryKind(kind))
        val severities = mapOf(
            Severity.INFORMATION to R.string.admin_severity_information, Severity.WARNING to R.string.admin_severity_warning,
            Severity.ERROR to R.string.admin_severity_error, Severity.CRITICAL to R.string.admin_severity_critical,
        )
        for ((severity, id) in severities) assertEquals(text(id), words.severity(severity))
        val outcomes = mapOf(
            TaskOutcome.COMPLETED to R.string.admin_outcome_completed, TaskOutcome.FAILED to R.string.admin_outcome_failed,
            TaskOutcome.CANCELLED to R.string.admin_outcome_cancelled, TaskOutcome.ABORTED to R.string.admin_outcome_aborted,
        )
        for ((outcome, id) in outcomes) assertEquals(text(id), words.outcome(outcome))
        val problems = mapOf(
            AdminProblem.OFFLINE to R.string.admin_problem_offline, AdminProblem.NETWORK to R.string.admin_problem_network,
            AdminProblem.REFUSED to R.string.admin_problem_refused, AdminProblem.GONE to R.string.admin_problem_gone,
            AdminProblem.SERVER to R.string.admin_problem_server, AdminProblem.CONNECTION to R.string.admin_problem_connection,
            AdminProblem.OTHER to R.string.admin_problem_other,
        )
        assertEquals(AdminProblem.entries.toSet(), problems.keys)
        for ((problem, id) in problems) assertEquals(text(id), words.problem(problem))
        assertEquals("Unnamed user", words.name("", R.string.admin_unnamed_user))
        assertEquals("kid", words.name("kid", R.string.admin_unnamed_user))
    }

    @Test fun typedConfirmation() {
        for (typed in listOf("restart", "Restart ", "RESTART", " restart")) assertTrue(typed, typedMatches(typed, "restart"))
        for (typed in listOf("shut down", "Shut  down", "SHUT DOWN", " shut\tdown ")) assertTrue(typed, typedMatches(typed, "shut down"))
        for (typed in listOf("restar", "restarts", "", "re start")) assertFalse(typed, typedMatches(typed, "restart"))
        assertFalse(typedMatches("shutdown", "shut down"))
    }
}
