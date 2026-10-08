package fr.tom.sirius

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale

/** Synthesizes French in private temporary files, then uses the existing media player and gain. */
internal class BriefingSpeech(private val context: Context) {
    private val initialized = CompletableDeferred<Int>()
    private val tts = TextToSpeech(context) { initialized.complete(it) }

    suspend fun synthesize(text: String): ByteArray = withTimeout(30_000) {
        check(initialized.await() == TextToSpeech.SUCCESS) { "Synthèse indisponible" }
        check(tts.setLanguage(Locale.FRANCE) >= TextToSpeech.LANG_AVAILABLE) { "Voix française indisponible" }
        val done = CompletableDeferred<Unit>()
        val file = File.createTempFile("briefing-", ".wav", context.cacheDir)
        val id = file.name
        try {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id) done.complete(Unit) }
                @Deprecated("Required by the Android TTS listener")
                override fun onError(utteranceId: String?) { if (utteranceId == id) done.completeExceptionally(IOExceptionForSpeech()) }
            })
            check(tts.synthesizeToFile(text, null, file, id) == TextToSpeech.SUCCESS) { "Synthèse refusée" }
            done.await()
            withContext(Dispatchers.IO) {
                check(file.length() in 1..12_000_000) { "Audio invalide" }
                file.readBytes()
            }
        } finally { file.delete() }
    }

    fun close() { tts.stop(); tts.shutdown() }
    private class IOExceptionForSpeech : java.io.IOException("Synthèse impossible")
}

internal fun briefingPieces(text: String): List<String> {
    val result = mutableListOf<String>()
    var remaining = text.trim()
    while (remaining.isNotEmpty()) {
        var end = minOf(remaining.length, 3000)
        if (end < remaining.length) {
            val boundary = remaining.lastIndexOf(' ', end - 1)
            if (boundary > 0) end = boundary
        }
        result += remaining.take(end)
        remaining = remaining.drop(end).trimStart()
    }
    return result
}
