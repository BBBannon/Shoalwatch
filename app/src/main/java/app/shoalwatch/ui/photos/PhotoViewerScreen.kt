package app.shoalwatch.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.images.ArtworkLimits
import app.shoalwatch.data.images.photoArtwork
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.ArtworkPlaceholder
import app.shoalwatch.ui.components.LocalArtworkLoader
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.lists.ListsFrame
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.ReadProblem
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.rememberStrings
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision

/** What the viewer can ask for. The route wires these to the ViewModel and to navigation. */
class PhotoViewerActions(
    val onClose: () -> Unit,
    val onRetry: () -> Unit,
    val onOpenServers: () -> Unit = {},
)

/**
 * The full-screen photo viewer: a pager over the folder's photos on black, each fitted to the screen, pinch to zoom 1x to 4x and
 * double tap for 2x, a Close button and a "3 of 12" counter. Offline, gone or failed shows the usual words with Try again.
 * It opens no dialog or sheet, so there is no separate window to cover when the app locks.
 */
@Composable
fun PhotoViewerScreen(state: PhotoViewerState, actions: PhotoViewerActions) {
    val session = state.session
    if (session == null || state.phase != ListsPhase.READY || state.photos.isEmpty()) {
        ListsFrame(stringResource(R.string.photo_title), actions.onClose, remember { SnackbarHostState() }) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    when {
                        session == null -> SignedOutNotice(actions.onOpenServers)
                        state.phase == ListsPhase.LOADING -> Unit
                        else -> ReadProblem(if (state.phase == ListsPhase.READY) ListsPhase.GONE else state.phase, actions.onRetry)
                    }
                }
            }
        }
        return
    }
    Viewer(session, state.photos, state.startIndex, actions.onClose)
}

private val Scrim = Color(0x99000000)

@Composable
private fun Viewer(session: Session, photos: List<Item>, startIndex: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    val strings = rememberStrings()
    val zoomIn = stringResource(R.string.photo_zoom_in)
    val zoomOut = stringResource(R.string.photo_zoom_out)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].id }) { page ->
            val photo = photos[page]
            PhotoPage(session, photo, stringResource(R.string.photo_cd, page + 1, photos.size, photo.displayName(strings)), zoomIn, zoomOut)
        }
        Row(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
            IconButton(
                onClick = onClose, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = Scrim, contentColor = Color.White),
            ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.photo_close)) }
            Box(Modifier.weight(1f).sizeIn(minHeight = 48.dp), contentAlignment = Alignment.Center) {
                // Each page already says "Photo 3 of 12", so the visible counter is not read out a second time.
                Text(
                    stringResource(R.string.photo_counter, pager.currentPage + 1, photos.size), color = Color.White,
                    modifier = Modifier.clearAndSetSemantics { }.background(Scrim, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.sizeIn(minWidth = 48.dp))
        }
    }
}

/** One photo, fitted, with pinch and double-tap zoom. A swipe pages on while at 1x; zoomed, a drag pans instead. */
@Composable
private fun PhotoPage(session: Session, photo: Item, description: String, zoomIn: String, zoomOut: String) {
    val zoom = remember(photo.id) { PhotoZoom() }
    val loader = LocalArtworkLoader.current
    val context = LocalContext.current
    val ref = remember(photo, session) { photo.photoArtwork(session) }
    val toggleLabel = if (zoom.zoomed) zoomOut else zoomIn
    BoxWithConstraints(
        Modifier.fillMaxSize().clipToBounds()
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Image
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { zoom.toggle(); true })
            }
            .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
            .pointerInput(zoom) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.changes.count { it.pressed } > 1 || zoom.zoomed) {
                            val factor = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (factor != 1f || pan != Offset.Zero) {
                                zoom.transform(factor, pan, Size(size.width.toFloat(), size.height.toFloat()))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        // The server's own picture at screen size: the smallest of 720, 1280 and 1920 that covers the longer side.
        val longest = maxOf(constraints.maxWidth, constraints.maxHeight)
        val shown = Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom.scale
            scaleY = zoom.scale
            translationX = zoom.offset.x
            translationY = zoom.offset.y
        }
        if (ref == null || loader == null) {
            ArtworkPlaceholder(seed = photo.id, title = photo.name, modifier = shown)
        } else {
            val request = remember(ref, longest) {
                val bucket = ArtworkLimits.photoBucket(longest)
                ImageRequest.Builder(context).data(ref).size(bucket, bucket).precision(Precision.INEXACT).build()
            }
            Box(Modifier.fillMaxSize()) {
                ArtworkPlaceholder(seed = photo.id, title = photo.name, modifier = Modifier.fillMaxSize())
                AsyncImage(request, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Fit, modifier = shown)
            }
        }
    }
}
