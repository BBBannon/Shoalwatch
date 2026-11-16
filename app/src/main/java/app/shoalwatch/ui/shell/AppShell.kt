package app.shoalwatch.ui.shell

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import app.shoalwatch.R
import app.shoalwatch.ui.components.DownloadIcon
import app.shoalwatch.ui.components.isWideScreen
import app.shoalwatch.ui.theme.ShoalwatchTheme

/** The three places the bottom bar (or the rail, on a wide screen) goes to. */
enum class Tab(@param:StringRes val label: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Filled.Home),
    SEARCH(R.string.nav_search, Icons.Filled.Search),
    DOWNLOADS(R.string.nav_downloads, DownloadIcon),
}

/**
 * The frame around Home, Search and Downloads: a bottom navigation bar on a phone, a navigation rail from 600dp wide, with the
 * snackbar above it. [content] draws its own top bar (it sits under the status bar) and is never hidden behind the bar. Items
 * keep a 48dp minimum height and say which tab is selected.
 */
@Composable
fun AppShell(selected: Tab, onSelect: (Tab) -> Unit, snackbar: SnackbarHostState, content: @Composable () -> Unit) {
    if (isWideScreen()) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = null) },
                        label = { NavLabel(stringResource(tab.label)) },
                    )
                }
            }
            Scaffold(
                Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                // The navigation bar's inset belongs to the Scaffold, so the snackbar sits above the bar as well as the content.
                contentWindowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
            ) { padding ->
                androidx.compose.foundation.layout.Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
            }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = null) },
                            label = { NavLabel(stringResource(tab.label)) },
                        )
                    }
                }
            },
        ) { padding ->
            // The bar's height is consumed, so the keyboard's inset (Search) is not counted twice.
            androidx.compose.foundation.layout.Box(Modifier.padding(padding).consumeWindowInsets(padding)) { content() }
        }
    }
}

/** A tab's name on one line, at a size that stops growing past 1.3 times: the icon and the spoken name carry the meaning at larger text. */
@Composable
private fun NavLabel(text: String) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(MaxNavLabelScale))) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val MaxNavLabelScale = 1.3f

@Preview(showBackground = true, name = "Shell")
@Composable
private fun ShellPreview() = ShoalwatchTheme {
    AppShell(Tab.HOME, {}, SnackbarHostState()) { Text("Home") }
}
