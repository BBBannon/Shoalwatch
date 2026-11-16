package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R

/** Content is never wider than this, so phones and tablets both get a comfortable reading column. */
private val MaxContentWidth = 560.dp
private val PinnedMinHeight = 360.dp
private const val PinnedMaxFontScale = 1.5f

/**
 * The frame every screen shares: a top bar (back arrow when [onBack] is given), a centred scrolling column
 * and an optional [bottomBar] of actions that stays above the keyboard. Edge to edge: the scaffold applies the
 * system-bar insets and the keyboard inset is added here, so a button is never hidden. Scrolling content copes
 * with a 200% font scale.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    // An empty title is allowed for screens whose big heading is in the content.
                    if (title.isNotEmpty()) Text(title, Modifier.semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            // The action bar stays pinned above the keyboard by default. Only when the space is genuinely too small
            // (a large font, or under ~360dp of visible height) would it squeeze the form, so the actions then move to
            // the end of the scrolling content and the primary button is reachable by scrolling. `maxHeight` is already
            // net of the system bars and the keyboard (the insets are applied above), and an open keyboard alone never
            // unpins.
            val pinned = bottomBar != null && maxHeight >= PinnedMinHeight && LocalDensity.current.fontScale <= PinnedMaxFontScale
            Column(Modifier.widthIn(max = MaxContentWidth).fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    content()
                    if (bottomBar != null && !pinned) {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = bottomBar)
                    }
                }
                if (bottomBar != null && pinned) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = bottomBar,
                    )
                }
            }
        }
    }
}
