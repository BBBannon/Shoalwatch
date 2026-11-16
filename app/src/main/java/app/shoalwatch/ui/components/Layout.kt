package app.shoalwatch.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One corner radius for every card, thumbnail container and banner, so they read as one family. */
val CardShape = RoundedCornerShape(12.dp)

/** Side margin of every browsing screen. */
val Gutter = 16.dp

/** Widest a detail or series page gets: comfortable line length on a tablet. */
val ReadableWidth = 840.dp

/** The smallest touch target, which every button here meets. */
val TouchTarget = 48.dp

/** A Continue Watching or Next Up card: narrow enough on a phone that the next card shows beside it, wider on a tablet. */
val PhoneWideCardWidth = 220.dp
val TabletWideCardWidth = 320.dp

/** From this width a navigation rail replaces the bottom bar and rows and grids show more. */
val WideBreakpoint = 600.dp

@Composable
fun screenWidth(): Dp = LocalConfiguration.current.screenWidthDp.dp

@Composable
fun isWideScreen(): Boolean = screenWidth() >= WideBreakpoint

@Composable
fun wideCardWidth(): Dp = if (isWideScreen()) TabletWideCardWidth else PhoneWideCardWidth

/** Titles may wrap to more lines when the reader has asked for larger text, so nothing is cut to one word. */
@Composable
fun titleLines(normal: Int = 2): Int = if (LocalDensity.current.fontScale > 1.3f) normal + 2 else normal

/** A full-size column that scrolls when its content is taller than the space (large text, a landscape phone). Not for use inside a lazy list. */
@Composable
fun ScrollingColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()), content = content)

/** Centres its content and never lets it grow wider than [ReadableWidth]. */
@Composable
fun ReadableBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = ReadableWidth).fillMaxWidth()) { content() }
    }
}

/** The one short fade used for things that appear or disappear (the system's "remove animations" setting shortens it to nothing). */
val ShortFadeIn = fadeIn(tween(150))
val ShortFadeOut = fadeOut(tween(150))
