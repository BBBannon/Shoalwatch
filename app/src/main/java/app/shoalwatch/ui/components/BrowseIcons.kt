package app.shoalwatch.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import app.shoalwatch.data.library.LibraryKind

/*
 * A few simple 24dp icons the small core icon set does not have. Drawn from plain path data so no icon library is needed.
 */

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

/** A tray with an arrow going in: Downloads. */
val DownloadIcon: ImageVector by lazy { icon("Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z") }

/** A clapper board: a films library. */
val FilmsIcon: ImageVector by lazy {
    icon("Films", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4H18z")
}

/** A television: a TV library. */
val TvIcon: ImageVector by lazy {
    icon("Tv", "M21,3H3C1.9,3 1,3.9 1,5v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5C23,3.9 22.1,3 21,3zM21,17H3V5h18V17z")
}

/** A folder: collections and mixed libraries. */
val FolderIcon: ImageVector by lazy {
    icon("Folder", "M10,4H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8L10,4z")
}

fun LibraryKind.icon(): ImageVector = when (this) {
    LibraryKind.MOVIES -> FilmsIcon
    LibraryKind.TV -> TvIcon
    LibraryKind.BOX_SETS, LibraryKind.MIXED -> FolderIcon
}
