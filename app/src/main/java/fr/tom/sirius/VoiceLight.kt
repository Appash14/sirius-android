package fr.tom.sirius

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Radial light only: no stroke, mask filter or hard waveform, on both hardware and Roborazzi canvases. */
internal class VoiceLight {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tints = IntArray(2)
    private val gradients = arrayOfNulls<Shader>(2)
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sunComposite = Paint()
    // Warm white in the middle, pastel prism reflections around it. Retained unit gradients, no frame allocations.
    private val sunGradients = intArrayOf(0xFFFFFBF4.toInt(), 0xFFFFA6CF.toInt(), 0xFFFFC399.toInt(),
        0xFFFFE79A.toInt(), 0xFF96E7C1.toInt(), 0xFF8ACFFF.toInt(), 0xFFBCA4FF.toInt()).map { color ->
        fun alpha(value: Int) = (color and 0x00FFFFFF) or (value shl 24)
        RadialGradient(0f, 0f, 1f, intArrayOf(alpha(255), alpha(208), alpha(94), alpha(24), alpha(0)),
            floatArrayOf(0f, .2f, .48f, .73f, 1f), Shader.TileMode.CLAMP)
    }

    fun palette(weights: FloatArray) {
        val total = weights.sum().coerceAtLeast(.001f)
        for (slot in 0..1) {
            var red = 0f; var green = 0f; var blue = 0f
            for (index in HudColor.entries.indices) {
                val color = if (slot == 0) HudColor.entries[index].top else HudColor.entries[index].bottom
                val share = weights[index] / total
                red += Color.red(color) * share; green += Color.green(color) * share; blue += Color.blue(color) * share
            }
            val tint = Color.rgb(red.roundToInt(), green.roundToInt(), blue.roundToInt())
            if (gradients[slot] != null && tints[slot] == tint) continue
            tints[slot] = tint
            fun alpha(value: Int) = (tint and 0x00FFFFFF) or (value shl 24)
            gradients[slot] = RadialGradient(0f, 0f, 1f,
                intArrayOf(alpha(255), alpha(190), alpha(76), alpha(15), alpha(0)),
                floatArrayOf(0f, .22f, .5f, .75f, 1f), Shader.TileMode.CLAMP)
        }
    }

    private fun spot(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, opacity: Float, slot: Int) {
        if (rx <= 0f || ry <= 0f || opacity <= 0f) return
        paint.shader = gradients[slot] ?: return
        paint.alpha = (255f * opacity.coerceIn(0f, 1f)).roundToInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, paint)
        canvas.restore()
    }

    fun aurora(canvas: Canvas, width: Float, height: Float, density: Float, seconds: Float, level: Float,
        tomPresence: Float = 0f) {
        val voice = level.coerceIn(0f, 1f)
        val tom = tomPresence.coerceIn(0f, 1f)
        val breath = .92f + .08f * sin(seconds * .65f)
        // The centers sit below the glass: only the feathered upper part rises into the screen.
        for (index in 0..3) {
            val phase = index * 1.8f
            val x = width * (.12f + index * .25f + .055f * sin(seconds * .23f + phase))
            val y = height + density * (10f + 7f * sin(seconds * .31f + phase))
            spot(canvas, x, y, width * (.43f + .025f * sin(seconds * .27f + phase)),
                density * (105f + voice * 125f) * breath, (.19f + voice * .18f) * breath * (1f - tom), index % 2)
        }
        wave(canvas, width, height - density * (25f + voice * 47f),
            density * (3f + voice * 19f), density * (11f + voice * 13f), seconds, voice, 1f - tom)
        if (tom > .004f) sun(canvas, width, height, seconds, voice, tom)
    }

    private fun sunSpot(canvas: Canvas, x: Float, y: Float, rx: Float, ry: Float, opacity: Float, tint: Int) {
        if (rx <= 0f || ry <= 0f || opacity <= 0f) return
        sunPaint.shader = sunGradients[tint]
        sunPaint.alpha = (255f * opacity.coerceIn(0f, 1f)).roundToInt()
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, sunPaint)
        canvas.restore()
    }

    private fun sun(canvas: Canvas, width: Float, height: Float, seconds: Float, voice: Float, amount: Float) {
        val breath = .97f + .03f * sin(seconds * .65f)
        // At normal speech the diffuse halo occupies over the bottom third, and grows with each syllable.
        val rise = height * (.46f + .27f * voice) * breath * (.12f + .88f * amount)
        // Only strong speech lights the white core, once the colored bloom has had time to rise.
        val strong = ((voice - .6f) / .4f).coerceIn(0f, 1f)
        val white = strong * strong * (3f - 2f * strong) * amount * amount
        val cx = width * (.5f + .035f * sin(seconds * .28f))
        val cy = height * 1.025f
        // Cap the opacity of the combined light: many overlapping lobes must still let content show through.
        sunComposite.alpha = (255f * amount * (.6f + .1f * voice)).roundToInt()
        canvas.saveLayer(0f, height - rise * 1.2f, width, height, sunComposite)
        // Broad pastel lobes drift around a white sun, rather than forming saturated rainbow stripes.
        for (index in 0..5) {
            val phase = index * PI.toFloat() / 3f + seconds * .12f
            val x = cx + width * .38f * cos(phase)
            val y = cy - rise * (.13f + .09f * sin(phase))
            sunSpot(canvas, x, y, width * (.58f + .04f * sin(phase)), rise * .96f,
                .26f + .28f * voice, index + 1)
        }
        sunSpot(canvas, cx, cy, width * .95f, rise, (.5f + .22f * voice) * white, 0)
        sunSpot(canvas, cx, height * 1.04f, width * .64f, rise * .7f,
            (.58f + .23f * voice) * white, 0)
        // A wide, blurred wave rides inside the light; its height and speed follow the smoothed microphone level.
        for (index in 0..32) {
            val fraction = index / 32f
            val envelope = sin(PI.toFloat() * fraction).coerceAtLeast(0f)
            val phase = fraction * 2f * PI.toFloat() - seconds * (.55f + voice * 1.5f)
            val y = height - rise * (.2f + .09f * voice) + rise * (.025f + .055f * voice) * sin(phase)
            sunSpot(canvas, width * fraction, y, width * .12f, rise * (.09f + .035f * voice),
                envelope * (.035f + .025f * voice) * (1f - white), 5)
            sunSpot(canvas, width * fraction, y, width * .12f, rise * (.09f + .035f * voice),
                envelope * (.035f + .025f * voice) * white, 0)
        }
        canvas.restore()
    }

    /** Overlapping, feathered ellipses form a horizontal sine of light, including at frozen preview time. */
    fun wave(canvas: Canvas, width: Float, baseline: Float, amplitude: Float, softness: Float,
        seconds: Float, level: Float, amount: Float = 1f) {
        if (amount <= 0f) return
        val voice = level.coerceIn(0f, 1f)
        for (ribbon in 0..1) for (index in 0..64) {
            val fraction = index / 64f
            val envelope = sin(PI.toFloat() * fraction).coerceAtLeast(0f)
            val phase = fraction * 2f * PI.toFloat() * 1.4f - seconds * (1.1f + voice * 1.3f) + ribbon * 1.6f
            val y = baseline + amplitude * (sin(phase) + .22f * sin(phase * 1.7f + seconds * .4f))
            spot(canvas, width * fraction, y, width * .075f, softness,
                amount * envelope * (.045f + voice * .07f) * (if (ribbon == 0) 1f else .6f), ribbon)
        }
    }
}
