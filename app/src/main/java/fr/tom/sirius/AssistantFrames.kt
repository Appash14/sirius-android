package fr.tom.sirius

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.util.Locale

/** Fixed storage during drawing. Only numeric timing data leaves the process. */
internal class AssistantFrames(private val window: Window) {
    private val thread = HandlerThread("SiriusFrames").apply { start() }
    private val handler = Handler(thread.looper)
    private val samples = LongArray(2048)
    private var count = 0
    private var started = 0L
    private var kind: String? = null
    private var skipFirst = true
    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        synchronized(samples) {
            if (kind != null) {
                if (skipFirst) skipFirst = false
                else if (count < samples.size) samples[count++] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            }
        }
    }
    fun begin(direction: String) {
        end()
        synchronized(samples) { count = 0; skipFirst = true; kind = direction; started = SystemClock.elapsedRealtime() }
        window.addOnFrameMetricsAvailableListener(listener, handler)
    }
    fun end() {
        val line = synchronized(samples) {
            val direction = kind ?: return
            kind = null
            val values = samples.copyOf(count).apply { sort() }
            fun percentile(fraction: Float): Double = if (values.isEmpty()) 0.0 else
                values[((values.size - 1) * fraction).toInt()] / 1_000_000.0
            String.format(Locale.ROOT, "%s fin duree_ms=%d frames=%d lents_16ms=%d max_ms=%.2f p50_ms=%.2f p90_ms=%.2f",
                direction, SystemClock.elapsedRealtime() - started, count, values.count { it > 16_700_000L },
                percentile(1f), percentile(.5f), percentile(.9f))
        }
        window.removeOnFrameMetricsAvailableListener(listener)
        Log.i("SiriusAnim", line)
    }
    fun close() { end(); thread.quitSafely() }
}
