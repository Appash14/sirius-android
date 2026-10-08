package fr.tom.sirius

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.RoundedCorner
import android.view.View
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real shape of the screen in the coordinates of the HUD canvas: corner radii in pixels (top left, top right,
 * bottom right, bottom left) and the bounds of the front camera cutout, or null without cutout.
 */
class ScreenOutline(val radii: FloatArray, val cutout: RectF?) {
    constructor(radius: Float, cutout: RectF? = null) : this(floatArrayOf(radius, radius, radius, radius), cutout)
}

/** WindowInsets.getRoundedCorner and DisplayCutout, with [fallbackRadius] when the system reports no corner. */
@Suppress("DEPRECATION")
internal fun readScreenOutline(view: View, fallbackRadius: Float): ScreenOutline {
    val insets = view.rootWindowInsets ?: return ScreenOutline(fallbackRadius)
    val origin = IntArray(2)
    view.getLocationInWindow(origin)
    val positions = intArrayOf(RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
        RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT)
    val reported = positions.map { position -> insets.getRoundedCorner(position)?.radius?.toFloat()?.takeIf { it > 0f } }
    val known = reported.filterNotNull().maxOrNull()
    val radii = FloatArray(4) { reported[it] ?: known ?: fallbackRadius }
    val cutout = insets.displayCutout?.let { display ->
        val rects = display.boundingRects.filter { !it.isEmpty }
        val bounds = RectF()
        // Sirius 2.9: the punch hole is the largest circle resting on the bottom of the exact path (Samsung's path
        // rises to the top edge). Several cutouts fall back to the top rectangle, whose bottom circle EdgeGlow takes.
        val path = display.cutoutPath
        if (rects.size <= 1 && path != null) {
            val circle = CameraHole.inscribed(path.approximate(.25f))
            if (circle != null) bounds.set(circle[0] - circle[2], circle[1] - circle[2], circle[0] + circle[2], circle[1] + circle[2])
            else path.computeBounds(bounds, true)
        }
        if (bounds.isEmpty) rects.minByOrNull { it.top }?.let { bounds.set(it) }
        if (bounds.isEmpty) null else bounds.apply { offset(-origin[0].toFloat(), -origin[1].toFloat()) }
    }
    return ScreenOutline(radii, cutout)
}

/**
 * Sirius 2.9: the camera ring is a true circle centered on the punch hole. Samsung (Tom's S25 Ultra) reports a
 * cutout that rises from the top edge down to the hole: the hole is the circle resting on the bottom of it.
 * Plain arithmetic on floats, checked on the JVM by EdgeGlowTest.
 */
/** Sirius 2.10: Tom's adjustment of the camera ring in dp (offset and radius), saved by Settings, read at layout. */
internal object RingTuning {
    @Volatile var dx = 0f
        private set
    @Volatile var dy = 0f
        private set
    @Volatile var dr = 0f
        private set
    @Volatile var version = 0
        private set
    fun set(x: Float, y: Float, r: Float) {
        dx = x.coerceIn(-LIMIT_DP, LIMIT_DP); dy = y.coerceIn(-LIMIT_DP, LIMIT_DP); dr = r.coerceIn(-LIMIT_DP, LIMIT_DP)
        version++
    }
    const val LIMIT_DP = 40f
}

/**
 * Sirius 2.11.4 (Tom) : l'anneau autour de la caméra ne s'affiche que quand Sirius utilise la caméra frontale,
 * pour le montrer. Sinon seule la ligne qui passe sous la caméra reste.
 */
internal object CameraRing {
    @Volatile var enabled = false
}

internal object CameraHole {
    /** Circle (center x, center y, radius) as wide as the cutout and resting on its bottom edge. */
    fun fromBounds(left: Float, top: Float, right: Float, bottom: Float, out: FloatArray = FloatArray(3)): FloatArray {
        val radius = (min(right - left, bottom - top) / 2f).coerceAtLeast(0f)
        out[0] = (left + right) / 2f; out[1] = bottom - radius; out[2] = radius
        return out
    }

