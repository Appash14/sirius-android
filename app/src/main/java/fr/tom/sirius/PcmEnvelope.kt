package fr.tom.sirius

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 20 ms RMS windows. The cursor is the actual player position, never a wall-clock estimate. */
internal class PcmEnvelope(private val levels: FloatArray) {
    fun at(positionMs: Int): Float = levels.getOrElse(positionMs.coerceAtLeast(0) / WINDOW_MS) { 0f }
    companion object {
        const val WINDOW_MS = 20
        val SILENT = PcmEnvelope(floatArrayOf())
    }
}

internal class PcmEnvelopeBuilder {
    // Bound memory even for malformed or unexpectedly long segments (120 seconds).
    private val squares = DoubleArray(6000)
    private val counts = IntArray(6000)
    private var last = -1
    fun sample(timeUs: Long, value: Float) {
        val bin = (timeUs / (PcmEnvelope.WINDOW_MS * 1000L)).toInt()
        if (timeUs < 0 || bin !in squares.indices || !value.isFinite()) return
        val bounded = value.coerceIn(-1f, 1f).toDouble()
        squares[bin] += bounded * bounded; counts[bin]++; last = maxOf(last, bin)
    }
    fun build(): PcmEnvelope = PcmEnvelope(FloatArray(last + 1) { index ->
        if (counts[index] == 0) 0f else (sqrt(squares[index] / counts[index]) / .22).toFloat().coerceIn(0f, 1f)
    })
}

/** MediaCodec runs on IO before each segment starts; failure gives a quiet, honest meter. */
internal suspend fun decodeEnvelope(context: Context, bytes: ByteArray): PcmEnvelope {
    val file = File.createTempFile("hud-envelope-", ".mp3", context.cacheDir)
    val extractor = MediaExtractor()
    var decoder: MediaCodec? = null
    try {
        file.writeBytes(bytes); extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: return PcmEnvelope.SILENT
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        decoder = codec; codec.configure(format, null, null, 0); codec.start()
        val info = MediaCodec.BufferInfo()
        val builder = PcmEnvelopeBuilder()
        var inputEnded = false
        var outputEnded = false
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (!outputEnded) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() > deadline) return PcmEnvelope.SILENT
            if (!inputEnded) {
                val input = codec.dequeueInputBuffer(1000)
                if (input >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(input))
                    val count = extractor.readSampleData(buffer, 0)
                    if (count < 0) {
                        codec.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                    } else {
                        codec.queueInputBuffer(input, 0, count, extractor.sampleTime, 0); extractor.advance()
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, 1000)
            if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val out = codec.outputFormat
                rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            } else if (output >= 0) {
                val buffer = codec.getOutputBuffer(output)?.order(ByteOrder.LITTLE_ENDIAN)
                val stride = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                if (encoding != AudioFormat.ENCODING_PCM_FLOAT && encoding != AudioFormat.ENCODING_PCM_16BIT) return PcmEnvelope.SILENT
                if (buffer != null && rate > 0 && channels > 0) {
                    for (sample in 0 until info.size / stride) {
                        val offset = info.offset + sample * stride
                        val value = if (stride == 4) buffer.getFloat(offset) else buffer.getShort(offset) / 32768f
                        builder.sample(info.presentationTimeUs + (sample / channels) * 1_000_000L / rate, value)
                    }
                }
                outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(output, false)
            }
        }
        return builder.build()
    } finally {
        runCatching { decoder?.stop() }; decoder?.release(); extractor.release(); file.delete()
    }
}
