package fr.tom.sirius

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

const val SAMPLE_RATE = 16000
const val FRAME_SAMPLES = 320 // 20 ms

fun rms(samples: ShortArray, count: Int): Double {
    if (count <= 0) return 0.0
    var sum = 0.0
    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
    return sqrt(sum / count)
}

fun pcmBytes(samples: ShortArray, count: Int): ByteArray = ByteBuffer.allocate(count * 2)
    .order(ByteOrder.LITTLE_ENDIAN).apply { for (i in 0 until count) putShort(samples[i]) }.array()

fun wavBytes(pcm: ByteArray): ByteArray {
    require(pcm.size % 2 == 0)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        .putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
        .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
    return header.array() + pcm
}

/**
 * One piece of an utterance. Whole-phrase mode: a single last piece. Live mode: about one second each,
 * [voicedMs] tells the server whether the piece holds speech (0: silence, no new transcription needed).
 */
class AudioChunk(val pcm: ByteArray, val seq: Int, val last: Boolean, val voicedMs: Int, val locked: Boolean)

/**
 * Bounded utterance: 300 ms preroll, 900 ms silence, 55 s maximum.
 * Live mode (server announcing "partiels", Tom 2026-10-05): a piece about every second so his words show
 * while he speaks. Cut at a micro-pause (100 ms quiet after 0.8 s, or 250 ms quiet after 0.3 s), hard cut
 * at 1.2 s, 6 min maximum. The utterance still ends after 900 ms of silence, with a last piece.
 */
class PhraseDetector(val interruptionMs: Int = INTERRUPTION_MS, val playbackFloor: Double = 900.0) {
    private data class Frame(val pcm: ByteArray, val locked: Boolean)
    private val preRoll = ArrayDeque<Frame>()
    private var audio = ByteArrayOutputStream()
    private var speechMs = 0
    private var quietMs = 0
    private var totalMs = 0
    private var started = false
    private var noise = 100.0
    private var utteranceLocked = false
    private var pieceMs = 0
    private var pieceVoicedMs = 0
    private var seq = 0
    private var liveUtterance = false
    private var playbackSpeechMs = 0
    private var serverTail = false
    private var tailQuietMs = 0
    /** Sustained voiced frames during playback authorize a local audio cut. */
    val interruptionReady get() = playbackSpeechMs >= interruptionMs
    /** Live mode advertised by hello; may become available during the first utterance. */
    var live = false
    var completedLocked = false
        private set
    val speaking get() = started
    fun reset() {
        preRoll.clear(); audio = ByteArrayOutputStream(); speechMs = 0; quietMs = 0; totalMs = 0; started = false
        utteranceLocked = false; completedLocked = false; pieceMs = 0; pieceVoicedMs = 0; seq = 0
        playbackSpeechMs = 0; serverTail = false; tailQuietMs = 0
    }
    /** Drop the ended turn, then require a short pause before capturing a new one. */
    fun finishByServer() {
        val quiet = quietMs
        reset()
        tailQuietMs = quiet
        serverTail = quiet < 120
    }
    /** Whole-phrase mode: the phrase once 900 ms of silence ended it. */
    fun accept(pcm: ByteArray, energy: Double, playing: Boolean, locked: Boolean = false): ByteArray? =
        feed(pcm, energy, playing, locked)?.takeIf { it.last }?.pcm
    fun feed(pcm: ByteArray, energy: Double, playing: Boolean, locked: Boolean = false): AudioChunk? {
        val ms = pcm.size * 1000 / (SAMPLE_RATE * 2)
        // Hardware AEC and the media reference remove echo before detection. Keep a residual noise floor.
        val threshold = if (playing) maxOf(playbackFloor, noise * 4.0) else maxOf(450.0, noise * 3.0)
        val voiced = energy > threshold
        if (serverTail) {
            tailQuietMs = if (voiced) 0 else tailQuietMs + ms
            if (tailQuietMs >= 120) serverTail = false
            return null
        }
        playbackSpeechMs = if (!playing) 0 else if (voiced) playbackSpeechMs + ms else (playbackSpeechMs - ms * 2).coerceAtLeast(0)
        if (!started && !voiced) noise = noise * .98 + energy.coerceAtMost(500.0) * .02
        if (!started) {
            preRoll.addLast(Frame(pcm, locked))
            while (preRoll.sumOf { it.pcm.size } > SAMPLE_RATE * 2 * 300 / 1000) preRoll.removeFirst()
            speechMs = if (voiced) speechMs + ms else 0
            if (speechMs >= 120) {   // Capture the first word even while Sirius is speaking.
                started = true; liveUtterance = live
                utteranceLocked = preRoll.any { it.locked }
                preRoll.forEach { audio.write(it.pcm) }
                pieceMs = preRoll.sumOf { it.pcm.size } * 1000 / (SAMPLE_RATE * 2); pieceVoicedMs = speechMs
                preRoll.clear()
            }
            return null
        }
        // The first hello can arrive after Tom started speaking. Upgrade that same utterance
        // when the server advertises live chunks, so its first words do not wait for silence.
        liveUtterance = liveUtterance || live
        audio.write(pcm); totalMs += ms; utteranceLocked = utteranceLocked || locked
        if (voiced) { speechMs += ms; quietMs = 0 } else quietMs += ms
        pieceMs += ms
        // A soft final word can stay under the speech threshold: the server must still transcribe it.
        if (energy > threshold * SOFT_VOICE) pieceVoicedMs += ms
        if (quietMs >= 900 || totalMs >= (if (liveUtterance) LIVE_MAX_MS else 55000)) {
            val piece = if (speechMs >= 200 || seq > 0) AudioChunk(audio.toByteArray(), seq, true, voicedHint(), utteranceLocked) else null
            val lockContext = utteranceLocked
            reset(); completedLocked = lockContext
            return piece
        }
        // A piece of pure silence waits for the end of the utterance: nothing new to transcribe.
        if (liveUtterance && speechMs >= 250 && pieceVoicedMs >= 60 &&
            ((playing && seq == 0 && playbackSpeechMs >= interruptionMs) ||
                pieceMs >= 1200 || (pieceMs >= 800 && quietMs >= 100) || (pieceMs >= 300 && quietMs >= 250))) {
            val piece = AudioChunk(audio.toByteArray(), seq++, false, voicedHint(), utteranceLocked)
            audio = ByteArrayOutputStream(); pieceMs = 0; pieceVoicedMs = 0
            return piece
        }
        return null
    }
    private fun voicedHint() = if (pieceVoicedMs >= 60) pieceVoicedMs else 0
    companion object {
        const val INTERRUPTION_MS = 500
        const val SOFT_VOICE = .55
        const val LIVE_MAX_MS = 360_000
    }
}