    /** Bounds (left, top, right, bottom) of the ring, [gap] pixels around the hole: always a square. */
    fun ring(left: Float, top: Float, right: Float, bottom: Float, gap: Float, out: FloatArray = FloatArray(4)): FloatArray {
        val circle = fromBounds(left, top, right, bottom)
        val radius = circle[2] + gap
        out[0] = circle[0] - radius; out[1] = circle[1] - radius; out[2] = circle[0] + radius; out[3] = circle[1] + radius
        return out
    }

    /**
     * Largest circle resting on the bottom of an outline and fitting inside it, from Path.approximate (triplets of
     * fraction, x, y; the same fraction at another position is a jump to a new contour). Null without a usable
     * circle: the caller keeps the bounds of the path.
     */
    fun inscribed(points: FloatArray): FloatArray? {
        val n = points.size / 3
        if (n < 3) return null
        val edges = FloatArray(n * 4)
        var count = 0
        var first = 0
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (index in 0 until n) {
            val x = points[index * 3 + 1]; val y = points[index * 3 + 2]
            minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            val next = index + 1
            val jump = next == n || (points[next * 3] == points[index * 3] &&
                (points[next * 3 + 1] != x || points[next * 3 + 2] != y))
            // Each contour is closed on its first point.
            val to = if (jump) first else next
            edges[count * 4] = x; edges[count * 4 + 1] = y
            edges[count * 4 + 2] = points[to * 3 + 1]; edges[count * 4 + 3] = points[to * 3 + 2]
            count++
            if (jump) first = next
        }
        val size = min(maxX - minX, maxY - minY)
        if (size <= 0f) return null
        val out = FloatArray(3)
        var low = 0f; var high = size / 2f
        for (step in 0 until 28) {
            val middle = (low + high) / 2f
            if (fits(edges, count, maxY, middle, out)) low = middle else high = middle
        }
        if (low < size * .2f || !fits(edges, count, maxY, low, out)) return null
        return out
    }

    private fun fits(edges: FloatArray, count: Int, bottom: Float, radius: Float, out: FloatArray): Boolean {
        val cy = bottom - radius
        // Center on the middle of the outline at the height of the center.
        var left = Float.MAX_VALUE; var right = -Float.MAX_VALUE
        for (index in 0 until count) {
            val x1 = edges[index * 4]; val y1 = edges[index * 4 + 1]; val x2 = edges[index * 4 + 2]; val y2 = edges[index * 4 + 3]
            if (min(y1, y2) > cy || max(y1, y2) < cy) continue
            if (y1 == y2) { left = min(left, min(x1, x2)); right = max(right, max(x1, x2)); continue }
            val x = x1 + (cy - y1) / (y2 - y1) * (x2 - x1)
            left = min(left, x); right = max(right, x)
        }
        if (left > right) return false
        val cx = (left + right) / 2f
        val tolerance = max(.35f, radius * .01f)
        for (index in 0 until count) {
            if (distance(cx, cy, edges[index * 4], edges[index * 4 + 1], edges[index * 4 + 2], edges[index * 4 + 3]) <
                radius - tolerance) return false
        }
        out[0] = cx; out[1] = cy; out[2] = radius
        return true
    }

    private fun distance(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val length = dx * dx + dy * dy
        val t = if (length <= 0f) 0f else (((px - x1) * dx + (py - y1) * dy) / length).coerceIn(0f, 1f)
        val ex = x1 + t * dx - px; val ey = y1 + t * dy - py
        return sqrt(ex * ex + ey * ey)
    }
}

private const val GLOW_STEPS = 10
/** Air between the punch hole and its ring. */
private const val RING_GAP_DP = 3f
/** Ribbon level of the opening: lit at once, as if a voice was already there. */
/** Slices of an opening comma, from its thick head to its thin tail. */
private const val COMMA_DOTS = 36

