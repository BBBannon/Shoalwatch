package app.shoalwatch.data.admin

import app.shoalwatch.data.admin.api.ActivityResultDto
import app.shoalwatch.data.admin.api.AdminLimits
import app.shoalwatch.data.admin.api.DeviceResultDto
import app.shoalwatch.data.admin.api.SystemInfoDto
import app.shoalwatch.data.admin.api.UserMeDto
import app.shoalwatch.data.admin.api.conversionReason
import app.shoalwatch.data.admin.api.toDevices
import app.shoalwatch.data.admin.api.toLibraries
import app.shoalwatch.data.admin.api.toMe
import app.shoalwatch.data.admin.api.toPage
import app.shoalwatch.data.admin.api.toSessions
import app.shoalwatch.data.admin.api.toStatus
import app.shoalwatch.data.admin.api.toTasks
import app.shoalwatch.data.admin.api.toUsers
import app.shoalwatch.data.api.JsonBodies
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

internal object AdminFixtures {
    const val THIS_PHONE = "this-phone-device"
    const val BEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val KID = "1d5e1f3b2a4c4e6f8a9b0c1d2e3f4a5b"

    fun text(name: String): String = AdminFixtures::class.java.getResource("/jellyfin/admin/$name")!!.readText()
    fun array(name: String): List<JsonElement> = JsonBodies.JSON.parseToJsonElement(text(name)).jsonArray
    inline fun <reified T> decode(name: String): T = JsonBodies.JSON.decodeFromString(text(name))
}

class AdminMappersTest {
    private val sessions = AdminFixtures.array("sessions.json").toSessions(AdminFixtures.THIS_PHONE)

    @Test fun theKidsLivingRoomSessionIsAnEpisodeBeingConvertedForItsVideo() {
        val tv = sessions.single { it.deviceName == "Living room TV" }
        assertEquals("a1b2c3d4e5f60718293a4b5c6d7e8f90", tv.id)
        assertEquals(listOf("kid", "Jellyfin Android TV", "0.17.0"), listOf(tv.userName, tv.client, tv.clientVersion))
        assertEquals(Instant.parse("2026-11-15T19:00:00Z"), tv.lastActivity)
        val playing = tv.nowPlaying!!
        assertEquals(PlayingKind.EPISODE, playing.kind)
        assertEquals("Episode 3", playing.title)
        assertEquals("Harbour Lights", playing.seriesName)
        assertEquals(2 to 3, playing.season to playing.episode)
        assertEquals(750_000L to 2_700_000L, playing.positionMs to playing.runtimeMs)
        assertFalse(playing.paused)
        assertEquals(AdminPlayMethod.TRANSCODE, playing.method)
        assertEquals(false to true, playing.videoDirect to playing.audioDirect)
        assertEquals(listOf(ConversionReason.VIDEO_FORMAT), playing.reasons)
        assertEquals("what a confirmation is checked against", "6c0e2b0f4f2d4d8b9a4f3c0f4e6f7a8b", playing.itemId)
        assertTrue(tv.canControl && tv.canMessage && !tv.isThisDevice)
    }

    @Test fun aFilmPlayedDirectlyReadsNoConversionDetails() {
        val laptop = sessions.single { it.deviceName == "Ben's laptop" }.nowPlaying!!
        assertEquals(PlayingKind.FILM, laptop.kind)
        assertNull(laptop.seriesName)
        assertEquals(AdminPlayMethod.DIRECT_PLAY, laptop.method)
        assertTrue(laptop.paused)
        assertEquals(emptyList<ConversionReason>(), laptop.reasons)
        assertNull(laptop.videoDirect)
        assertNull(laptop.audioDirect)
    }

    @Test fun theIdleTabletAndThisPhone() {
        val tablet = sessions.single { it.deviceName == "Kid's tablet" }
        assertNull(tablet.nowPlaying)
        assertFalse(tablet.canMessage || tablet.canControl)
        assertTrue(sessions.single { it.deviceName == "Pixel 8" }.isThisDevice)
        assertEquals(1, sessions.count { it.isThisDevice })
    }

    @Test fun sessionsArePlayingFirstThenNewestFirst() {
        assertEquals(listOf("Living room TV", "Ben's laptop", "Pixel 8", "Kid's tablet"), sessions.map { it.deviceName })
    }

