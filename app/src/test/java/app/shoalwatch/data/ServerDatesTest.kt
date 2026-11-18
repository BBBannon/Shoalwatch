package app.shoalwatch.data

import app.shoalwatch.data.admin.AdminFixtures
import app.shoalwatch.data.admin.api.ActivityResultDto
import app.shoalwatch.data.admin.api.DeviceResultDto
import app.shoalwatch.data.admin.api.toDevices
import app.shoalwatch.data.admin.api.toPage
import app.shoalwatch.data.admin.api.toSessions
import app.shoalwatch.data.admin.api.toTasks
import app.shoalwatch.data.admin.api.toUsers
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.db.library.toEntity
import app.shoalwatch.data.library.api.toItemDto
import app.shoalwatch.data.library.api.toItemWithUserData
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Security review M8 F3: a date from the server outside 1800-2200 is no date at all. `Instant` holds years up to a billion either
 * way, which `toEpochMilli()` (the whole page's Room write) and `atZone()` (the admin screens' "ago") cannot take.
 */
class ServerDatesTest {
    private val absurd = listOf("+300000000-01-01T00:00:00Z", "-1000000000-01-01T00:00:00Z", "1799-12-31T23:59:59Z", "2200-01-01T00:00:01Z")
    private val anyDate = Regex(""""\d{4}-\d\d-\d\dT[0-9:.]+Z"""")

    private fun item(premiere: String, lastPlayed: String) = JsonBodies.JSON.parseToJsonElement(
        """{"Id":"ff000000000000000000000000000001","Name":"x","Type":"Movie","PremiereDate":"$premiere",
            "UserData":{"Played":false,"LastPlayedDate":"$lastPlayed"}}""",
    ).toItemDto()!!.toItemWithUserData()!!

    @Test fun anItemsPremiereAndLastPlayedOutsideTheRangeAreNullAndTheItemStillSaves() {
        for (raw in absurd) {
            val it = item(raw, raw)
            assertNull(raw, it.item.premiereDate)
            assertNull(raw, it.userData!!.lastPlayed)
            it.item.toEntity("srv-1", Instant.EPOCH) // no ArithmeticException for the page's write
            it.userData!!.toEntity("srv-1", "u-1", it.item.id, Instant.EPOCH)
        }
    }

    @Test fun datesInsideTheRangeAreKept() {
        val it = item("1800-01-01T00:00:00Z", "2026-11-15T19:00:00.1234567Z")
        assertEquals(Instant.parse("1800-01-01T00:00:00Z"), it.item.premiereDate)
        assertEquals(Instant.parse("2026-11-15T19:00:00.1234567Z"), it.userData!!.lastPlayed)
    }

    private fun admin(name: String, date: String) = AdminFixtures.text(name).replace(anyDate, "\"$date\"")

    @Test fun everyAdminDateOutsideTheRangeIsNull() {
        for (raw in absurd) {
            val sessions = JsonBodies.JSON.parseToJsonElement(admin("sessions.json", raw)).jsonArray.toSessions(AdminFixtures.THIS_PHONE)
            assertTrue(sessions.isNotEmpty())
            assertTrue(raw, sessions.all { it.lastActivity == null })
            val tasks = JsonBodies.JSON.parseToJsonElement(admin("tasks.json", raw)).jsonArray.toTasks()
            assertTrue(tasks.any { it.lastRun != null })
            assertTrue(raw, tasks.all { it.lastRun?.endedAt == null })
            val users = JsonBodies.JSON.parseToJsonElement(admin("users.json", raw)).jsonArray.toUsers(AdminFixtures.BEN)
            assertTrue(users.isNotEmpty())
            assertTrue(raw, users.all { it.lastSeen == null })
            val page = JsonBodies.JSON.decodeFromString(ActivityResultDto.serializer(), admin("activity.json", raw)).toPage(0, 50)
            assertTrue(page.entries.isNotEmpty())
            assertTrue(raw, page.entries.all { it.at == null })
            val devices = JsonBodies.JSON.decodeFromString(DeviceResultDto.serializer(), admin("devices.json", raw)).toDevices(AdminFixtures.THIS_PHONE)
            assertTrue(devices.isNotEmpty())
            assertTrue(raw, devices.all { it.lastSeen == null })
        }
    }
}