/** Border colors per HudColor, in the enum order: neutral at rest, Tom, thinking, Sirius. */
internal object EdgePalette {
    val colors: Array<IntArray> = arrayOf(
        intArrayOf(0xFFD8DEE8.toInt(), 0xFF9AA8BA.toInt(), 0xFFF2F4F8.toInt()),
        intArrayOf(0xFF4FD1FF.toInt(), 0xFF2F7BFF.toInt(), 0xFFB8F3FF.toInt()),
        intArrayOf(0xFF8B5CFF.toInt(), 0xFF4F6BFF.toInt(), 0xFFE07BFF.toInt()),
        intArrayOf(0xFFFFA347.toInt(), 0xFFFF6A3D.toInt(), 0xFFFFE08A.toInt()))
    /** Sirius 2.9: vivid coral of the opening accent (glow, body, hot head). */
    // Sirius 2.10.1: an ice white light for the opening, no more coral (Tom).
    val accent: IntArray = intArrayOf(0xFF7FC4FF.toInt(), 0xFFCFEBFF.toInt(), 0xFFFFFFFF.toInt())
}

/**
 * Sirius 2.8 and 2.9: a light that hugs every edge of the screen, its real rounded corners and the front camera.
 * The opening shows a coral accent at once; the ribbons swell with the voice of Tom or Sirius.
 * Paths, shaders and paints are retained; a frame only moves shader matrices, alphas and short paths.
 */