    @Test fun everyTranscodeReasonInTheReferenceMapsAsListed() {
        val reference = JsonBodies.JSON.parseToJsonElement(File("../docs/reference/jellyfin-openapi-12.4.0.json").readText())
        val names = reference.jsonObject["components"]!!.jsonObject["schemas"]!!.jsonObject["TranscodeReason"]!!.jsonObject["enum"]!!
            .jsonArray.map { it.jsonPrimitive.content }
        val expected = mapOf(
            "ContainerNotSupported" to ConversionReason.FILE_TYPE, "VideoCodecNotSupported" to ConversionReason.VIDEO_FORMAT,
            "AudioCodecNotSupported" to ConversionReason.AUDIO_FORMAT, "SubtitleCodecNotSupported" to ConversionReason.SUBTITLES,
            "AudioIsExternal" to ConversionReason.AUDIO_FORMAT, "SecondaryAudioNotSupported" to ConversionReason.AUDIO_FORMAT,
            "VideoProfileNotSupported" to ConversionReason.VIDEO_FORMAT, "VideoLevelNotSupported" to ConversionReason.VIDEO_FORMAT,
            "VideoResolutionNotSupported" to ConversionReason.BITRATE, "VideoBitDepthNotSupported" to ConversionReason.VIDEO_FORMAT,
            "VideoFramerateNotSupported" to ConversionReason.VIDEO_FORMAT, "RefFramesNotSupported" to ConversionReason.VIDEO_FORMAT,
            "AnamorphicVideoNotSupported" to ConversionReason.VIDEO_FORMAT, "InterlacedVideoNotSupported" to ConversionReason.VIDEO_FORMAT,
            "AudioChannelsNotSupported" to ConversionReason.AUDIO_CHANNELS, "AudioProfileNotSupported" to ConversionReason.AUDIO_FORMAT,
            "AudioSampleRateNotSupported" to ConversionReason.AUDIO_FORMAT, "AudioBitDepthNotSupported" to ConversionReason.AUDIO_FORMAT,
            "ContainerBitrateExceedsLimit" to ConversionReason.BITRATE, "VideoBitrateNotSupported" to ConversionReason.BITRATE,
            "AudioBitrateNotSupported" to ConversionReason.BITRATE, "UnknownVideoStreamInfo" to ConversionReason.OTHER,
            "UnknownAudioStreamInfo" to ConversionReason.OTHER, "DirectPlayError" to ConversionReason.DIRECT_PLAY_FAILED,
            "VideoRangeTypeNotSupported" to ConversionReason.HDR, "VideoCodecTagNotSupported" to ConversionReason.VIDEO_FORMAT,
            "StreamCountExceedsLimit" to ConversionReason.OTHER, "VideoRotationNotSupported" to ConversionReason.VIDEO_FORMAT,
        )
        assertEquals("a reason in the reference has no mapping row", expected.keys, names.toSet())
        for (name in names) assertEquals(name, expected[name], conversionReason(name))
        assertEquals(ConversionReason.OTHER, conversionReason("SomethingNew"))
        assertEquals(ConversionReason.OTHER, conversionReason("videocodecnotsupported"))
    }

    @Test fun libraryKindsProgressAndOrder() {
        val libraries = AdminFixtures.array("virtual-folders.json").toLibraries()
        val byName = libraries.associateBy { it.name }
        assertEquals(LibraryKind.FILMS, byName.getValue("Films").kind)
        assertEquals(LibraryKind.TV, byName.getValue("TV").kind)
        assertEquals(LibraryKind.MUSIC, byName.getValue("Music").kind)
        assertEquals(LibraryKind.MUSIC, byName.getValue("Music videos").kind)
        assertEquals(LibraryKind.COLLECTIONS, byName.getValue("Collections").kind)
        assertEquals(LibraryKind.MIXED, byName.getValue("Everything").kind)
        assertEquals(LibraryKind.OTHER, byName.getValue("Home videos").kind)
        assertEquals(LibraryKind.OTHER, byName.getValue("Books").kind)
        assertEquals(LibraryKind.OTHER, byName.getValue("No type").kind)
        val tv = byName.getValue("TV")
        assertEquals("2b9f2c3e4d5a6b7c8d9e0f1a2b3c4d5e", tv.id)
        assertEquals(true to 42, tv.scanning to tv.progressPercent)
        assertEquals(false to null, byName.getValue("Films").let { it.scanning to it.progressPercent })
        assertFalse(byName.getValue("Music").scanning)
        assertTrue("progress below 100 means scanning even when the status says idle", byName.getValue("Everything").scanning)
        assertEquals(libraries.map { it.name }.sortedBy { it.lowercase() }, libraries.map { it.name })
    }

