package app.shoalwatch.ui.admin

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
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
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.admin.AdminSamples.benUser
import app.shoalwatch.ui.admin.AdminSamples.guestUser
import app.shoalwatch.ui.admin.AdminSamples.kidUser
import app.shoalwatch.ui.admin.AdminSamples.unreadUser
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.annotation.Config

abstract class UsersRenderBase : RenderBase() {
    private val open = AdminFrame(gate = AdminGate.Open(Session(S1, U1), online = true), loading = false, updatedAt = AdminSamples.now)
    private val asked = mutableListOf<String>()
    private val actions = UsersActions(
        onBack = {}, onRefresh = {}, onDisable = { asked += "disable:$it" }, onEnable = { asked += "enable:$it" }, onConfirm = { asked += "confirm" }, onDismissConfirm = {},
    )
    private val rows = listOf(
        UserRow(benUser, emptyList()),
        UserRow(kidUser, listOf("TV", "Films")),
        UserRow(guestUser, emptyList()),
        UserRow(unreadUser, null),
        UserRow(kidUser.copy(id = "u-many", name = "many", libraryIds = listOf("a", "b", "c", "d", "e")), listOf("A", "B", "C", "D", "E")),
        UserRow(kidUser.copy(id = "u-count", name = "count"), null),
    )

    private fun users(frame: AdminFrame = open, confirm: UserConfirm? = null) = show { UsersScreen(UsersUiState(frame, rows, confirm), actions) }
    private fun see(label: String) {
        scrollTo(hasText(label))
        assertShown(label)
    }

    @Test fun everyLineAndBadge() {
        users()
        assertShown("ben")
        assertShown(text(R.string.admin_you))
        assertShown(text(R.string.admin_user_admin))
        assertShown(text(R.string.admin_user_last_seen, text(R.string.admin_time_just_now)))
        assertShown(text(R.string.admin_user_libraries_all))
        assertShown(text(R.string.admin_user_downloads_yes))
        see(text(R.string.admin_user_you_note))
        see(text(R.string.admin_user_downloads_no))
        see(text(R.string.admin_user_libraries_some, "TV, Films"))
        see(text(R.string.admin_user_disabled))
        see(text(R.string.admin_user_never_seen))
        see(text(R.string.admin_user_libraries_none))
        see(text(R.string.admin_user_policy_unknown))
        see(text(R.string.admin_user_libraries_some, text(R.string.admin_user_libraries_more, "A, B, C", "2")))
        see(text(R.string.admin_user_libraries_count, "2"))
        see(text(R.string.admin_users_note_dashboard))
    }

    @Test fun buttonsNameTheAccount() {
        users()
        val disable = text(R.string.admin_user_disable_cd, "kid")
        scrollTo(hasContentDescription(disable))
        rule.onNodeWithContentDescription(disable).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).assertIsEnabled().performClick()
        assertEquals("disable:${kidUser.id}", asked.last())
        val enable = text(R.string.admin_user_enable_cd, "guest")
        scrollTo(hasContentDescription(enable))
        rule.onNodeWithContentDescription(enable).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp).performClick()
        assertEquals("enable:${guestUser.id}", asked.last())
        assertFalse("never for yourself", described(text(R.string.admin_user_disable_cd, "ben")))
        scrollTo(hasText(text(R.string.admin_user_policy_unknown)))
        assertFalse("never for an account that couldn't be read", described(text(R.string.admin_user_disable_cd, "odd")))
    }

    @Test fun offlineSwitchesTheButtonsOff() {
        users(open.copy(gate = AdminGate.Open(Session(S1, U1), online = false)))
        val disable = text(R.string.admin_user_disable_cd, "kid")
        scrollTo(hasContentDescription(disable))
        rule.onNodeWithContentDescription(disable).assertIsNotEnabled()
    }

    @Test fun theDisableConfirmationSaysWhenItIsAnAdministrator() {
        users(confirm = UserConfirm("u-mum", "mum", disable = true, isAdministrator = true))
        rule.onNode(isDialog()).assertExists()
        assertShown(text(R.string.admin_confirm_disable_title, "mum"))
        assertShown(text(R.string.admin_confirm_disable_body, "mum"))
        assertShown(text(R.string.admin_confirm_disable_admin, "mum"))
        rule.onNode(hasText(text(R.string.admin_user_disable)) and hasClickAction() and hasAnyAncestorDialog()).performClick()
        assertEquals("confirm", asked.last())
    }

    @Test fun theEnableConfirmation() {
        users(confirm = UserConfirm(guestUser.id, "guest", disable = false, isAdministrator = false))
        assertShown(text(R.string.admin_confirm_enable_title, "guest"))
        assertShown(text(R.string.admin_confirm_enable_body, "guest"))
        assertNotShown(text(R.string.admin_confirm_disable_admin, "guest"))
        rule.onNode(hasText(text(R.string.admin_user_enable)) and hasClickAction() and hasAnyAncestorDialog()).assertIsEnabled()
    }

    private fun hasAnyAncestorDialog() = androidx.compose.ui.test.hasAnyAncestor(isDialog())
}

@Config(qualifiers = PHONE) class UsersPhoneRenderTest : UsersRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class UsersPhoneLargeTextRenderTest : UsersRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class UsersTabletRenderTest : UsersRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class UsersTabletLargeTextRenderTest : UsersRenderBase() { override val fontScale = 2f }
