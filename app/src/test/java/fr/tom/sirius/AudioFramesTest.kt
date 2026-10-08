package fr.tom.sirius

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioFramesTest {
    private fun frame(value: Short) = pcmBytes(ShortArray(FRAME_SAMPLES) { value }, FRAME_SAMPLES)
    @Test fun wavHasLittleEndianMono16kHeaderAndPreservesSamples() {
        val pcm = pcmBytes(shortArrayOf(0, -32768, 32767, 1000), 4)
        val wav = wavBytes(pcm)
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals(44, header.getInt(4))
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(16000, header.getInt(24))
        assertEquals(16, header.getShort(34).toInt())
        assertEquals(8, header.getInt(40))
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
    @Test fun silenceDoesNotSubmitAndSpeechEndsAt900ms() {
        val vad = PhraseDetector()
        repeat(100) { assertNull(vad.accept(frame(0), 0.0, false)) }
        repeat(25) { assertNull(vad.accept(frame(1500), 1500.0, false)) }
        assertTrue(vad.speaking)
        repeat(44) { assertNull(vad.accept(frame(0), 0.0, false)) }
        val phrase = vad.accept(frame(0), 0.0, false)
        assertNotNull(phrase); assertFalse(vad.speaking)
        assertTrue(phrase!!.size > 16000)
    }
    @Test fun briefNoiseDuringPlaybackDoesNotInterruptButNormalVoiceDoes() {
        val vad = PhraseDetector()
        repeat(10) { vad.feed(frame(1500), 1500.0, true) }
        assertTrue("The microphone captures short words before deciding to cut", vad.speaking); assertFalse(vad.interruptionReady)
        repeat(50) { vad.feed(frame(0), 0.0, true) }
        assertFalse(vad.speaking)
        repeat(24) { vad.feed(frame(1500), 1500.0, true); assertFalse(vad.interruptionReady) }
        vad.feed(frame(1500), 1500.0, true)
        assertTrue(vad.interruptionReady)
    }
    @Test fun shortYesIsCapturedDuringPlaybackWithoutALocalCut() {
        val vad = PhraseDetector().apply { live = true }
        val voice = frame(1500)
        repeat(10) { assertNull(vad.feed(voice, 1500.0, true)); assertFalse(vad.interruptionReady) }
        val pieces = (0 until 45).mapNotNull { vad.feed(frame(0), 0.0, true) }
        assertEquals(1, pieces.size)
        assertTrue(pieces.single().last)
        assertEquals(200, pieces.single().voicedMs)
        assertArrayEquals(List(10) { voice }.fold(ByteArray(0)) { all, bytes -> all + bytes },
            pieces.single().pcm.copyOfRange(0, 200 * 32))
        assertFalse(vad.interruptionReady)
    }
    @Test fun playbackSpeechUploadsAt500msAndPreservesTheFirstWord() {
        val vad = PhraseDetector().apply { live = true }
        val pieces = (0 until 25).mapNotNull { vad.feed(frame(1500), 1500.0, true) }
        assertEquals(1, pieces.size)
        assertEquals(500, pieces.single().voicedMs)
        assertEquals(500 * 32, pieces.single().pcm.size)
        assertEquals(0, pieces.single().seq)
        assertFalse(pieces.single().last)
        assertTrue(vad.interruptionReady)
    }
    @Test fun continuousSpeechIsBoundedBelowServerLimit() {
        val vad = PhraseDetector()
        var result: ByteArray? = null
        repeat(3000) { if (result == null) result = vad.accept(frame(3000), 3000.0, false) }
        assertNotNull(result)
        assertTrue(result!!.size < 4 * 1024 * 1024)
        assertTrue(result!!.size <= SAMPLE_RATE * 2 * 56)
    }
    @Test fun onlySustainedVoiceDuringActualPlaybackInterrupts() {
        val vad = PhraseDetector()
        repeat(50) { vad.feed(frame(1500), 1500.0, false) }
        assertFalse(vad.interruptionReady)
        repeat(26) { vad.feed(frame(1500), 1500.0, true) }
        assertTrue(vad.interruptionReady)
        repeat(15) { vad.feed(frame(0), 0.0, true) }
        assertFalse(vad.interruptionReady)
        repeat(26) { vad.feed(frame(1500), 1500.0, true) }
        assertTrue(vad.interruptionReady)
        vad.feed(frame(1500), 1500.0, false)
        assertFalse(vad.interruptionReady)
        vad.reset(); assertFalse(vad.interruptionReady)
    }
    private fun feedAll(vad: PhraseDetector, frames: List<Short>, locked: (Int) -> Boolean = { false }): List<AudioChunk> =
        frames.mapIndexedNotNull { i, v -> vad.feed(frame(v), v.toDouble(), false, locked(i)) }
    private fun voice(ms: Int) = List(ms / 20) { 1500.toShort() }
    private fun quiet(ms: Int) = List(ms / 20) { 0.toShort() }

    @Test fun livePiecesAreAboutOneSecondAndRebuildTheWholeUtterance() {
        val frames = quiet(600) + voice(3000) + quiet(920)
        val pieces = feedAll(PhraseDetector().apply { live = true }, frames)
        val whole = PhraseDetector()
        val phrase = frames.mapNotNull { whole.accept(frame(it), it.toDouble(), false) }.single()
        assertEquals(listOf(0, 1, 2, 3), pieces.map { it.seq })
        assertEquals(listOf(false, false, false, true), pieces.map { it.last })
        // Hard cut at 1.2 s during continuous speech, then a cut 100 ms into the silence.
        assertEquals(listOf(1200, 1200, 880, 800), pieces.map { it.pcm.size / 32 })
        assertTrue(pieces.dropLast(1).all { it.voicedMs > 0 })
        assertEquals("the last piece is only silence: no audio to send", 0, pieces.last().voicedMs)
        assertArrayEquals(phrase, pieces.fold(ByteArray(0)) { all, piece -> all + piece.pcm })
    }
    @Test fun livePieceIsCutAtMicroPausesBeforeTheHardCut() {
        val vad = PhraseDetector().apply { live = true }
        assertTrue(feedAll(vad, quiet(600) + voice(700) + quiet(80)).isEmpty())
        val first = feedAll(vad, quiet(20)).single()   // 100 ms of quiet after 0.98 s: cut now
        assertEquals(980, first.pcm.size / 32)
        assertTrue(feedAll(vad, voice(300) + quiet(240)).isEmpty())
        val second = feedAll(vad, quiet(20)).single()  // 260 ms of quiet after 0.3 s: cut too
        assertEquals(560, second.pcm.size / 32)
        assertFalse(second.last); assertEquals(1, second.seq)
    }
    @Test fun helloDuringFirstSpeechEnablesPartialsBeforeSilenceWithoutLosingSamples() {
        val vad = PhraseDetector().apply { live = true }
        assertTrue(feedAll(vad, quiet(600) + voice(140) + quiet(1000)).isEmpty())
        val legacy = PhraseDetector()
        val pieces = feedAll(legacy, quiet(600) + voice(400)) + run { legacy.live = true; feedAll(legacy, voice(2600) + quiet(920)) }
        assertTrue("The first utterance must send live pieces after hello", pieces.size > 1)
        assertTrue(pieces.last().last)
        assertTrue(pieces.dropLast(1).none { it.last })
        assertEquals(pieces.indices.toList(), pieces.map { it.seq })
        val whole = feedAll(PhraseDetector(), quiet(600) + voice(3000) + quiet(920)).single().pcm
        assertArrayEquals(whole, pieces.fold(ByteArray(0)) { all, piece -> all + piece.pcm })
        legacy.live = true
        assertTrue(feedAll(legacy, quiet(600) + voice(3000) + quiet(920)).size > 1)
    }
    @Test fun liveLockInPrerollMarksEveryPiece() {
        val vad = PhraseDetector().apply { live = true }
        val frames = quiet(600) + voice(3000) + quiet(920)
        val pieces = feedAll(vad, frames) { it in 25..28 }
        assertTrue(pieces.size > 2 && pieces.all { it.locked })
        assertTrue(vad.completedLocked)
        assertFalse(feedAll(vad, frames).any { it.locked })
    }
    @Test fun lockInPrerollOrUtteranceSurvivesUnlockUntilSubmission() {
        val vad = PhraseDetector()
        repeat(10) { vad.accept(frame(0), 0.0, false, locked = true) }
        repeat(25) { vad.accept(frame(1500), 1500.0, false, locked = false) }
        var phrase: ByteArray? = null
        repeat(45) { phrase = vad.accept(frame(0), 0.0, false, locked = false) ?: phrase }
        assertNotNull(phrase); assertTrue(vad.completedLocked)
        repeat(25) { vad.accept(frame(1500), 1500.0, false) }
        repeat(45) { vad.accept(frame(0), 0.0, false) }
        assertFalse(vad.completedLocked)
        repeat(25) { vad.accept(frame(1500), 1500.0, false) }
        vad.accept(frame(1500), 1500.0, false, locked = true)
        repeat(45) { vad.accept(frame(0), 0.0, false) }
        assertTrue(vad.completedLocked)
    }
}
