package fr.tom.sirius

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal data class AudioSentenceKey(val id: String, val epoch: Int, val index: Int)

/** Matches serveur.py: multipart WAV in, JSON/base64 MP3 over WS out. */
class VoiceProtocol(private val base: HttpUrl, settings: ServerSettings, private val client: String) {
    private val auth = Credentials.basic(settings.username, settings.password, Charsets.UTF_8)
    fun socketRequest(): Request = Request.Builder()
        .url(base.resolve("ws")!!.newBuilder().addQueryParameter("client", client).build())
        .header("Authorization", auth).build()

    fun phraseRequest(wav: ByteArray, start: Double, end: Double, locked: Boolean, eveil: Boolean = false): Request {
        // Current server rejects unknown multipart fields. Query/header preserve compatibility.
        val url = base.resolve("api/phrase")!!.newBuilder()
            .addQueryParameter("verrouille", locked.toString()).apply { if (eveil) addQueryParameter("eveil", "true") }.build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "phrase.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("client", client)
            .addFormDataPart("start", start.toString())
            .addFormDataPart("end", end.toString()).build()
        return Request.Builder().url(url).header("Authorization", auth)
            .header("X-Sirius-Verrouille", locked.toString()).apply { if (eveil) header("X-Sirius-Eveil", "true") }.post(body).build()
    }

    /**
     * One piece of a live utterance (server capability "partiels"): same route and lock context as a phrase,
     * plus enonce, seq, fin and parole. The last piece may carry no audio when it is only silence.
     */
    fun chunkRequest(wav: ByteArray?, enonce: String, seq: Int, last: Boolean, voicedMs: Int,
                     start: Double, end: Double, locked: Boolean, eveil: Boolean = false): Request {
        val url = base.resolve("api/phrase")!!.newBuilder()
            .addQueryParameter("verrouille", locked.toString()).apply { if (eveil) addQueryParameter("eveil", "true") }.build()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            if (wav != null) addFormDataPart("audio", "bout.wav", wav.toRequestBody("audio/wav".toMediaType()))
        }
            .addFormDataPart("client", client)
            .addFormDataPart("start", start.toString())
            .addFormDataPart("end", end.toString())
            .addFormDataPart("enonce", enonce)
            .addFormDataPart("seq", seq.toString())
            .addFormDataPart("fin", if (last) "1" else "0")
            .addFormDataPart("parole", voicedMs.coerceIn(0, 65000).toString()).build()
        return Request.Builder().url(url).header("Authorization", auth)
            .header("X-Sirius-Verrouille", locked.toString()).apply { if (eveil) header("X-Sirius-Eveil", "true") }.post(body).build()
    }
    companion object {
        fun hello(stopCode: String) = JSONObject().put("type", "hello").put("code_arret", stopCode).toString()
        /** Utterance id for live pieces: 16 hex characters, accepted by the server ([A-Za-z0-9_-]{4,40}). */
        fun newUtteranceId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        fun ready(value: Boolean) = JSONObject().put("type", "ready").put("ready", value).toString()
        internal fun audioAck(key: AudioSentenceKey) = JSONObject().put("type", "audio_ack")
            .put("id", key.id).put("epoch", key.epoch).put("index", key.index).toString()
        /** eveil=true: the utterance starting now is the first one after a wake word (server may reject it). */
        fun speech(value: Boolean, eveil: Boolean = false) = JSONObject().put("type", "speech").put("active", value)
            .apply { if (eveil) put("eveil", true) }.toString()
        fun interrupt() = JSONObject().put("type", "interrupt").toString()
        /** raison=arrete_par_tom tells the server why it must refuse; the phone refuses on its own anyway. */
        fun pilotage(enabled: Boolean, locked: Boolean, stopped: Boolean = false) = JSONObject().put("type", "pilotage")
            .put("enabled", enabled).put("verrouille", locked)
            .apply { if (!enabled && stopped) put("raison", STOPPED_BY_TOM) }.toString()
    }
}

/** Re-announces pilotage whenever the switch or the keyguard differs from what the current socket last told the server. */
class PilotAnnouncer {
    private var announced: Triple<Boolean, Boolean, Boolean>? = null
    fun reset() { announced = null }
    fun frame(enabled: Boolean, locked: Boolean, force: Boolean = false, stopped: Boolean = false): String? {
        val value = Triple(enabled, locked, stopped)
        if (!force && announced == value) return null
        announced = value
        return VoiceProtocol.pilotage(enabled, locked, stopped)
    }
}
