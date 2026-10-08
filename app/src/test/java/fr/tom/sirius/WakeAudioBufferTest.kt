package fr.tom.sirius

import org.junit.Assert.*
import org.junit.Test

class WakeAudioBufferTest {
    @Test fun keepsTheAcceptedWakeForTheFirstUtteranceIncludingLockContext() {
        val ring = WakeAudioBuffer()
        repeat(100) { i -> ring.feed(ByteArray(FRAME_SAMPLES * 2) { i.toByte() }, locked = i == 20) }
        val wake = ring.wake(WakeFilter.Span(.3, 1.1))!!
        assertEquals(1.04, wake.seconds, .001)
        assertEquals(9.toByte(), wake.pcm.first())
        assertTrue(wake.locked)
        ring.clear()
        assertNull(ring.wake(WakeFilter.Span(.3, 1.1)))
    }
    @Test fun audioOlderThanTheBoundedRingNeverLeavesIt() {
        val ring = WakeAudioBuffer()
        repeat(300) { ring.feed(ByteArray(FRAME_SAMPLES * 2), false) }
        assertNull(ring.wake(WakeFilter.Span(.3, 1.1)))
        assertNotNull(ring.wake(WakeFilter.Span(4.3, 5.1)))
    }
    @Test fun aSecondWakeUsesVosksContinuingClockAfterReset() {
        val ring = WakeAudioBuffer()
        repeat(100) { ring.feed(ByteArray(FRAME_SAMPLES * 2), false) }
        ring.discard()
        repeat(100) { ring.feed(ByteArray(FRAME_SAMPLES * 2) { 7 }, false) }
        assertNull(ring.wake(WakeFilter.Span(.3, 1.1)))
        assertEquals(7.toByte(), ring.wake(WakeFilter.Span(2.3, 3.1))!!.pcm.first())
    }
}