    @Test fun taskStatesOutcomesAndOrder() {
        val tasks = AdminFixtures.array("tasks.json").toTasks()
        val byKey = tasks.associateBy { it.key }
        assertFalse("hidden tasks are dropped", tasks.any { it.key == "HiddenTask" })
        val scan = byKey.getValue(FULL_SCAN_TASK_KEY)
        assertEquals(TaskState.RUNNING to 37, scan.state to scan.progressPercent)
        assertEquals(TaskRun(TaskOutcome.COMPLETED, Instant.parse("2026-11-14T03:04:00Z"), null), scan.lastRun)
        assertEquals("Scan Media Library", scan.name)
        assertEquals("Library", scan.category)
        val chapters = byKey.getValue("RefreshChapterImages")
        assertEquals(TaskRun(TaskOutcome.FAILED, Instant.parse("2026-11-15T02:10:00Z"), "ffmpeg exited with code 1"), chapters.lastRun)
        val cache = byKey.getValue("DeleteCacheFiles")
        assertEquals(TaskState.CANCELLING to null, cache.state to cache.progressPercent)
        assertNull("an error message is kept only for failed and aborted runs", cache.lastRun!!.errorMessage)
        assertNull(byKey.getValue("CleanActivityLog").lastRun)
        assertEquals(TaskRun(TaskOutcome.ABORTED, Instant.parse("2026-11-13T01:00:00Z"), "Server shut down"), byKey.getValue("OptimizeDatabaseTask").lastRun)
        assertEquals(TaskOutcome.CANCELLED, byKey.getValue("CancelledOne").lastRun!!.outcome)
        assertEquals(listOf("Library", "Library", "Maintenance", "Maintenance", "Maintenance", "Maintenance"), tasks.map { it.category })
        assertEquals(listOf("Extract Chapter Images", "Scan Media Library"), tasks.take(2).map { it.name })
    }

    @Test fun usersSortedWithYouFirst() {
        val users = AdminFixtures.array("users.json").toUsers(AdminFixtures.BEN)
        assertEquals(listOf("ben", "Anna", "guest", "kid"), users.map { it.name })
        val ben = users[0]
        assertTrue(ben.isYou && ben.isAdministrator && ben.canDownload && ben.allLibraries && ben.policyKnown)
        assertEquals(Instant.parse("2026-11-15T19:00:00Z"), ben.lastSeen)
        val kid = users.single { it.name == "kid" }
        assertEquals(AdminFixtures.KID, kid.id)
        assertFalse(kid.isYou || kid.isAdministrator || kid.canDownload || kid.allLibraries || kid.isDisabled)
        assertEquals(listOf("1a9f2c3e4d5a6b7c8d9e0f1a2b3c4d5e"), kid.libraryIds)
        assertEquals(Instant.parse("2026-11-15T18:55:00Z"), kid.lastSeen)
        assertTrue(users.single { it.name == "guest" }.isDisabled)
        assertNull(users.single { it.name == "guest" }.lastSeen)
        val anna = users.single { it.name == "Anna" }
        assertFalse("missing booleans are false", anna.isDisabled || anna.canDownload)
    }

    @Test fun severityMappingAndPage() {
        val page = AdminFixtures.decode<ActivityResultDto>("activity.json").toPage(startIndex = 0, limit = 50)
        val byId = page.entries.associateBy { it.id }
        assertEquals(listOf(120L, 119L, 118L, 117L, 116L, 115L, 114L, 113L, 112L), page.entries.map { it.id })
        assertEquals(Severity.INFORMATION, byId.getValue(120).severity)
        assertEquals(Severity.ERROR, byId.getValue(119).severity)
        assertEquals(Severity.WARNING, byId.getValue(117).severity)
        assertEquals(Severity.CRITICAL, byId.getValue(116).severity)
        for (id in listOf(115L, 114L, 113L, 112L)) assertEquals(Severity.INFORMATION, byId.getValue(id).severity)
        assertEquals("IP address: 192.168.1.20", byId.getValue(120).detail)
        assertNull(byId.getValue(119).detail)
        assertNull(byId.getValue(112).at)
        assertEquals(Instant.parse("2026-11-15T19:00:00Z"), byId.getValue(120).at)
        assertEquals(9, page.nextStartIndex)
        assertTrue("fewer than the limit came back", page.endReached)
        val full = AdminFixtures.decode<ActivityResultDto>("activity.json").toPage(startIndex = 111, limit = 9)
        assertEquals(120, full.nextStartIndex)
        assertTrue("TotalRecordCount reached", full.endReached)
        assertFalse(AdminFixtures.decode<ActivityResultDto>("activity.json").toPage(startIndex = 0, limit = 9).endReached)
    }

    @Test fun aDeviceIdOutsideTheStrictSetIsListedButNeverRemovable() {
        val json = """{"Items":[
            {"Id":"junk,${AdminFixtures.THIS_PHONE}","Name":"Junk","LastUserName":"kid"},
            {"Id":"odd|id","Name":"Odd","LastUserName":"kid"},
            {"Id":"tv-living-room-1","Name":"TV","LastUserName":"kid"},
            {"Id":"${AdminFixtures.THIS_PHONE}","Name":"Phone","LastUserName":"ben"}]}"""
        val devices = JsonBodies.JSON.decodeFromString(DeviceResultDto.serializer(), json).toDevices(AdminFixtures.THIS_PHONE).associateBy { it.name }
        assertFalse("a comma could name a second device", devices.getValue("Junk").removable)
        assertFalse("not this phone, but not removable either", devices.getValue("Junk").isThisDevice)
        assertFalse(devices.getValue("Odd").removable)
        assertTrue(devices.getValue("TV").removable)
        assertTrue(devices.getValue("Phone").isThisDevice)
    }

