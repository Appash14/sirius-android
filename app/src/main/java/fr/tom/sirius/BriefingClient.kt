package fr.tom.sirius

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val BRIEFING_UNAVAILABLE = "Le briefing n'est pas encore disponible"

internal class BriefingClient(private val http: OkHttpClient) {
    internal fun request(settings: ServerSettings): Request = Request.Builder()
        .url(normalizeAddress(settings.address).resolve("api/briefing")!!)
        .header("Authorization", Credentials.basic(settings.username, settings.password, Charsets.UTF_8))
        .header("Accept", "application/json").get().build()

    suspend fun fetch(settings: ServerSettings): String = fetch(request(settings))

    internal suspend fun fetch(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val result = runCatching {
                        if (it.code == 404) BRIEFING_UNAVAILABLE
                        else {
                            check(it.isSuccessful) { "Briefing HTTP ${it.code}" }
                            val body = it.body ?: error("Briefing vide")
                            // Never read an unbounded server body, even without Content-Length.
                            val source = body.source()
                            source.request(65_537)
                            val raw = source.buffer.readByteArray(minOf(source.buffer.size, 65_537L))
                            check(raw.size <= 65_536) { "Briefing trop long" }
                            val text = JSONObject(String(raw, Charsets.UTF_8)).getString("texte").trim()
                            check(text.isNotBlank() && text.length <= 16_000) { "Briefing invalide" }
                            text
                        }
                    }
                    if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                }
            }
        })
    }
}
