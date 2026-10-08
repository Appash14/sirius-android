package fr.tom.sirius

import org.junit.Assert.assertEquals
import org.junit.Test

class PcmEnvelopeTest {
    @Test fun samplesFollowPlayerPositionInTwentyMillisecondWindows() {
        val builder = PcmEnvelopeBuilder()
        builder.sample(0, .22f)
        builder.sample(19_999, -.22f)
        builder.sample(40_000, .11f)
        val envelope = builder.build()
        assertEquals(1f, envelope.at(0), .01f)
        assertEquals(1f, envelope.at(19), .01f)
        assertEquals(0f, envelope.at(20), .01f)
        assertEquals(.5f, envelope.at(40), .01f)
        assertEquals(0f, envelope.at(200), .01f)
    }
}