    /**
     * jellyfin-web's id is base64 of the user agent plus a timestamp, so a long user agent passes 256 characters. Such a device is
     * listed (the screen must not under-report what is signed in) but never removable, and its id is never one a removal accepts.
     */
    @Test fun aDeviceIdOverTheLengthCapIsListedButNeverRemovable() {
        val long = "QmFzZTY0".repeat(40) + "|1731700000000"
        val json = """{"Items":[
            {"Id":"$long","Name":"Long","LastUserName":"anna","DateLastActivity":"2026-11-15T18:00:00Z"},
            {"Id":"$long","Name":"Long","LastUserName":"bob","DateLastActivity":"2026-11-15T19:00:00Z"},
            {"Id":"${long}x","Name":"Other long","LastUserName":"kid"}]}"""
        val devices = JsonBodies.JSON.decodeFromString(DeviceResultDto.serializer(), json).toDevices(AdminFixtures.THIS_PHONE)
        assertEquals("one row per id, even a long one", listOf("Long", "Other long"), devices.map { it.name })
        assertEquals(listOf("anna", "bob"), devices[0].userNames)
        for (d in devices) {
            assertFalse(d.removable)
            assertFalse(d.isThisDevice)
            assertFalse("the listed id is not one a removal accepts", AdminLimits.REMOVABLE_DEVICE_ID.matches(d.id))
            assertTrue("bounded in memory", d.id.length <= 256)
        }
        assertTrue(devices[0].id != devices[1].id)
    }

    /** When the newer row of a merged device wins, its own name is kept (the older one only fills an empty name). */
    @Test fun aMergedDeviceTakesTheNewerRowsName() {
        val json = """{"Items":[
            {"Id":"tv-1","Name":"Old name","LastUserName":"anna","DateLastActivity":"2026-11-15T18:00:00Z"},
            {"Id":"tv-1","Name":"New name","LastUserName":"bob","DateLastActivity":"2026-11-15T19:00:00Z"},
            {"Id":"tv-2","Name":"Named","LastUserName":"anna","DateLastActivity":"2026-11-15T18:00:00Z"},
            {"Id":"tv-2","Name":"","LastUserName":"bob","DateLastActivity":"2026-11-15T19:00:00Z"}]}"""
        val devices = JsonBodies.JSON.decodeFromString(DeviceResultDto.serializer(), json).toDevices(AdminFixtures.THIS_PHONE).associateBy { it.id }
        assertEquals("New name", devices.getValue("tv-1").name)
        assertEquals("Named", devices.getValue("tv-2").name)
    }

    @Test fun devicesMergeByIdAndPreferTheCustomName() {
        val devices = AdminFixtures.decode<DeviceResultDto>("devices.json").toDevices(AdminFixtures.THIS_PHONE)
        assertEquals(listOf("this-phone-device", "tv-living-room-1", "web-ben-laptop", "tablet-kid"), devices.map { it.id })
        val phone = devices[0]
        assertTrue(phone.isThisDevice)
        assertEquals(listOf("ben", "kid"), phone.userNames)
        assertEquals("0.6.0" to Instant.parse("2026-11-15T18:58:00Z"), phone.appVersion to phone.lastSeen)
        assertEquals("Study laptop", devices.single { it.id == "web-ben-laptop" }.name)
        assertEquals("Kid's tablet", devices.single { it.id == "tablet-kid" }.name)
        assertEquals("Jellyfin Android TV" to "0.17.0", devices[1].appName to devices[1].appVersion)
        assertFalse(devices[1].isThisDevice)
    }

    @Test fun systemInfoAndMe() {
        assertEquals(ServerStatus("Test Jellyfin", "12.4.0", pendingRestart = true, shuttingDown = false),
            AdminFixtures.decode<SystemInfoDto>("system-info.json").toStatus())
        val me = JsonBodies.JSON.decodeFromString<UserMeDto>(AdminFixtures.array("users.json")[1].toString()).toMe()
        assertEquals(AdminMe(AdminFixtures.BEN, isAdministrator = true, isDisabled = false), me)
        val dashed = JsonBodies.JSON.decodeFromString<UserMeDto>(AdminFixtures.array("users.json")[0].toString()).toMe()
        assertEquals(AdminMe(AdminFixtures.KID, isAdministrator = false, isDisabled = false), dashed)
        assertEquals(AdminMe(null, false, false), UserMeDto(id = "nope").toMe())
    }
}
