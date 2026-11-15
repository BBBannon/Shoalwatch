package app.shoalwatch.ui.admin

import app.shoalwatch.data.admin.AdminPlayMethod
import app.shoalwatch.data.admin.ConversionReason
import app.shoalwatch.data.admin.FULL_SCAN_TASK_KEY
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.LibraryKind
import app.shoalwatch.data.admin.NowPlaying
import app.shoalwatch.data.admin.PlayingKind
import app.shoalwatch.data.admin.ServerDevice
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.ServerUser
import app.shoalwatch.data.admin.TaskOutcome
import app.shoalwatch.data.admin.TaskRun
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.admin.WatchSession
import java.time.Instant

/** Made-up admin answers shared by the admin ViewModel and render tests. */
object AdminSamples {
    val now: Instant = Instant.parse("2026-11-15T14:02:05Z")

    val episode = NowPlaying(
        kind = PlayingKind.EPISODE, title = "Title", seriesName = "Harbour Lights", season = 2, episode = 3,
        positionMs = 750_000, runtimeMs = 2_700_000, paused = false, method = AdminPlayMethod.TRANSCODE,
        videoDirect = false, audioDirect = true, reasons = listOf(ConversionReason.VIDEO_FORMAT),
    )

    val film = NowPlaying(
        kind = PlayingKind.FILM, title = "The Long Tide", seriesName = null, season = null, episode = null,
        positionMs = 60_000, runtimeMs = 5_400_000, paused = true, method = AdminPlayMethod.DIRECT_PLAY,
        videoDirect = true, audioDirect = true, reasons = emptyList(),
    )

    /** kid watching an episode on the TV, being converted; can be paused, stopped and messaged. */
    val kidOnTv = WatchSession(
        id = "sesskid", userName = "kid", deviceName = "Living room TV", client = "Jellyfin Android TV", clientVersion = "0.17.0",
        lastActivity = now, nowPlaying = episode, canControl = true, canMessage = true, isThisDevice = false,
    )

    /** guest with a paused film on a laptop. */
    val guestPaused = WatchSession(
        id = "sessguest", userName = "guest", deviceName = "Laptop", client = "Jellyfin Web", clientVersion = "10.11.0",
        lastActivity = now, nowPlaying = film, canControl = true, canMessage = true, isThisDevice = false,
    )

    /** ben on this phone: no actions. */
    val benHere = WatchSession(
        id = "sessben", userName = "ben", deviceName = "Pixel", client = "Shoalwatch", clientVersion = "1.0",
        lastActivity = now, nowPlaying = film.copy(paused = false), canControl = true, canMessage = true, isThisDevice = true,
    )

    /** A player that cannot be controlled or messaged. */
    val noControl = WatchSession(
        id = "sessdlna", userName = "gran", deviceName = "Old TV", client = "DLNA", clientVersion = "",
        lastActivity = now, nowPlaying = film.copy(paused = false), canControl = false, canMessage = false, isThisDevice = false,
    )

    /** kid's tablet, connected and not playing, last active three days ago. */
    val kidIdle = WatchSession(
        id = "sesstablet", userName = "kid", deviceName = "Kid's tablet", client = "Jellyfin Android", clientVersion = "2.6.0",
        lastActivity = Instant.parse("2026-11-12T09:00:00Z"), nowPlaying = null, canControl = true, canMessage = true, isThisDevice = false,
    )
}
