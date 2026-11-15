package app.shoalwatch.ui.admin

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.admin.ActivityEntry
import app.shoalwatch.data.admin.Severity
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.admin.AdminSamples.phoneDevice
import app.shoalwatch.ui.admin.AdminSamples.tvDevice
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.format.DateTimeFormatter

abstract class ActivityDevicesRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(Session(S1, U1), online = true), loading = false, updatedAt = AdminSamples.now)
    private var more = 0
    private val asked = mutableListOf<String>()
    private val activityActions = ActivityActions(onBack = {}, onRefresh = {}, onLoadMore = { more++ })
    private val deviceActions = DevicesActions(onBack = {}, onRefresh = {}, onRemove = { asked += "remove:$it" }, onConfirm = { asked += "confirm" }, onDismissConfirm = {})

    private val at = AdminSamples.now.minusSeconds(2 * 3600)
    private val warning = ActivityEntry(7, "kid failed to sign in", "IP address: 192.168.1.20", at, Severity.WARNING)
    private val info = ActivityEntry(6, "ben started playing Harbour Lights", null, at, Severity.INFORMATION)
    private val todayAt get() = text(R.string.admin_time_today, DateTimeFormatter.ofPattern("HH:mm").format(at.atZone(ZoneId.systemDefault())))

    private fun activity(state: ActivityUiState) = show { ActivityScreen(state, activityActions) }
    private fun devices(state: DevicesUiState) = show { DevicesScreen(state, deviceActions) }

    @Test fun eachRowIsReadAsOneSentence() {
        activity(ActivityUiState(open, listOf(warning, info)))
        assertDescribed(text(R.string.admin_activity_row_spoken, text(R.string.admin_severity_warning), "kid failed to sign in. IP address: 192.168.1.20", todayAt))
        assertDescribed(text(R.string.admin_activity_row_spoken, text(R.string.admin_severity_information), "ben started playing Harbour Lights", todayAt))
    }

    @Test fun showOlderEntries() {
        activity(ActivityUiState(open, listOf(warning, info)))
        scrollTo(hasText(text(R.string.admin_activity_more)))
        rule.onNode(hasText(text(R.string.admin_activity_more)) and hasClickAction()).assertHeightIsAtLeast(48.dp).assertIsEnabled().performClick()
        assertEquals(1, more)
    }

    @Test fun showOlderEntriesIsOffWhileLoadingOrOffline() {
        activity(ActivityUiState(open.copy(gate = AdminGate.Open(Session(S1, U1), online = false)), listOf(warning), loadingMore = false))
        scrollTo(hasText(text(R.string.admin_activity_more)))
        rule.onNode(hasText(text(R.string.admin_activity_more)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test fun theEndLines() {
        activity(ActivityUiState(open, listOf(warning), endReached = true))
        scrollTo(hasText(text(R.string.admin_activity_end)))
        assertShown(text(R.string.admin_activity_end))
        assertNotShown(text(R.string.admin_activity_more))
    }

    @Test fun theLimitLine() {
        activity(ActivityUiState(open, listOf(warning), limitReached = true))
        scrollTo(hasText(text(R.string.admin_activity_limit)))
        assertShown(text(R.string.admin_activity_limit))
        assertNotShown(text(R.string.admin_activity_more))
    }

    @Test fun anEmptyLog() {
        activity(ActivityUiState(open, emptyList(), endReached = true))
        assertShown(text(R.string.admin_activity_empty))
    }

    @Test fun deviceLines() {
        devices(DevicesUiState(open, listOf(tvDevice, phoneDevice)))
        assertShown("Living room TV")
        assertShown(text(R.string.admin_device_users, "kid, ben"))
        assertShown(text(R.string.admin_device_app, "Jellyfin Android TV", "0.17.0"))
        assertShown(text(R.string.admin_device_last_seen, text(R.string.admin_time_minutes, "5")))
        val remove = text(R.string.admin_device_remove_cd, "Living room TV")
        scrollTo(hasContentDescription(remove))
        rule.onNodeWithContentDescription(remove).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled().performClick()
        assertEquals("remove:${tvDevice.id}", asked.last())
    }

    @Test fun thisPhoneHasTheNoteAndNoRemove() {
        devices(DevicesUiState(open, listOf(phoneDevice)))
        assertShown(text(R.string.admin_this_phone))
        scrollTo(hasText(text(R.string.admin_device_this_phone_note)))
        assertShown(text(R.string.admin_device_this_phone_note))
        assertFalse(described(text(R.string.admin_device_remove_cd, "Pixel")))
    }

    @Test fun offlineSwitchesRemoveOff() {
        devices(DevicesUiState(open.copy(gate = AdminGate.Open(Session(S1, U1), online = false)), listOf(tvDevice)))
        val remove = text(R.string.admin_device_remove_cd, "Living room TV")
        scrollTo(hasContentDescription(remove))
        rule.onNodeWithContentDescription(remove).assertIsNotEnabled()
    }

    @Test fun noDevices() {
        devices(DevicesUiState(open, emptyList()))
        assertShown(text(R.string.admin_devices_empty))
    }

    @Test fun theRemoveConfirmation() {
        devices(DevicesUiState(open, listOf(tvDevice), DeviceConfirm(tvDevice.id, "Living room TV", "kid")))
        rule.onNode(isDialog()).assertExists()
        assertShown(text(R.string.admin_confirm_device_title, "Living room TV"))
        assertShown(text(R.string.admin_confirm_device_body, "kid"))
        rule.onNode(hasText(text(R.string.admin_confirm_device_action)) and hasClickAction() and hasAnyAncestor(isDialog())).assertIsEnabled().performClick()
        assertEquals("confirm", asked.last())
    }
}

@Config(qualifiers = PHONE) class ActivityDevicesPhoneRenderTest : ActivityDevicesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class ActivityDevicesPhoneLargeTextRenderTest : ActivityDevicesRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class ActivityDevicesTabletRenderTest : ActivityDevicesRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class ActivityDevicesTabletLargeTextRenderTest : ActivityDevicesRenderBase() { override val fontScale = 2f }
