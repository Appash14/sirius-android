package fr.tom.sirius

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.sqrt

internal data class PlaybackReference(val rms: Double, val spectrum: DoubleArray)

/** Output mix level/signature only, using the playback volume. Never stores or uploads the media. */
internal class PlaybackLevelReference : AutoCloseable {
    private var visualizer: Visualizer? = null
    private var waveform = ByteArray(0)
    private var retryAt = 0L
    private var warned = false

    fun read(now: Long): PlaybackReference? {
        if (visualizer == null && now >= retryAt) {
            var candidate: Visualizer? = null
            try {
                candidate = Visualizer(0)
                candidate.captureSize = Visualizer.getCaptureSizeRange().last()
                check(candidate.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED) == Visualizer.SUCCESS)
                check(candidate.setEnabled(true) == Visualizer.SUCCESS)
                waveform = ByteArray(candidate.captureSize)
                visualizer = candidate
            } catch (_: Exception) {
                candidate?.release()
                retryAt = now + 5_000
                if (!warned) { Log.i("SiriusBarge", "reference media indisponible, AEC seul"); warned = true }
            }
        }
        val effect = visualizer ?: return null
        return try {
            if (effect.getWaveForm(waveform) != Visualizer.SUCCESS) { close(); retryAt = now + 5_000; null }
            else {
                // Unsigned 8-bit waveform centered at 128, converted to PCM16 RMS units.
                val samples = DoubleArray(waveform.size) { ((waveform[it].toInt() and 255) - 128) * 256.0 }
                val energy = sqrt(samples.sumOf { it * it } / samples.size.coerceAtLeast(1))
                PlaybackReference(energy, EchoSpectrum.of(samples, effect.samplingRate / 1000.0))
            }
        } catch (_: Exception) { close(); retryAt = now + 5_000; null }
    }

    override fun close() {
        runCatching { visualizer?.release() }
        visualizer = null
    }
}
