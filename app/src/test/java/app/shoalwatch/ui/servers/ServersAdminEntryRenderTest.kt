package app.shoalwatch.ui.servers

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.browse.PHONE
import app.shoalwatch.ui.browse.RenderBase
import app.shoalwatch.ui.browse.TABLET
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

abstract class ServersAdminEntryRenderBase : RenderBase() {
    private var admins = 0
    private val actions = ServersActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {}, onAdmin = { admins++ })
    private val state = ServersUiState(
        loading = false,
        servers = listOf(
            ServerItem("s1", "Den", "12.4.0", false, listOf(AccountItem("u1", "ben", isActive = true))),
            ServerItem("s2", "Cabin", "12.4.0", false, listOf(AccountItem("u3", "cat", isActive = false))),
        ),
    )

    @Test fun onlyTheAdministratorsServerHasTheButton() {
        show { ServersScreen(state.copy(adminServerId = "s1"), actions) }
        val button = rule.onNodeWithContentDescription(text(R.string.admin_entry_cd, "Den"))
        button.performScrollTo().assertHeightIsAtLeast(48.dp)
        assertEquals(false, described(text(R.string.admin_entry_cd, "Cabin")))
        button.performClick()
        assertEquals(1, admins)
    }

    @Test fun nobodyIsAdministrator() {
        show { ServersScreen(state, actions) }
        assertNotShown(text(R.string.admin_entry))
        assertEquals(false, describedStarting(text(R.string.admin_entry)))
    }
}

@Config(qualifiers = PHONE) class ServersAdminEntryPhoneRenderTest : ServersAdminEntryRenderBase() { override val fontScale = 1f }
@Config(qualifiers = PHONE) class ServersAdminEntryPhoneLargeTextRenderTest : ServersAdminEntryRenderBase() { override val fontScale = 2f }
@Config(qualifiers = TABLET) class ServersAdminEntryTabletRenderTest : ServersAdminEntryRenderBase() { override val fontScale = 1f }
@Config(qualifiers = TABLET) class ServersAdminEntryTabletLargeTextRenderTest : ServersAdminEntryRenderBase() { override val fontScale = 2f }
