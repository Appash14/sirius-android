package fr.tom.sirius

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Small speech-band signature, insensitive to waveform phase and capture rate. */
internal object EchoSpectrum {
    fun of(samples: DoubleArray, sampleRate: Double): DoubleArray {
        if (samples.isEmpty() || sampleRate <= 0) return DoubleArray(16)
        val mean = samples.average()
        return DoubleArray(16) { band ->
            val coefficient = 2.0 * cos(2.0 * PI * (band + 1) * 250.0 / sampleRate)
            var previous = 0.0; var before = 0.0
            samples.forEach { sample ->
                val current = sample - mean + coefficient * previous - before
                before = previous; previous = current
            }
            sqrt((previous * previous + before * before - coefficient * previous * before).coerceAtLeast(0.0))
        }
    }

    fun similarity(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa * bb <= 0) 0.0 else dot / sqrt(aa * bb)
    }
}
