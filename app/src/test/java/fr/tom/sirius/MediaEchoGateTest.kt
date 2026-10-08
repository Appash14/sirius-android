package fr.tom.sirius

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MediaEchoGateTest {
    private val media = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    private val tom = doubleArrayOf(0.0, 0.0, 1.0, 0.0)
    @Test fun speakerOnlyNeverStartsAnUtteranceButTomCanSpeakOverIt() {
        val gate = MediaEchoGate()
        val vad = PhraseDetector().apply { live = true }
        val submitted = mutableListOf<AudioChunk>()
        repeat(150) { i ->
            val output = 4000.0 + (i % 8) * 200
            val energy = gate.voiceEnergy(output * .25, output, i * 20L, vad.speaking, media, media)
            assertEquals(0.0, energy, 0.0)
            vad.feed(ByteArray(FRAME_SAMPLES * 2), energy, false)?.let(submitted::add)
        }
        assertTrue(submitted.isEmpty()); assertFalse(vad.speaking)
        repeat(60) { i ->
            // Tom need not be louder than the media: his different spectrum must pass.
            val energy = gate.voiceEnergy(1500.0, 4000.0, 3000L + i * 20, vad.speaking, tom, media)
            assertEquals(1500.0, energy, 0.0)
            vad.feed(pcmBytes(ShortArray(FRAME_SAMPLES) { 1500 }, FRAME_SAMPLES), energy, false)?.let(submitted::add)
        }
        assertTrue(vad.speaking); assertTrue(submitted.isNotEmpty())
    }
    @Test fun doubleTalkDoesNotTeachTheGateToSuppressTomAndEchoTailStaysFiltered() {
        val gate = MediaEchoGate()
        assertEquals(0.0, gate.voiceEnergy(1000.0, 4000.0, 0, false, media, media), 0.0)
        repeat(100) { i ->
            assertTrue(gate.voiceEnergy(3500.0, 4000.0, 20L + i * 20, true, media, media) > 3000)
        }
        assertEquals(0.0, gate.voiceEnergy(1000.0, 0.0, 2020, false, media, tom), 0.0)
        assertEquals(1500.0, gate.voiceEnergy(1500.0, 0.0, 2500, false, tom, tom), 0.0)
    }
    @Test fun unavailableReferenceAndVideoStartingDuringTomSpeechKeepTheMicrophoneOpen() {
        val gate = MediaEchoGate()
        assertEquals(1500.0, gate.voiceEnergy(1500.0, null, 0, false), 0.0)
        assertEquals(1500.0, gate.voiceEnergy(1500.0, 4000.0, 20, true, media, media), 0.0)
    }
    @Test fun spectrumMatchesAcrossCaptureRatesWithoutConfusingAnotherVoiceBand() {
        fun tone(rate: Int, n: Int, hz: Double) = DoubleArray(n) { sin(2 * PI * hz * it / rate) }
        val output = EchoSpectrum.of(tone(48000, 1024, 1000.0), 48000.0)
        val echo = EchoSpectrum.of(tone(16000, 320, 1000.0), 16000.0)
        val voice = EchoSpectrum.of(tone(16000, 320, 2500.0), 16000.0)
        assertTrue(EchoSpectrum.similarity(output, echo) > .95)
        assertTrue(EchoSpectrum.similarity(output, voice) < .2)
    }
}
