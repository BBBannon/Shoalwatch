package app.shoalwatch.ui.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import app.shoalwatch.R
import app.shoalwatch.data.playback.PlaybackState.Phase
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** What the player asks of the window. [playing]: auto-enter only while playing. Aspect clamped to 1:2.39 … 2.39:1. */
data class PipWish(val width: Int, val height: Int, val playing: Boolean, val sourceRect: Rect?)

/** The activity's picture-in-picture, for the player (`MainActivity`, through [ActivityPip]). */
interface PipHost {
    /** True while the activity is in a picture-in-picture window. */
    val inPip: StateFlow<Boolean>

    /**
     * null when the player is not shown (or a sheet, the screen lock or the lock cover is up, or playback failed): nothing enters
     * picture-in-picture. Whether leaving the app enters it by itself also needs the Settings switch (`PipPrefs.autoEnter`).
     */
    fun wish(w: PipWish?, onPlayPause: (() -> Unit)?)

    /** The button: enters now if allowed. False if the system refused. */
    fun enterNow(): Boolean
}

/** The host for the player; null in tests and previews, where the button is not shown. */
val LocalPipHost: ProvidableCompositionLocal<PipHost?> = staticCompositionLocalOf { null }

private val WIDEST = Rational(239, 100)
private val TALLEST = Rational(100, 239)
private val UNKNOWN = Rational(16, 9)

/** The video's aspect within what Android allows (1:2.39 to 2.39:1); 16:9 while the size is not known. */
internal fun pipAspect(width: Int, height: Int): Rational {
    if (width <= 0 || height <= 0) return UNKNOWN
    val ratio = Rational(width, height)
    return when {
        ratio > WIDEST -> WIDEST
        ratio < TALLEST -> TALLEST
        else -> ratio
    }
}

/**
 * The params: aspect, one RemoteAction (play or pause: an explicit, FLAG_IMMUTABLE broadcast to this package), seamless resize
 * off, source rect hint; on API 31+ `setAutoEnterEnabled([autoEnter])`, which is [PipWish.playing] unless the Settings switch is
 * off. Pure apart from the Android builders.
 */
internal fun pipParams(context: Context, wish: PipWish, autoEnter: Boolean = wish.playing): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(pipAspect(wish.width, wish.height))
        .setActions(listOf(playPauseAction(context, wish.playing)))
        .setSourceRectHint(wish.sourceRect)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        builder.setAutoEnterEnabled(autoEnter)
        builder.setSeamlessResizeEnabled(false) // video: a cross-fade, not a stretched frame
    }
    return builder.build()
}

/** API 31+: the params once there is no wish, so leaving the app no longer enters picture-in-picture by itself. */
internal fun noPipParams(): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(false)
    return builder.build()
}

private fun playPauseAction(context: Context, playing: Boolean): RemoteAction {
    // Explicit (this package only) and immutable, so no other app can redirect or fill it in (Global Constraints: components).
    val intent = Intent(PipActionReceiver.ACTION).setPackage(context.packageName)
    val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val label = context.getString(if (playing) R.string.pip_pause else R.string.pip_play)
    val icon = Icon.createWithResource(context, if (playing) R.drawable.ic_pip_pause else R.drawable.ic_pip_play)
    return RemoteAction(icon, label, label, pending)
}

/** The small window's play/pause button. Registered at run time as not exported, only while a wish exists; any other action is ignored. */
internal class PipActionReceiver(private val onPlayPause: () -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) onPlayPause()
    }

    companion object {
        const val ACTION = "app.shoalwatch.action.PIP_PLAY_PAUSE"
    }
}

/**
 * The player's wish, kept up to date while it is composed and withdrawn when it goes. [enabled]: false while the lock cover is up.
 * No wish while playback failed, a sheet is open or the screen lock is on. [aspect]: the video's, when known. The source rect
 * hint is the picture's place in the window (the player fills it, the picture centred at its aspect).
 */
@Composable
internal fun PipEffect(state: PlayerUiState, enabled: Boolean, onPlayPause: () -> Unit, aspect: Float? = null) {
    val host = LocalPipHost.current ?: return
    val latest by rememberUpdatedState(onPlayPause)
    val window = LocalWindowInfo.current.containerSize
    val p = state.playback
    val allowed = enabled && p.phase != Phase.FAILED && state.sheet == null && !state.locked
    val wish = if (!allowed) null else {
        val (w, h) = if (aspect != null && aspect > 0f) (aspect * ASPECT_SCALE).roundToInt() to ASPECT_SCALE else 0 to 0
        PipWish(w, h, p.phase == Phase.PLAYING || p.phase == Phase.BUFFERING, sourceRect(window.width, window.height, aspect))
    }
    DisposableEffect(host, wish) {
        host.wish(wish, if (wish != null) ({ latest() }) else null)
        onDispose { }
    }
    DisposableEffect(host) { onDispose { host.wish(null, null) } }
}

private const val ASPECT_SCALE = 1000

/** The picture's rectangle in a [width] x [height] window: centred at [aspect] (16:9 when unknown); null with no window size. */
internal fun sourceRect(width: Int, height: Int, aspect: Float?): Rect? {
    if (width <= 0 || height <= 0) return null
    val a = aspect?.takeIf { it > 0f } ?: (16f / 9f)
    val (w, h) = if (width.toFloat() / height > a) (height * a).roundToInt() to height else width to (width / a).roundToInt()
    val left = (width - w) / 2
    val top = (height - h) / 2
    return Rect(left, top, left + w, top + h)
}
