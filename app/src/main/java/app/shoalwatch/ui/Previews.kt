package app.shoalwatch.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.ui.home.HomePlaceholderScreen
import app.shoalwatch.ui.home.HomeUiState
import app.shoalwatch.ui.onboarding.AddServerActions
import app.shoalwatch.ui.onboarding.AddServerScreen
import app.shoalwatch.ui.onboarding.AddServerStage
import app.shoalwatch.ui.onboarding.AddServerUiState
import app.shoalwatch.ui.onboarding.Check
import app.shoalwatch.ui.onboarding.FoundServer
import app.shoalwatch.ui.onboarding.QuickConnectUi
import app.shoalwatch.ui.onboarding.SignInActions
import app.shoalwatch.ui.onboarding.SignInMode
import app.shoalwatch.ui.onboarding.SignInScreen
import app.shoalwatch.ui.onboarding.SignInUiState
import app.shoalwatch.ui.servers.AccountItem
import app.shoalwatch.ui.servers.HeaderItem
import app.shoalwatch.ui.servers.PaneActions
import app.shoalwatch.ui.servers.ServerEditActions
import app.shoalwatch.ui.servers.ServerEditScreen
import app.shoalwatch.ui.servers.ServerEditUiState
import app.shoalwatch.ui.servers.ServerItem
import app.shoalwatch.ui.servers.ServersActions
import app.shoalwatch.ui.servers.ServersScreen
import app.shoalwatch.ui.servers.ServersUiState
import app.shoalwatch.ui.theme.ShoalwatchTheme
import java.time.Instant

/* Android Studio previews of the main state of each screen, with sample data only. */

private val addActions = AddServerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
private val signInActions = SignInActions({}, {}, {}, {}, {}, {})
private val serversActions = ServersActions({}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {})
private val paneActions = PaneActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
private val editActions = ServerEditActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, paneActions)
private val sampleCertificate = CertificateSummary(
    "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
    "01:02:03:04:05:06:07:08:09:0A:0B:0C:0D:0E:0F:10:11:12:13:14:15:16:17:18:19:1A:1B:1C:1D:1E:1F:20",
    "CN=den.example", "CN=den.example", Instant.parse("2030-01-01T00:00:00Z"),
)

@Composable
private fun Themed(content: @Composable () -> Unit) = ShoalwatchTheme(content = content)

@Preview(showBackground = true, name = "Welcome") @Composable
private fun WelcomePreview() = Themed { AddServerScreen(AddServerUiState(), addActions, onLeave = null) }

@Preview(showBackground = true, name = "Add server: error") @Composable
private fun AddServerErrorPreview() = Themed {
    AddServerScreen(AddServerUiState(address = "den", check = Check.Failed("No Jellyfin server was found at that address.")), addActions, onLeave = {})
}

@Preview(showBackground = true, name = "Plain HTTP warning") @Composable
private fun HttpWarningPreview() = Themed {
    AddServerScreen(AddServerUiState(check = Check.NeedsHttpConsent, addressRole = AddressRole.Home), addActions, onLeave = null)
}

@Preview(showBackground = true, name = "Certificate") @Composable
private fun CertificatePreview() = Themed {
    AddServerScreen(AddServerUiState(check = Check.NeedsCertificateTrust(sampleCertificate)), addActions, onLeave = null)
}

@Preview(showBackground = true, name = "Server found, untested version") @Composable
private fun FoundPreview() = Themed {
    val found = FoundServer("srv", "Den", "10.8.0", ServerInfo.Compatibility.TOO_OLD, firstIsHome = true)
    AddServerScreen(AddServerUiState(stage = AddServerStage.Found, found = found), addActions, onLeave = null)
}

@Preview(showBackground = true, name = "Quick Connect") @Composable
private fun QuickConnectPreview() = Themed {
    SignInScreen(SignInUiState(serverName = "Den", quickConnect = QuickConnectUi.Code("ABC123", 272)), TextFieldState(), signInActions)
}

@Preview(showBackground = true, name = "Password") @Composable
private fun PasswordPreview() = Themed {
    SignInScreen(SignInUiState(serverName = "Den", mode = SignInMode.Password), TextFieldState(), signInActions)
}

@Preview(showBackground = true, name = "Home") @Composable
private fun HomePreview() = Themed {
    HomePlaceholderScreen(
        HomeUiState(loading = false, serverId = "srv", serverName = "Den", userName = "ben", connection = ConnectionView(ConnectionView.Kind.HOME)),
        {}, {}, {},
    )
}

@Preview(showBackground = true, name = "Home: offline") @Composable
private fun HomeOfflinePreview() = Themed {
    HomePlaceholderScreen(
        HomeUiState(loading = false, serverId = "srv", serverName = "Den", userName = "ben", connection = Connection.Offline().toView()),
        {}, {}, {},
    )
}

@Preview(showBackground = true, name = "Servers") @Composable
private fun ServersPreview() = Themed {
    ServersScreen(
        ServersUiState(
            loading = false,
            servers = listOf(ServerItem("s1", "Den", "10.10.3", false, listOf(AccountItem("u1", "ben", true), AccountItem("u2", "amy", false)))),
        ),
        serversActions,
    )
}

@Preview(showBackground = true, name = "Connection settings") @Composable
private fun SettingsPreview() = Themed {
    ServerEditScreen(
        ServerEditUiState(
            loaded = true, name = "Den", homeAddress = "http://192.168.1.10:8096", allowCleartext = true,
            headers = listOf(HeaderItem("X-Proxy", "secret")), connection = ConnectionView(ConnectionView.Kind.HOME),
        ),
        editActions,
    )
}
