package app.shoalwatch.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/* The player's 24dp icons that the core icon set does not have, from plain path data (as `BrowseIcons`). */

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

internal object PlayerIcons {
    val Pause: ImageVector by lazy { icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Rewind: ImageVector by lazy { icon("Rewind", "M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z") }
    val Forward: ImageVector by lazy { icon("Forward", "M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z") }
    val Previous: ImageVector by lazy { icon("Previous", "M6,6h2v12H6zM9.5,12l8.5,6V6z") }
    val Next: ImageVector by lazy { icon("Next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val Subtitles: ImageVector by lazy {
        icon("Subtitles", "M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4zM4,12h4v2H4V12zM14,18H4v-2h10V18zM20,18h-4v-2h4V18zM20,14H10v-2h10V14z")
    }
    val Speed: ImageVector by lazy {
        icon("Speed", "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1a10,10 0,0 0,-0.27 -10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49l-8.49,5.66a2,2 0,0 0,0 2.83z")
    }
    val Sleep: ImageVector by lazy { icon("Sleep", "M12.34,2.02C6.59,1.82 2,6.42 2,12c0,5.52 4.48,10 10,10c3.71,0 6.93,-2.02 8.66,-5.02C13.15,16.73 8.57,8.55 12.34,2.02z") }
    val TextStyle: ImageVector by lazy { icon("TextStyle", "M5,17v2h14v-2H5zM9.5,12.8h5l0.9,2.2h2.1L12.75,4h-1.5L6.5,15h2.1L9.5,12.8zM12,5.98L13.87,11h-3.74L12,5.98z") }
    val Quality: ImageVector by lazy {
        icon("Quality", "M19,4H5C3.89,4 3,4.9 3,6v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6C21,4.9 20.1,4 19,4zM11,15H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11V15zM18,14c0,0.55 -0.45,1 -1,1h-0.75v1.5h-1.5V15H14c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1V14zM14.5,13.5h2v-3h-2V13.5z")
    }
    val LockOpen: ImageVector by lazy {
        icon("LockOpen", "M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2s-2,0.9 -2,2S10.9,17 12,17zM18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1s3.1,1.39 3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10C20,8.9 19.1,8 18,8zM18,20H6V10h12V20z")
    }
}
