package fr.tom.sirius

import kotlin.math.sqrt

/**
 * Gate only the residual matching the speaker level after Android AEC. Learn the acoustic gain
 * between output and microphone, then freeze it while Tom speaks (double talk). No media audio
 * is sent or retained. A short reference tail covers the speaker/record latency.
 */
internal class MediaEchoGate {
    private data class Level(val at: Long, val rms: Double, val spectrum: DoubleArray?)
    private val recent = ArrayDeque<Level>()
    private var echoGain: Double? = null
    private var lastOutputAt = Long.MIN_VALUE

    fun voiceEnergy(microphoneRms: Double, outputRms: Double?, now: Long, speechOngoing: Boolean,
                    microphoneSpectrum: DoubleArray? = null, outputSpectrum: DoubleArray? = null): Double {
        // Unsupported output reference: keep listening, with platform AEC as the fallback.
        if (outputRms == null) return microphoneRms
        recent.addLast(Level(now, outputRms, outputSpectrum))
        while (recent.isNotEmpty() && now - recent.first().at > 160) recent.removeFirst()
        val reference = recent.maxOfOrNull { it.rms } ?: 0.0
        if (reference < 100.0) {
            if (lastOutputAt != Long.MIN_VALUE && now - lastOutputAt > 400) echoGain = null
            return microphoneRms
        }
        lastOutputAt = now
        // Level alone cannot distinguish a quiet Tom from loud media. Suppress only a matching
        // speech-band signature, including earlier reference frames for acoustic latency.
        if (microphoneSpectrum != null && recent.none {
                it.spectrum?.let { spectrum -> EchoSpectrum.similarity(microphoneSpectrum, spectrum) >= .82 } == true
            }) return microphoneRms
        val ratio = microphoneRms / reference
        val learned = echoGain
        // Do not learn Tom's voice if a video starts in the middle of his utterance.
        if (!speechOngoing && microphoneRms >= 450.0) {
            if (learned == null) echoGain = ratio
            else if (ratio < learned) echoGain = learned * .9 + ratio * .1
        }
        val echo = reference * (echoGain ?: return microphoneRms)
        // A 6 dB margin absorbs level/latency jitter; an independent voice exceeding the echo passes.
        if (microphoneRms <= echo * 2.0 + 150.0) return 0.0
        return sqrt((microphoneRms * microphoneRms - echo * echo).coerceAtLeast(0.0))
    }
}
