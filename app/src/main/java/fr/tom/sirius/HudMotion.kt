package fr.tom.sirius

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sirius 2.4.2: one timeline per direction, in milliseconds. Every visible property of the HUD is a pure
 * function of the entrance time (0 to 420) and of the exit time (0 to 260), read only in the draw phase.
 */
internal object HudTimeline {
    const val ENTER_MS = 420f
    const val EXIT_MS = 260f
    /** Smooth start (zero slope), long soft landing. */
    val Decelerate: Easing = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val Accelerate: Easing = CubicBezierEasing(.3f, 0f, 1f, 1f)
    private const val SPRING_DAMPING = .78f
    private const val SPRING_STIFFNESS = 340f
    const val PILL_RISE_DP = 28f
    const val PILL_FALL_DP = 24f
    const val PILL_BLUR_DP = 12f
    const val BUBBLE_RISE_DP = 12f
    const val BUBBLE_FALL_DP = 8f

    fun segment(t: Float, start: Float, end: Float, easing: Easing = LinearEasing): Float = when {
        t <= start -> 0f
        t >= end -> 1f
        else -> easing.transform((t - start) / (end - start))
    }

    /** Damped spring from 0 to 1, zero initial velocity (damping 0.78, stiffness 340, mass 1): 2 % overshoot near 300 ms. */
    fun spring(t: Float, start: Float = 30f, end: Float = ENTER_MS): Float {
        if (t <= start) return 0f
        if (t >= end) return 1f
        val s = (t - start) / 1000f
        val w0 = sqrt(SPRING_STIFFNESS)
        val decay = SPRING_DAMPING * w0
        val wd = w0 * sqrt(1f - SPRING_DAMPING * SPRING_DAMPING)
        return 1f - exp(-decay * s) * (cos(wd * s) + decay / wd * sin(wd * s))
    }

    fun veil(t: Float, u: Float): Float = segment(t, 0f, 300f, Decelerate) * (1f - segment(u, 60f, 260f))
    fun pillProgress(t: Float): Float = spring(t)
    fun pillAlpha(t: Float, u: Float): Float = segment(t, 30f, 210f) * (1f - segment(u, 120f, 260f))
    fun pillBlur(t: Float): Float = 1f - segment(t, 30f, 270f, Decelerate)
    fun pillFall(u: Float): Float = segment(u, 40f, 260f, Accelerate)
    fun halo(t: Float, u: Float): Float {
        val rise = when {
            t < 160f -> 0f
            t < 260f -> .55f * segment(t, 160f, 260f, Decelerate)
            else -> .55f - .37f * segment(t, 260f, 420f, FastOutSlowInEasing)
        }
        return rise * (1f - segment(u, 0f, 120f))
    }
    fun waves(t: Float, u: Float): Float = segment(t, 60f, 420f, Decelerate) * (1f - segment(u, 0f, 200f, Accelerate))
    /** Opacity of the screen border on the hidden pose: it is the first thing shown, before the veil and the pill. */
    const val BORDER_FIRST = .75f
    /** Sirius 2.8: already lit on the first frame, full after 160 ms, fully faded 20 ms before the exit ends. */
    fun border(t: Float, u: Float): Float =
        (BORDER_FIRST + (1f - BORDER_FIRST) * segment(t, 0f, 160f, Decelerate)) * (1f - segment(u, 0f, 240f, FastOutSlowInEasing))
    /**
     * Sirius 2.10 (Tom: « un truc qui part d'en bas et qui remonte jusqu'en haut, qui fait un petit cercle autour
     * de la caméra, clean »). Two coral heads leave the bottom center and climb both sides, tracing the border
     * behind them; at the top they draw the camera ring, then fade. The ribbons come in softly afterwards, no pop.
     * The time keeps running after the entrance (420 ms).
     */
    // Sirius 2.10.1 (Tom: « ça va trop vite ») : a calmer climb, the ring after it, the ribbons last.
    const val CLIMB_MS = 1100f
    const val ACCENT_MS = 1700f
    /** Share of the climb: 0 at the bottom center, 1 when both heads meet above the camera. */
    fun accentTravel(t: Float): Float = segment(t, 0f, CLIMB_MS, Decelerate)
    fun accentAlpha(t: Float): Float = 1f - segment(t, 1250f, ACCENT_MS, FastOutSlowInEasing)
    /** Share of the camera ring drawn, from its bottom point around both sides. */
    fun ringDraw(t: Float): Float = segment(t, 1000f, 1450f, Decelerate)
    /** The voice ribbons fade in after the opening instead of popping. */
    fun ribbonIn(t: Float): Float = segment(t, 1500f, 2100f, FastOutSlowInEasing)
    private fun bubbleStart(index: Int): Float = 90f + 30f * index.coerceIn(0, 5)
    fun bubbleProgress(t: Float, index: Int): Float = bubbleStart(index).let { segment(t, it, it + 180f, Decelerate) }
    fun bubbleAlpha(t: Float, u: Float, index: Int): Float =
        bubbleStart(index).let { segment(t, it, it + 180f) } * (1f - segment(u, 0f, 140f))
    fun bubbleFall(u: Float): Float = segment(u, 0f, 140f, Accelerate)
    fun listening(t: Float, u: Float): Float = segment(t, 180f, 360f) * (1f - segment(u, 0f, 140f))
}

/**
 * Opening and closing clock of the HUD. The assistant session owns one and resets it synchronously in
 * onShow and onHide, so the first frame of every opening is the hidden pose, never the previous one.
 */
@Stable
class HudMotionState {
    internal var onEntranceStarted: (() -> Unit)? = null
    internal var onEntranceFinished: (() -> Unit)? = null
    internal var onExitFinished: (() -> Unit)? = null
    internal var fallbackClock = false
    var motion by mutableStateOf(true)
        internal set
    var generation by mutableIntStateOf(0)
        private set
    var enterMs by mutableFloatStateOf(0f)
        internal set
    var exitMs by mutableFloatStateOf(0f)
        internal set
    var exiting by mutableStateOf(false)
        internal set

    /** Before the window becomes visible. Without animations the final pose is shown at once. */
    fun prepareShow(motion: Boolean) {
        this.motion = motion
        fallbackClock = false
        enterMs = if (motion) 0f else HudTimeline.ENTER_MS
        exitMs = 0f
        exiting = false
        generation++
    }

    /** The system hid the window (Home, app switch) or the exit ended: the next opening starts hidden. */
    fun resetHidden() {
        enterMs = 0f
        exitMs = 0f
        exiting = false
    }
}

/** Blur of the pill: recreated only when the radius moves by half a pixel, removed under half a pixel. */
internal class BlurCache {
    private var radius = -1f
    private var effect: RenderEffect? = null
    fun get(px: Float): RenderEffect? {
        if (px < .5f) { radius = -1f; effect = null; return null }
        if (effect == null || abs(px - radius) >= .5f) { radius = px; effect = BlurEffect(px, px, TileMode.Decal) }
        return effect
    }
}
