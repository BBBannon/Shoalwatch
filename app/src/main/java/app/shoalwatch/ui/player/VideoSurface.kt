package app.shoalwatch.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.data.playback.engine.SubtitleSurface
import app.shoalwatch.data.playback.engine.VideoOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The picture and its subtitles. A plain `SurfaceView` (HDR output needs one, not a TextureView) sized to the video's aspect
 * ratio, handed to the engine's [video] output while it is on screen and taken back when the view goes; and the subtitle view
 * (`SubtitleSurface`, which keeps Media3 inside the engine package) over it, fed only by the controller's [cues] and styled from
 * [appearance] on every change. With the controls up, the subtitles move above them.
 */
@Composable
internal fun VideoSurface(video: VideoOutput?, cues: StateFlow<SubtitleFrame>?, appearance: SubtitleAppearance?, controlsUp: Boolean) {
    val context = LocalContext.current
    val surfaceView = remember { SurfaceView(context) }
    val subtitles = remember { SubtitleSurface(context) }
    val aspect by (video?.aspectRatio ?: NoAspect).collectAsState()
    DisposableEffect(video, surfaceView) {
        video?.attach(surfaceView.holder)
        onDispose { video?.detach(surfaceView.holder) }
    }
    LaunchedEffect(cues, subtitles) { cues?.collect(subtitles::show) }
    LaunchedEffect(appearance, subtitles) { appearance?.let(subtitles::style) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(aspect ?: DEFAULT_ASPECT)) {
            AndroidView(factory = { surfaceView }, modifier = Modifier.fillMaxSize())
            AndroidView(factory = { subtitles.view }, modifier = Modifier.fillMaxSize().padding(bottom = if (controlsUp) CONTROLS_CLEARANCE else 0.dp))
        }
    }
}

private val NoAspect = MutableStateFlow<Float?>(null)
private const val DEFAULT_ASPECT = 16f / 9f
/** Room for the seek bar and the times below the subtitles while the controls show. */
private val CONTROLS_CLEARANCE = 96.dp
