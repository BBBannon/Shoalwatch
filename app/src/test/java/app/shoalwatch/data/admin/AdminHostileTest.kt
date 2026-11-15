package app.shoalwatch.data.admin

import app.shoalwatch.data.admin.api.ActivityResultDto
import app.shoalwatch.data.admin.api.DeviceResultDto
import app.shoalwatch.data.admin.api.SystemInfoDto
import app.shoalwatch.data.admin.api.toDevices
import app.shoalwatch.data.admin.api.toLibraries
import app.shoalwatch.data.admin.api.toPage
import app.shoalwatch.data.admin.api.toSessions
import app.shoalwatch.data.admin.api.toStatus
import app.shoalwatch.data.admin.api.toTasks
import app.shoalwatch.data.admin.api.toUsers
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminHostileTest {
    private val sessions = AdminFixtures.array("hostile-sessions.json").toSessions(AdminFixtures.THIS_PHONE).associateBy { it.id }
    private val tasks = AdminFixtures.array("hostile-tasks.json").toTasks().associateBy { it.id }
    private val users = AdminFixtures.array("hostile-users.json").toUsers(AdminFixtures.BEN).associateBy { it.id }
    private val devices = AdminFixtures.decode<DeviceResultDto>("hostile-devices.json").toDevices(AdminFixtures.THIS_PHONE).associateBy { it.id }
    private val activity = AdminFixtures.decode<ActivityResultDto>("hostile-activity.json").toPage(startIndex = 0, limit = 50)

    @Test fun aBadElementCostsOnlyItself() {
        assertTrue("goodfirst" in sessions && "goodlast" in sessions)
        assertFalse("a number where a name should be", "numbername" in sessions)
        assertFalse("an object where a string should be", "objectname" in sessions)
        assertTrue("good1" in tasks)
        assertFalse("numbername" in tasks)
        assertTrue("11111111111111111111111111111111" in users)
        assertFalse("66666666666666666666666666666666" in users)
        assertTrue("goodone" in devices)
        assertFalse("numbername" in devices)
        assertEquals(listOf(16L, 15L, 14L, 10L).sorted(), activity.entries.map { it.id }.sorted())
    }

    @Test fun aFractionalIndexCostsOnlyWhatIsPlaying() {
        val fraction = sessions.getValue("fraction")
        assertNull(fraction.nowPlaying)
    }

    @Test fun badSessionIdsAreDropped() {
        assertFalse("../x" in sessions)
        assertFalse("a".repeat(65) in sessions)
        assertFalse("" in sessions)
    }

    @Test fun duplicateIdsKeepTheFirst() {
        assertEquals("first", sessions.getValue("dup1").userName)
        assertEquals("first", tasks.getValue("dup").name)
        assertEquals("first", users.getValue("22222222222222222222222222222222").name)
        assertEquals(1, AdminFixtures.array("hostile-tasks.json").toTasks().count { it.id == "dup" })
    }

    @Test fun aHundredAndFiftySessionsKeepAHundred() {
        val many = (1..150).map { n -> buildJsonObject { put("Id", "s$n"); put("UserName", "u$n") } as JsonElement }
        assertEquals(100, many.toSessions(AdminFixtures.THIS_PHONE).size)
    }

    @Test fun longTitlesAreCutAndHiddenCharactersRemoved() {
        val long = sessions.getValue("longtitle").nowPlaying!!
        assertEquals(200, long.title.codePointCount(0, long.title.length))
        assertEquals("evilname", sessions.getValue("bidi").userName)
        assertEquals("tv", sessions.getValue("bidi").deviceName)
        assertEquals("", sessions.getValue("blank").userName)
        assertEquals("admin", users.getValue("55555555555555555555555555555555").name)
    }

    @Test fun outOfRangeTimesAreNull() {
        val long = sessions.getValue("longtitle").nowPlaying!!
        assertNull("position -1", long.positionMs)
        assertNull("runtime of 100 h", long.runtimeMs)
        val past = sessions.getValue("pastend").nowPlaying!!
        assertEquals(600_000L, past.runtimeMs)
        assertNull("position past the runtime", past.positionMs)
        assertNull("index past 100,000", past.episode)
        assertNull("negative index", past.season)
        assertNull("an empty series name", past.seriesName)
        assertNull("play method names are exact", past.method)
    }

    @Test fun outOfRangeProgressIsNull() {
        assertNull(tasks.getValue("minus").progressPercent)
        assertNull(tasks.getValue("over").progressPercent)
        assertNull(tasks.getValue("nan").progressPercent)
        assertTrue("the task survives a bad progress", "nan" in tasks)
        assertNull("progress only while running", tasks.getValue("idleprogress").progressPercent)
        assertEquals("state names are exact", TaskState.IDLE, tasks.getValue("weirdstate").state)
        assertNull(tasks.getValue("badkey").key)
        assertNull("unknown outcome", tasks.getValue("badresult").lastRun)
        assertNull("a result that is a string", tasks.getValue("stringresult").lastRun)
        assertEquals(TaskRun(TaskOutcome.FAILED, null, null), tasks.getValue("emptyerror").lastRun)
        assertFalse("hidden" in tasks)
        val folders = listOf(
            buildJsonObject { put("ItemId", "1a9f2c3e4d5a6b7c8d9e0f1a2b3c4d5e"); put("Name", "a"); put("RefreshProgress", -5) },
            buildJsonObject { put("ItemId", "2a9f2c3e4d5a6b7c8d9e0f1a2b3c4d5e"); put("Name", "b"); put("RefreshProgress", 100.5) },
            buildJsonObject { put("ItemId", "3a9f2c3e4d5a6b7c8d9e0f1a2b3c4d5e"); put("Name", "c"); put("RefreshProgress", "NaN") },
            buildJsonObject { put("ItemId", "../x"); put("Name", "d") },
            JsonPrimitive("bare"),
        ).toLibraries()
        assertEquals(listOf("a", "b", "c"), folders.map { it.name })
        assertTrue(folders.all { it.progressPercent == null && !it.scanning })
    }

    @Test fun transcodeReasonsAreBounded() {
        val forty = sessions.getValue("reasons40").nowPlaying!!
        assertEquals(8, forty.reasons.size)
        assertEquals(forty.reasons.distinct(), forty.reasons)
        assertEquals(
            listOf(ConversionReason.FILE_TYPE, ConversionReason.VIDEO_FORMAT, ConversionReason.AUDIO_FORMAT, ConversionReason.SUBTITLES,
                ConversionReason.BITRATE, ConversionReason.AUDIO_CHANNELS, ConversionReason.OTHER, ConversionReason.DIRECT_PLAY_FAILED),
            forty.reasons,
        )
        assertEquals("only 32 elements are read", listOf(ConversionReason.VIDEO_FORMAT), sessions.getValue("reasons32").nowPlaying!!.reasons)
        val direct = sessions.getValue("notranscode").nowPlaying!!
        assertEquals(emptyList<ConversionReason>(), direct.reasons)
        assertNull(direct.videoDirect)
        assertFalse("DisplayMessage must be the exact string", sessions.getValue("badcommands").canMessage)
        assertTrue(sessions.getValue("badcommands").canControl)
    }

    @Test fun badDeviceIdsAreDropped() {
        for (bad in listOf("has space", "ctrl\u0007char", "x".repeat(300), "", "café")) assertFalse(bad, bad in devices)
        val shared = devices.getValue("shared")
        assertEquals(listOf("anna", "bob"), shared.userNames)
        assertEquals("New", shared.appName)
        assertEquals(10, devices.getValue("many").userNames.size)
        assertNull(devices.getValue("nodate").lastSeen)
        assertEquals("nodate", devices.values.last().id)
    }

    @Test fun usersWithoutAReadablePolicy() {
        assertFalse(users.getValue("33333333333333333333333333333333").policyKnown)
        assertFalse(users.getValue("44444444444444444444444444444444").policyKnown)
        assertFalse(users.getValue("44444444444444444444444444444444").isAdministrator)
        assertFalse("not-a-guid" in users)
        assertEquals(500, users.getValue("77777777777777777777777777777777").libraryIds.size)
    }

    @Test fun activityEntriesAndAnUnusableTotal() {
        val byId = activity.entries.associateBy { it.id }
        assertFalse(11L in byId || 12L in byId || 13L in byId || -1L in byId)
        assertEquals("entry 14", byId.getValue(14).text)
        assertEquals("detail", byId.getValue(14).detail)
        assertNull(byId.getValue(15).at)
        assertEquals(300, byId.getValue(16).text.length)
        assertEquals("newest first, undated last, then id", listOf(14L, 16L, 10L, 15L), activity.entries.map { it.id })
        assertEquals("every element counts, kept or not", 13, activity.nextStartIndex)
        assertTrue("fewer than the limit", activity.endReached)
        val full = AdminFixtures.decode<ActivityResultDto>("hostile-activity.json").toPage(startIndex = 0, limit = 13)
        assertFalse("a TotalRecordCount of -1 is unknown", full.endReached)
        val smaller = ActivityResultDto(items = List(16) { buildJsonObject { put("Id", it); put("Name", "n") } }, total = 3).toPage(100, 16)
        assertFalse("a total smaller than what has been seen is unknown", smaller.endReached)
    }

    @Test fun systemInfoValues() {
        val status = AdminFixtures.decode<SystemInfoDto>("hostile-system-info.json").toStatus()
        assertEquals(80, status.name.length)
        assertTrue(status.name.all { it == 'S' })
        assertEquals("", status.version)
        assertFalse(status.pendingRestart)
        assertTrue(status.shuttingDown)
    }

    @Test fun secretsAndAddressesNeverReachAModel() {
        val forbidden = listOf("MOCKDEVICETOKEN", "HOSTILETOKEN", "192.168.1.", "10.9.8.7", "10.1.2.3", "/config/log", "/hostile/", "/srv/media")
        val models: List<Any> = listOf(
            AdminFixtures.array("sessions.json").toSessions(AdminFixtures.THIS_PHONE),
            AdminFixtures.array("hostile-sessions.json").toSessions(AdminFixtures.THIS_PHONE),
            AdminFixtures.decode<DeviceResultDto>("devices.json").toDevices(AdminFixtures.THIS_PHONE),
            AdminFixtures.decode<DeviceResultDto>("hostile-devices.json").toDevices(AdminFixtures.THIS_PHONE),
            AdminFixtures.decode<SystemInfoDto>("system-info.json").toStatus(),
            AdminFixtures.decode<SystemInfoDto>("hostile-system-info.json").toStatus(),
            AdminFixtures.array("virtual-folders.json").toLibraries(),
            AdminFixtures.array("tasks.json").toTasks(),
        )
        val values = models.flatMap { allValues(it) } + models.map { it.toString() }
        for (value in values) for (bad in forbidden) assertFalse("a model holds a forbidden value", value.contains(bad))
        assertTrue("the walk reached the fields", values.any { it == "Living room TV" })
        assertFalse("a task's long error text is never read", values.any { it.contains("MediaBrowser.dll") })
        assertTrue(models.filterIsInstance<List<*>>().flatten().all { it.toString().endsWith("(<redacted>)") })
    }

    /** Every string reachable from [root] through data-class fields and lists (reflection, so a new field is covered too). */
    private fun allValues(root: Any?): List<String> = when (root) {
        null -> emptyList()
        is String -> listOf(root)
        is Collection<*> -> root.flatMap { allValues(it) }
        is Enum<*>, is Number, is Boolean, is java.time.Instant -> listOf(root.toString())
        else -> root.javaClass.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .flatMap { f -> f.isAccessible = true; allValues(f.get(root)) }
    }
}
