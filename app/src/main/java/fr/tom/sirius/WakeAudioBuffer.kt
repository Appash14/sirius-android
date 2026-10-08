package fr.tom.sirius

/** Four seconds in RAM only. Nothing is sent unless the final wake passes the confidence filter. */
internal class WakeAudioBuffer {
    private data class Frame(val from: Long, val pcm: ByteArray, val locked: Boolean)
    private val frames = ArrayDeque<Frame>()
    private var position = 0L
    fun clear() { frames.clear(); position = 0L }
    /** Vosk reset discards decoding, but its word timestamps keep the recognizer's audio clock. */
    fun discard() { frames.clear() }
    fun feed(pcm: ByteArray, locked: Boolean) {
        frames.addLast(Frame(position, pcm, locked)); position += pcm.size
        while (frames.isNotEmpty() && position - frames.first().from > SAMPLE_RATE * 2 * 4) frames.removeFirst()
    }
    fun wake(span: WakeFilter.Span): WakeAudio? {
        val from = ((span.start - .12).coerceAtLeast(0.0) * SAMPLE_RATE).toLong() * 2
        val to = ((span.end + .12) * SAMPLE_RATE).toLong() * 2
        // A wake older than the ring is unusable for the server check: keep listening locally.
        if (frames.isEmpty() || from < frames.first().from || to > position) return null
        val selected = frames.filter { it.from < to && it.from + it.pcm.size > from }
        val audio = java.io.ByteArrayOutputStream()
        selected.forEach {
            val start = (from - it.from).coerceAtLeast(0).toInt()
            val end = (to - it.from).coerceAtMost(it.pcm.size.toLong()).toInt()
            audio.write(it.pcm, start, end - start)
        }
        return WakeAudio(audio.toByteArray(), selected.any { it.locked })
    }
}

internal data class WakeAudio(val pcm: ByteArray, val locked: Boolean) {
    val seconds get() = pcm.size.toDouble() / (SAMPLE_RATE * 2)
    val ms get() = pcm.size * 1000 / (SAMPLE_RATE * 2)
}
