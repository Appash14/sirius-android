package fr.tom.sirius

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class EdgeGlowTest {
    private val end = HudTimeline.ENTER_MS

    @Test fun borderIsLitOnTheHiddenPoseBeforeVeilAndPillAndFadesOutCompletely() {
        assertTrue(HudTimeline.border(0f, 0f) >= .7f)
        assertEquals(0f, HudTimeline.veil(0f, 0f), 0f)
        assertEquals(0f, HudTimeline.pillAlpha(0f, 0f), 0f)
        assertEquals(0f, HudTimeline.bubbleAlpha(0f, 0f, 0), 0f)
        assertEquals(1f, HudTimeline.border(end, 0f), 0f)
        var previous = HudTimeline.border(0f, 0f)
        for (t in 0..420 step 10) {
            val next = HudTimeline.border(t.toFloat(), 0f)
            assertTrue("bordure non monotone à $t", next >= previous - 1e-6f); previous = next
        }
        for (u in 0..260 step 10) {
            val next = HudTimeline.border(end, u.toFloat())
            assertTrue("fondu non monotone à $u", next <= previous + 1e-6f); previous = next
        }
        // Nothing left on screen when the window is hidden at the end of the exit.
        assertEquals(0f, HudTimeline.border(end, 240f), 0f)
        assertEquals(0f, HudTimeline.border(end, HudTimeline.EXIT_MS), 0f)
        assertEquals(0f, HudTimeline.border(137f, HudTimeline.EXIT_MS), 0f)
    }

    @Test fun restIsNeutralAndEachVoiceStateHasItsOwnHue() {
        assertEquals(HudColor.WAIT, VoiceState().hudColor())
        assertEquals(HudColor.WAIT, VoiceState(conversation = true, phase = VoicePhase.LISTENING).hudColor())
        assertEquals(HudColor.TOM, VoiceState(conversation = true, phase = VoicePhase.LISTENING, speechActive = true).hudColor())
        assertEquals(HudColor.THINKING, VoiceState(conversation = true, phase = VoicePhase.THINKING).hudColor())
        assertEquals(HudColor.SIRIUS, VoiceState(conversation = true, phase = VoicePhase.SPEAKING).hudColor())
        assertEquals(HudColor.entries.size, EdgePalette.colors.size)
        fun rgb(color: Int) = intArrayOf((color shr 16) and 255, (color shr 8) and 255, color and 255)
        for (color in EdgePalette.colors[HudColor.WAIT.ordinal]) {
            val c = rgb(color); assertTrue("repos pas neutre", c.max() - c.min() <= 0x30)
        }
        for (color in EdgePalette.colors[HudColor.TOM.ordinal]) { val c = rgb(color); assertTrue(c[2] - c[0] >= 0x40) }
        for (color in EdgePalette.colors[HudColor.THINKING.ordinal]) { val c = rgb(color); assertTrue(c[2] - c[1] >= 0x40) }
        for (color in EdgePalette.colors[HudColor.SIRIUS.ordinal]) { val c = rgb(color); assertTrue(c[0] - c[2] >= 0x40) }
    }

    @Test fun cameraRingIsATrueCircleCenteredOnThePunchHole() {
        fun check(ring: FloatArray, cx: Float, cy: Float, radius: Float) {
            assertEquals("anneau pas rond", ring[2] - ring[0], ring[3] - ring[1], 1e-3f)
            assertEquals(cx, (ring[0] + ring[2]) / 2f, 1e-3f)
            assertEquals(cy, (ring[1] + ring[3]) / 2f, 1e-3f)
            assertEquals(radius, (ring[2] - ring[0]) / 2f, 1e-3f)
        }
        // Samsung (S25 Ultra): the cutout rises from the top edge down to the bottom of the punch hole.
        check(CameraHole.ring(513f, 0f, 567f, 94f, 8f), 540f, 67f, 35f)
        // Even reported above the screen, the ring stays on the hole at the bottom of the cutout.
        check(CameraHole.ring(513f, -40f, 567f, 94f, 8f), 540f, 67f, 35f)
        // A simple round cutout keeps its own center.
        check(CameraHole.ring(520f, 30f, 560f, 70f, 8f), 540f, 50f, 28f)
    }

    @Test fun cutoutPathGivesTheLargestCircleRestingOnItsBottom() {
        // Path.approximate: triplets of fraction, x, y.
        fun outline(xy: List<Float>) = FloatArray(xy.size / 2 * 3).also { out ->
            for (i in 0 until xy.size / 2) {
                out[i * 3] = i / (xy.size / 2f); out[i * 3 + 1] = xy[i * 2]; out[i * 3 + 2] = xy[i * 2 + 1]
            }
        }
        fun arc(cx: Float, cy: Float, radius: Float, from: Double, to: Double, steps: Int) = (0..steps).flatMap {
            val angle = from + (to - from) * it / steps
            listOf((cx + radius * cos(angle)).toFloat(), (cy + radius * sin(angle)).toFloat())
        }
        fun assertCircle(circle: FloatArray?, cx: Float, cy: Float, radius: Float) {
            assertNotNull("pas de cercle", circle)
            assertEquals(cx, circle!![0], .5f); assertEquals(cy, circle[1], .5f); assertEquals(radius, circle[2], .5f)
        }
        // A real punch hole path: the circle itself.
        assertCircle(CameraHole.inscribed(outline(arc(540f, 50f, 20f, 0.0, 2 * PI, 96))), 540f, 50f, 20f)
        // Samsung: a rectangle from the top edge to the bottom of the hole.
        assertCircle(CameraHole.inscribed(outline(listOf(513f, 0f, 567f, 0f, 567f, 94f, 513f, 94f, 513f, 0f))), 540f, 67f, 27f)
        // A pill rising above the screen: the hole is its bottom half circle.
        val pill = arc(540f, -20f, 27f, PI, 2 * PI, 24) + arc(540f, 67f, 27f, 0.0, PI, 24)
        assertCircle(CameraHole.inscribed(outline(pill)), 540f, 67f, 27f)
        // Nothing usable: no circle, the caller keeps the bounds.
        assertNull(CameraHole.inscribed(outline(listOf(0f, 0f, 10f, 0f))))
        assertNull(CameraHole.inscribed(outline(listOf(0f, 0f, 10f, 0f, 20f, 0f))))
    }

    @Test fun openingClimbsFromTheBottomThenDrawsTheRingCalmly() {
        assertTrue(HudTimeline.ACCENT_MS in 1200f..2000f)
        assertEquals(0f, HudTimeline.accentTravel(0f), 0f)
        assertEquals(1f, HudTimeline.accentAlpha(0f), 0f)
        assertEquals(0f, HudTimeline.ringDraw(0f), 0f)
        assertEquals(0f, HudTimeline.ribbonIn(0f), 0f)
        assertTrue("la montée doit partir tout de suite", HudTimeline.accentTravel(100f) > .03f)
        assertEquals(1f, HudTimeline.accentTravel(HudTimeline.CLIMB_MS), 0f)
        assertEquals("l'anneau attend la fin de la montée", 0f, HudTimeline.ringDraw(900f), 0f)
        assertEquals(1f, HudTimeline.ringDraw(1450f), 0f)
        assertEquals("rubans sans pop", 1f, HudTimeline.ribbonIn(2100f), 0f)
        for (t in listOf(HudTimeline.ACCENT_MS, 2000f, 5000f)) assertEquals(0f, HudTimeline.accentAlpha(t), 0f)
        var previous = 0f
        for (t in 0..1100 step 10) {
            val travel = HudTimeline.accentTravel(t.toFloat())
            assertTrue("montée qui recule à $t", travel >= previous - 1e-6f); previous = travel
        }
    }

    @Test fun ringTuningIsBoundedAndBumpsTheLayout() {
        val before = RingTuning.version
        RingTuning.set(100f, -100f, 2.5f)
        assertEquals(RingTuning.LIMIT_DP, RingTuning.dx, 0f)
        assertEquals(-RingTuning.LIMIT_DP, RingTuning.dy, 0f)
        assertEquals(2.5f, RingTuning.dr, 0f)
        assertTrue(RingTuning.version > before)
        RingTuning.set(0f, 0f, 0f)
    }
}
