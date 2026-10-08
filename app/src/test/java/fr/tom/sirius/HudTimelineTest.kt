package fr.tom.sirius

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudTimelineTest {
    private val end = HudTimeline.ENTER_MS
    private val exit = HudTimeline.EXIT_MS

    @Test fun interruptionStartsFromExactlyTheCurrentPoseAndDisabledModeIsFinal() {
        assertEquals(420f, end, 0f); assertEquals(260f, exit, 0f)
        val hud = HudMotionState().apply { prepareShow(true); enterMs = 137f }
        val before = listOf(HudTimeline.veil(hud.enterMs, hud.exitMs), HudTimeline.pillAlpha(hud.enterMs, hud.exitMs),
            HudTimeline.halo(hud.enterMs, hud.exitMs), HudTimeline.bubbleAlpha(hud.enterMs, hud.exitMs, 0))
        hud.exiting = true
        assertEquals(before, listOf(HudTimeline.veil(hud.enterMs, hud.exitMs), HudTimeline.pillAlpha(hud.enterMs, hud.exitMs),
            HudTimeline.halo(hud.enterMs, hud.exitMs), HudTimeline.bubbleAlpha(hud.enterMs, hud.exitMs, 0)))
        var veil = before.first()
        for (u in 0..260 step 16) {
            val next = HudTimeline.veil(hud.enterMs, u.toFloat())
            assertTrue(next <= veil + 1e-6f); veil = next
        }
        hud.prepareShow(false)
        assertEquals(end, hud.enterMs, 0f); assertEquals(0f, hud.exitMs, 0f)
        assertEquals(1f, HudTimeline.veil(hud.enterMs, hud.exitMs), 0f)
        assertEquals(1f, HudTimeline.pillAlpha(hud.enterMs, hud.exitMs), 0f)
    }

    @Test fun hiddenPoseAtZeroAndFinalPoseAtTheEnd() {
        assertEquals(0f, HudTimeline.veil(0f, 0f), 0f)
        assertEquals(0f, HudTimeline.pillAlpha(0f, 0f), 0f)
        assertEquals(0f, HudTimeline.halo(0f, 0f), 0f)
        assertEquals(0f, HudTimeline.waves(0f, 0f), 0f)
        assertEquals(0f, HudTimeline.bubbleAlpha(0f, 0f, 0), 0f)
        assertEquals(0f, HudTimeline.listening(0f, 0f), 0f)
        assertEquals(1f, HudTimeline.veil(end, 0f), 0f)
        assertEquals(1f, HudTimeline.pillAlpha(end, 0f), 0f)
        assertEquals(1f, HudTimeline.pillProgress(end), 0f)
        assertEquals(0f, HudTimeline.pillBlur(end), 0f)
        assertEquals(.18f, HudTimeline.halo(end, 0f), .001f)
        assertEquals(1f, HudTimeline.waves(end, 0f), 0f)
        assertEquals(1f, HudTimeline.bubbleAlpha(end, 0f, 5), 0f)
        assertEquals(1f, HudTimeline.listening(end, 0f), 0f)
    }

    @Test fun exitEndsFullyHidden() {
        assertEquals(0f, HudTimeline.veil(end, exit), 0f)
        assertEquals(0f, HudTimeline.pillAlpha(end, exit), 0f)
        assertEquals(1f, HudTimeline.pillFall(exit), 0f)
        assertEquals(0f, HudTimeline.halo(end, exit), 0f)
        assertEquals(0f, HudTimeline.waves(end, exit), 0f)
        assertEquals(0f, HudTimeline.bubbleAlpha(end, exit, 0), 0f)
    }

    @Test fun entranceIsMonotonicAndHasNoFirstFrameJump() {
        var previous = 0f
        var t = 0f
        while (t <= end) {
            val veil = HudTimeline.veil(t, 0f)
            assertTrue("voile non monotone à $t", veil >= previous - 1e-6f)
            previous = veil
            t += 16f
        }
        // First animated frame at 60 fps: no element moves more than 5 % of its amplitude.
        assertTrue(HudTimeline.veil(16f, 0f) <= .05f)
        assertTrue(HudTimeline.pillAlpha(16f, 0f) <= .05f)
        assertTrue(HudTimeline.pillProgress(16f) <= .05f)
        assertTrue(HudTimeline.waves(16f, 0f) <= .05f)
        // The spring overshoots by about 2 % and settles before the end of the entrance.
        val peak = (0..420).maxOf { HudTimeline.pillProgress(it.toFloat()) }
        assertTrue(peak in 1.01f..1.03f)
        assertEquals(1f, HudTimeline.pillProgress(end - 1f), .006f)
    }

    @Test fun bubblesCascadeFromTheNewest() {
        assertTrue(HudTimeline.bubbleAlpha(150f, 0f, 0) > HudTimeline.bubbleAlpha(150f, 0f, 1))
        assertEquals(HudTimeline.bubbleAlpha(250f, 0f, 5), HudTimeline.bubbleAlpha(250f, 0f, 9), 0f)
    }
}