internal class EdgeGlow(private val palette: Array<IntArray> = EdgePalette.colors) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val core = Path()
    private val bottomFade = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private var fadeTop = 0f
    private val waveA = Path()
    private val waveB = Path()
    private val comet = Path()
    private val traced = Path()
    private val ringHole = Path()
    private var tuned = -1
    private val measure = PathMeasure()
    private val matrix = Matrix()
    private val ring = RectF()
    private val ringBounds = FloatArray(4)
    private val position = FloatArray(2)
    private val tangent = FloatArray(2)
    private var ringRadius = 0f
    private var hasRing = false
    private var accentStart = 0f
    private var samples = FloatArray(0)
    private var count = 0
    private var perimeter = 0f
    private var waveK = 0f
    private var width = 0
    private var height = 0
    private var outline: ScreenOutline? = null
    private val blended = IntArray(3)
    private val shown = IntArray(3)
    private var glowShader: Shader? = null
    private var hotShader: Shader? = null
    private var ringShader: Shader? = null
    private var lastTime = -1f
    private var flow = 0f
    private var spin = 0f
    private var travel = 0f
    private var sonar = 0f
    private var lastEnter = -1f
    private var openStart = 0f
    private var ribbonVoice = 0f
    private var insetsKey: Any? = null
    private var readOutline: ScreenOutline? = null

    /** Re-read only when the window insets change (rotation, first attach). */
    fun outline(view: View, density: Float): ScreenOutline {
        val insets = view.rootWindowInsets
        val cached = readOutline
        if (cached != null && insets === insetsKey) return cached
        insetsKey = insets
        return readScreenOutline(view, density * 28f).also { readOutline = it }
    }

    private fun layout(w: Int, h: Int, shape: ScreenOutline, density: Float) {
        if (w == width && h == height && shape === outline && tuned == RingTuning.version) return
        width = w; height = h; outline = shape; tuned = RingTuning.version
        val limit = min(w, h) / 2f
        val r = FloatArray(4) { shape.radii.getOrElse(it) { 0f }.coerceIn(0f, limit) }
        fadeTop = h - max(density * 120f, max(r[2], r[3]) + density * 70f)
        bottomFade.shader = LinearGradient(0f, fadeTop, 0f, h - density * 24f,
            intArrayOf(Color.TRANSPARENT, Color.WHITE), null, Shader.TileMode.CLAMP)
        val hole = shape.cutout
        hasRing = hole != null && !hole.isEmpty && hole.width() < w * .45f && hole.height() < h * .2f
        if (hole != null && hasRing) {
            // Sirius 2.9: a true circle around the punch hole, never the tall cutout rectangle of Samsung.
            CameraHole.ring(hole.left, hole.top, hole.right, hole.bottom, density * RING_GAP_DP, ringBounds)
            // Sirius 2.10: Tom's fine adjustment, so the ring sits on the real hole to the millimeter.
            val ox = RingTuning.dx * density; val oy = RingTuning.dy * density
            val grow = max(RingTuning.dr * density, density * 2f - (ringBounds[2] - ringBounds[0]) / 2f)
            ring.set(ringBounds[0] + ox - grow, ringBounds[1] + oy - grow, ringBounds[2] + ox + grow, ringBounds[3] + oy + grow)
            ringRadius = ring.width() / 2f
            ringHole.rewind()
            ringHole.addCircle(ring.centerX(), ring.centerY(), ringRadius + density * 3f, Path.Direction.CW)
            // Remove the corridor above the ring as well: even the diffuse glow must follow the dip below it.
            ringHole.addRect(ring.left - density * 3f, 0f, ring.right + density * 3f, ring.centerY(), Path.Direction.CW)
        }
        // The bright line runs just inside the glass, from the top center, clockwise.
        val inset = density * 1.5f
        val left = inset; val top = inset; val right = w - inset; val bottom = h - inset
        val c = FloatArray(4) { (r[it] - inset).coerceAtLeast(0f) }
        core.rewind()
        // Sirius 2.10.1 (Tom's drawing): the top line dips and passes under the camera ring, then goes back up.
        val dip = if (hasRing) ringRadius + density * 4f else 0f
        val dipX = if (hasRing) ring.centerX() else w / 2f
        val dipY = if (hasRing) ring.centerY() else top
        if (hasRing && dipY + dip > top + density * 2f) {
            // Sirius 2.11.4 (Tom : « ultra smooth, pas d'angle net ») : épaules longues et douces.
            val bend = dip * 2.4f
            core.moveTo(dipX, dipY + dip)
            core.arcTo(dipX - dip, dipY - dip, dipX + dip, dipY + dip, 90f, -90f, false)
            core.cubicTo(dipX + dip, top + .2f * (dipY - top), dipX + dip + .45f * bend, top, dipX + dip + bend, top)
        } else core.moveTo(w / 2f, top)
        core.lineTo(right - c[1], top)
        if (c[1] > 0f) core.arcTo(right - 2f * c[1], top, right, top + 2f * c[1], -90f, 90f, false)
        core.lineTo(right, bottom - c[2])
        if (c[2] > 0f) core.arcTo(right - 2f * c[2], bottom - 2f * c[2], right, bottom, 0f, 90f, false)
        core.lineTo(left + c[3], bottom)
        if (c[3] > 0f) core.arcTo(left, bottom - 2f * c[3], left + 2f * c[3], bottom, 90f, 90f, false)
        core.lineTo(left, top + c[0])
        if (c[0] > 0f) core.arcTo(left, top, left + 2f * c[0], top + 2f * c[0], 180f, 90f, false)
        if (hasRing && dipY + dip > top + density * 2f) {
            core.lineTo(dipX - dip - dip * 2.4f, top)
            core.cubicTo(dipX - dip - .45f * dip * 2.4f, top, dipX - dip, top + .2f * (dipY - top), dipX - dip, dipY)
            core.arcTo(dipX - dip, dipY - dip, dipX + dip, dipY + dip, 180f, -90f, false)
        }
        core.close()
        measure.setPath(core, true)
        perimeter = measure.length.coerceAtLeast(1f)
        count = max(8, (perimeter / (density * 5f)).roundToInt())
        samples = FloatArray(count * 4)
        for (index in 0 until count) {
            measure.getPosTan(perimeter * index / count, position, tangent)
            samples[index * 4] = position[0]; samples[index * 4 + 1] = position[1]
            // Inward normal of a clockwise outline with y pointing down.
            samples[index * 4 + 2] = -tangent[1]; samples[index * 4 + 3] = tangent[0]
        }
        // Sirius 2.10: the opening heads leave the bottom center (the line starts on the center line, clockwise).
        accentStart = perimeter / 2f
        // A whole number of wavelengths: the waves close without a seam under the camera.
        waveK = 2f * PI.toFloat() * max(3, (perimeter / (density * 150f)).roundToInt()) / perimeter
        shown.fill(0)
    }

    private fun alpha(color: Int, amount: Float): Int = (color and 0x00FFFFFF) or ((amount * 255f).roundToInt() shl 24)

    private fun shaders(weights: FloatArray) {
        var total = 0f
        for (weight in weights) total += weight.coerceAtLeast(0f)
        for (slot in 0..2) {
            var red = 0f; var green = 0f; var blue = 0f
            for (index in palette.indices) {
                val share = if (total > 0f) weights.getOrElse(index) { 0f }.coerceAtLeast(0f) / total
                    else if (index == 0) 1f else 0f
                if (share <= 0f) continue
                val color = palette[index][slot]
                red += share * Color.red(color); green += share * Color.green(color); blue += share * Color.blue(color)
            }
            blended[slot] = Color.rgb(red.roundToInt().coerceIn(0, 255), green.roundToInt().coerceIn(0, 255),
                blue.roundToInt().coerceIn(0, 255))
        }
        if (glowShader != null && blended.contentEquals(shown)) return
        blended.copyInto(shown)
        val (c0, c1, c2) = Triple(shown[0], shown[1], shown[2])
        val cx = width / 2f; val cy = height / 2f
        // Bright and dim lobes: turning the gradient makes the light run around the screen.
        glowShader = SweepGradient(cx, cy, intArrayOf(alpha(c0, 1f), alpha(c1, .55f), alpha(c2, 1f), alpha(c1, .5f),
            alpha(c0, .9f), alpha(c2, .6f), alpha(c0, 1f)), null)
        hotShader = SweepGradient(cx, cy, intArrayOf(alpha(c2, 1f), alpha(c0, .85f), alpha(c1, .7f), alpha(c2, 1f),
            alpha(c1, .75f), alpha(c0, .9f), alpha(c2, 1f)), null)
        ringShader = if (hasRing) SweepGradient(ring.centerX(), ring.centerY(), intArrayOf(alpha(c1, 0f), alpha(c1, 0f),
            alpha(c0, .7f), alpha(c2, 1f), alpha(c1, 0f)), floatArrayOf(0f, .3f, .78f, .97f, 1f)) else null
    }

    private fun layer(canvas: Canvas, path: Path, shader: Shader?, strokeWidth: Float, amount: Float) {
        if (amount <= .004f || strokeWidth <= 0f) return
        stroke.shader = shader
        stroke.strokeWidth = strokeWidth
        stroke.alpha = (amount.coerceIn(0f, 1f) * 255f).roundToInt()
        canvas.drawPath(path, stroke)
    }

    /** A solid color stroke: the color first, then the opacity of this layer. */
    private fun tint(canvas: Canvas, path: Path, color: Int, strokeWidth: Float, amount: Float) {
        stroke.color = color
        layer(canvas, path, null, strokeWidth, amount)
    }

    /** Feathered light, with twelve faint nested passes instead of an opaque ribbon edge. */
    private fun light(canvas: Canvas, path: Path, shader: Shader?, width: Float, amount: Float) {
        for (index in 12 downTo 1) {
            val share = (25f - 2f * index) / 144f
            layer(canvas, path, shader, width * index / 12f, amount * share)
        }
    }

    private fun drawRing(canvas: Canvas, extra: Float, strokeWidth: Float, amount: Float) {
        if (amount <= .004f) return
        stroke.strokeWidth = strokeWidth
        stroke.alpha = (amount.coerceIn(0f, 1f) * 255f).roundToInt()
        canvas.drawCircle(ring.centerX(), ring.centerY(), ringRadius + extra, stroke)
    }

    private fun wave(path: Path, swell: Float, direction: Float) {
        path.rewind()
        for (index in 0 until count) {
            val s = perimeter * index / count
            val value = .5f + .5f * (.65f * sin(waveK * s - direction * travel) +
                .35f * sin(2f * waveK * s + direction * 2f * travel + 1.1f))
            val shift = swell * value
            val x = samples[index * 4] + samples[index * 4 + 2] * shift
            val y = samples[index * 4 + 1] + samples[index * 4 + 3] * shift
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

    /** Adds to [comet] the piece of the bright line between two arc lengths, across its start if needed. */
    private fun piece(from: Float, to: Float) {
        if (to <= from) return
        var start = from % perimeter
        if (start < 0f) start += perimeter
        val end = start + (to - from)
        if (end <= perimeter) measure.getSegment(start, end, comet, true)
        else {
            measure.getSegment(start, perimeter, comet, true)
            measure.getSegment(0f, end - perimeter, comet, true)
        }
    }

    /**
     * Sirius 2.10.1: two heads of light leave the bottom center and climb both sides to meet above the camera.
     * Each is a soft bloom with a tail of overlapping dots that thin and fade: no sliced strokes, no white bars.
     */
    private fun commas(canvas: Canvas, openMs: Float, amount: Float, density: Float, tom: Float) {
        // Keep the same route and timing. In TOM the heads emerge softly from the sun at the bottom center.
        val emergence = (HudTimeline.accentTravel(openMs) / .12f).coerceIn(0f, 1f)
        val fade = HudTimeline.accentAlpha(openMs) * amount * (1f - tom * .65f * (1f - emergence))
        if (fade <= .004f) return
        val head = HudTimeline.accentTravel(openMs) * perimeter / 2f
        val tail = min(head, perimeter * .06f)
        fill.shader = null
        for (step in COMMA_DOTS downTo 1) {
            val share = step / COMMA_DOTS.toFloat()
            fill.color = EdgePalette.accent[1]
            fill.alpha = (fade * .55f * (1f - share).pow(1.6f) * 255f).roundToInt()
            if (fill.alpha == 0) continue
            val radius = density * (3.2f - 2.2f * share)
            dot(canvas, accentStart + head - tail * share, radius)
            dot(canvas, accentStart - head + tail * share, radius)
        }
        for ((radius, alpha, color) in listOf(Triple(16f, .1f, 0), Triple(9f, .22f, 0), Triple(5f, .6f, 1), Triple(2.6f, 1f, 2))) {
            fill.color = EdgePalette.accent[color]
            fill.alpha = (fade * alpha * 255f).roundToInt()
            dot(canvas, accentStart + head, density * radius)
            dot(canvas, accentStart - head, density * radius)
        }
    }

    private fun dot(canvas: Canvas, at: Float, radius: Float) {
        measure.getPosTan(((at % perimeter) + perimeter) % perimeter, position, tangent)
        canvas.drawCircle(position[0], position[1], radius, fill)
    }

    /**
     * [level] is the smoothed HUD level (.08 at rest, about .26 thinking, up to 1 with a loud voice), [weights]
     * the crossfade between the HudColor states, [amount] the entrance and exit opacity.
     */
    fun draw(canvas: Canvas, w: Int, h: Int, shape: ScreenOutline, time: Float, level: Float, weights: FloatArray,
        amount: Float, motion: Boolean, density: Float, enterMs: Float, softContours: Boolean = false, omitBottom: Boolean = false) {
        if (w <= 0 || h <= 0) return
        val elapsed = if (lastTime < 0f || time < lastTime) 0f else time - lastTime
        val dt = min(elapsed, .1f)
        lastTime = time
        // Opening clock in the time base, whatever the number of draws: the entrance runs at real speed, so while
        // it runs the opening started enterMs ago; afterwards the clock keeps running on [time]. prepareShow puts
        // the entrance back to 0 at each opening; a HUD first drawn after its entrance (rotation) skips the accent.
        if (lastEnter < 0f || enterMs < lastEnter) {
            ribbonVoice = 0f
            if (enterMs >= HudTimeline.ENTER_MS) openStart = time - 2f * HudTimeline.ACCENT_MS / 1000f
        }
        if (enterMs < HudTimeline.ENTER_MS) openStart = time - enterMs / 1000f
        lastEnter = enterMs
        val openMs = ((time - openStart) * 1000f).coerceAtLeast(enterMs)
        if (amount <= .004f) return
        layout(w, h, shape, density)
        shaders(weights)
        val glow = glowShader ?: return
        val hot = hotShader ?: return
        var total = 0f
        for (weight in weights) total += weight.coerceAtLeast(0f)
        if (total <= 0f) total = 1f
        val rest = weights.getOrElse(HudColor.WAIT.ordinal) { 0f }.coerceAtLeast(0f) / total
        val thinking = weights.getOrElse(HudColor.THINKING.ordinal) { 0f }.coerceAtLeast(0f) / total
        val tom = weights.getOrElse(HudColor.TOM.ordinal) { 0f }.coerceAtLeast(0f) / total
        val voice = (1f - rest - thinking).coerceIn(0f, 1f)
        // 0 at rest, about .2 while thinking, up to 1 when the voice is loud. Frozen without animations.
        val amp = if (motion) ((level - .08f) / .92f).coerceIn(0f, 1f) else .12f * (1f - rest)
        val breath = if (motion) thinking * .18f * (.5f + .5f * sin(time * 2.1f)) else 0f
        val energy = (amp + breath).coerceIn(0f, 1f)
        // Sirius 2.10: the border is traced by the climbing heads, the ring drawn at the top, then the ribbons.
        val reveal = if (motion) HudTimeline.accentTravel(openMs) else 1f
        val ringShare = if (motion) HudTimeline.ringDraw(openMs) else 1f
        val ribbonsIn = if (motion) HudTimeline.ribbonIn(openMs) else 1f
        val opening = if (motion) 1f - ribbonsIn else 0f
        if (motion) {
            // Degrees and radians per second: almost still at rest, slow while thinking, driven by the voice.
            flow = (flow + dt * (6f + 26f * thinking + voice * (24f + 150f * amp) + 90f * opening)) % 360f
            spin = (spin + dt * (40f + 100f * thinking + voice * (80f + 260f * amp) + 320f * opening)) % 360f
            travel = (travel + dt * (.3f + .5f * thinking + voice * (1f + 4.5f * amp) + 3.5f * opening)) % (2f * PI.toFloat())
            sonar = (sonar + dt * (.7f + .7f * amp)) % 1f
        }
        val a = amount.coerceIn(0f, 1f)
        // Sirius 2.10.1 (Tom: « c'est le passage qui dessine les lignes »): nothing exists before the heads pass.
        if (reveal < 1f) {
            comet.rewind()
            if (reveal > 0f) piece(accentStart - reveal * perimeter / 2f, accentStart + reveal * perimeter / 2f)
            traced.set(comet)
        }
        // Sirius 2.10.1 (Tom's screenshot): the lines and the ribbons pass under the camera ring.
        val underRing = hasRing && ringShader != null
        // Mask only the border, never the content, camera ring or opening heads. All its layers fade together.
        // Sirius 2.11.4 (Tom) : jamais de ligne en bas, la bordure s'arrête sur les bords, qu'il parle ou non.
        val bottomAmount = 1f
        val fadeBottom = bottomAmount > .004f
        if (fadeBottom) canvas.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        if (underRing) { canvas.save(); canvas.clipOutPath(ringHole) }
        val corePath = if (reveal >= 1f) core else traced
        val lightIn = a
        val cx = w / 2f; val cy = h / 2f
        matrix.setRotate(flow, cx, cy); glow.setLocalMatrix(matrix)
        matrix.setRotate(40f - flow * 1.6f, cx, cy); hot.setLocalMatrix(matrix)
        // Soft light inside the glass: strokes centered on the edge (half off screen), each wider and fainter.
        // Their sum follows a quadratic falloff that reaches zero at [depth]: no visible step, no inner frame.
        val depth = density * (10f + 30f * energy)
        val strength = lightIn * (.42f + .3f * energy)
        for (index in GLOW_STEPS downTo 1) {
            val share = (2f * (GLOW_STEPS - index) + 1f) / (GLOW_STEPS * GLOW_STEPS)
            layer(canvas, corePath, if (index > GLOW_STEPS / 2) glow else hot, 2f * depth * index / GLOW_STEPS, strength * share)
        }
        if (softContours) light(canvas, corePath, hot, density * (18f + 14f * energy), lightIn * (.3f + .2f * energy))
        else layer(canvas, corePath, hot, density * (5f + 4f * energy), lightIn * (.35f + .2f * energy))
        // Sirius 2.9: the ribbons carry the voice of Tom or Sirius. Fast attack, slower release: a loud syllable
        // swells without flicker. Sirius 2.10: 25 % less swell than 2.9 (Tom), faded in after the opening.
        val target = if (motion) (amp.pow(.75f) + breath).coerceIn(0f, 1f) else 0f
        ribbonVoice += (target - ribbonVoice) * (1f - exp(-elapsed / (if (target > ribbonVoice) .07f else .22f)))
        val ribbon = ribbonVoice.coerceIn(0f, 1f)
        val lit = (ribbon / .12f).coerceIn(0f, 1f) * ribbonsIn
        if (motion && lit > .004f) {
            val swell = density * (.9f + 18f * ribbon)
            wave(waveA, swell, 1f)
            wave(waveB, swell * .7f, -1f)
            light(canvas, waveA, glow, density * (22f + 16f * ribbon), a * lit * (.15f + .2f * ribbon))
            light(canvas, waveB, glow, density * (16f + 12f * ribbon), a * lit * (.12f + .16f * ribbon))
            light(canvas, waveA, hot, density * (10f + 8f * ribbon), a * lit * (.1f + .12f * ribbon))
            layer(canvas, waveA, hot, density * .8f, a * lit * .035f)
        }
        if (reveal > 0f) {
            if (softContours) light(canvas, corePath, hot, density * (12f + 8f * energy), a * (.4f + .1f * energy))
            else layer(canvas, corePath, hot, density * (1.5f + 1.3f * energy), a * (.8f + .2f * energy))
        }
        if (underRing) canvas.restore()
        if (fadeBottom) {
            bottomFade.alpha = (bottomAmount.coerceIn(0f, 1f) * 255f).roundToInt()
            canvas.drawRect(0f, fadeTop, w.toFloat(), h.toFloat(), bottomFade)
            canvas.restore()
        }
        val ringGradient = ringShader
        if (CameraRing.enabled && hasRing && ringGradient != null && ringShare < 1f) {
            // The heads reach the top and draw the ring from its bottom point, around both sides.
            if (ringShare > 0f) {
                stroke.shader = null
                stroke.style = Paint.Style.STROKE
                stroke.color = EdgePalette.accent[0]
                stroke.strokeWidth = density * 7f
                stroke.alpha = (a * .3f * 255f).roundToInt()
                canvas.drawArc(ring, 90f, 180f * ringShare, false, stroke)
                canvas.drawArc(ring, 90f, -180f * ringShare, false, stroke)
                stroke.color = EdgePalette.accent[1]
                stroke.strokeWidth = density * 2.4f
                stroke.alpha = (a * 255f).roundToInt()
                canvas.drawArc(ring, 90f, 180f * ringShare, false, stroke)
                canvas.drawArc(ring, 90f, -180f * ringShare, false, stroke)
            }
        } else if (CameraRing.enabled && hasRing && ringGradient != null) {
            val grow = density * 1.5f * energy
            stroke.shader = null
            stroke.color = shown[0]
            if (softContours) {
                for (index in 12 downTo 1) drawRing(canvas, grow, density * (18f + 14f * energy) * index / 12f,
                    a * (.14f + .16f * energy) * (25f - 2f * index) / 144f)
            } else drawRing(canvas, grow, density * (5f + 8f * energy), a * (.14f + .16f * energy))
            stroke.color = shown[2]
            if (!softContours) drawRing(canvas, grow, density * 1.1f, a * .45f)
            matrix.setRotate(spin, ring.centerX(), ring.centerY()); ringGradient.setLocalMatrix(matrix)
            stroke.shader = ringGradient
            if (softContours) {
                for (index in 12 downTo 1) drawRing(canvas, grow, density * (10f + 6f * energy) * index / 12f,
                    a * (25f - 2f * index) / 144f * .55f)
            } else drawRing(canvas, grow, density * (1.8f + 1.2f * energy), a)
            // Sonar: a ripple leaves the camera about once per second while Tom or Sirius is active.
            val active = voice + thinking
            stroke.shader = null
            if (motion && active > .05f) {
                stroke.color = shown[0]
                if (softContours) {
                    for (index in 12 downTo 1) drawRing(canvas, grow + density * 16f * sonar,
                        density * 8f * index / 12f, a * (1f - sonar) * (.15f + .55f * energy) * active *
                            (25f - 2f * index) / 144f)
                } else drawRing(canvas, grow + density * 16f * sonar, density * 1.2f,
                    a * (1f - sonar) * (.15f + .55f * energy) * active)
            }
            // The coral ring drawn by the opening fades into the neutral one.
            val coral = if (motion) HudTimeline.accentAlpha(openMs) else 0f
            if (coral > .004f) {
                stroke.color = EdgePalette.accent[1]
                drawRing(canvas, 0f, density * 2.4f, a * coral)
            }
        }
        if (motion) commas(canvas, openMs, a, density, tom)
        stroke.shader = null
    }
}
