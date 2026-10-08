package fr.tom.sirius

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ServerSettings(val address: String = "", val username: String = "", val password: String = "") {
    val configured get() = address.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** Secrets never leave app-private storage; the AES key is held by Android Keystore. */
class Settings(context: Context) {
    @Volatile private var cachedServer: ServerSettings? = null
    private val prefs = context.getSharedPreferences("sirius", Context.MODE_PRIVATE)
    private val keyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }
    private val alias = "sirius.server.v1"
    val clientId: String = prefs.getString("client", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("client", it).commit()
    }
    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake", false)
        set(value) { prefs.edit().putBoolean("wake", value).commit() }
    var pilotEnabled: Boolean
        get() = prefs.getBoolean("pilot", false)
        set(value) { prefs.edit().putBoolean("pilot", value).commit() }
    /** Stop latch for the current request, cleared by the next accepted voice question. */
    var pilotStopped: Boolean
        get() = prefs.getBoolean("pilot_stopped", false)
        set(value) { prefs.edit().putBoolean("pilot_stopped", value).commit() }

    fun migratePilotSessionStop() {
        if (prefs.getBoolean("pilot_session_stop_v2", false)) return
        val editor = prefs.edit().putBoolean("pilot_session_stop_v2", true)
        if (pilotStopped) editor.putBoolean("pilot", true)
        editor.commit()
    }

    var pilotStopCode: String
        get() = prefs.getString("pilot_stop_code", null) ?: StopWords.DEFAULT_CODE
        set(value) {
            require(StopWords.validCode(value)) { "Choisis un code de deux à cinq mots." }
            prefs.edit().putString("pilot_stop_code", value.trim()).apply()
        }

    var wakeSensitivity: WakeSensitivity
        get() = WakeSensitivity.entries.firstOrNull { it.name == prefs.getString("wake_sensitivity", null) } ?: WakeSensitivity.MOYENNE
        set(value) { prefs.edit().putString("wake_sensitivity", value.name).apply() }

    /** Sirius 2.10: Tom's adjustment of the camera ring (dx, dy, radius) in dp, applied by EdgeGlow. */
    var ringTuning: FloatArray
        get() = floatArrayOf(prefs.getFloat("ring_dx", 0f), prefs.getFloat("ring_dy", 0f), prefs.getFloat("ring_dr", 0f))
        set(value) {
            prefs.edit().putFloat("ring_dx", value[0]).putFloat("ring_dy", value[1]).putFloat("ring_dr", value[2]).apply()
            RingTuning.set(value[0], value[1], value[2])
        }
    init { ringTuning.let { RingTuning.set(it[0], it[1], it[2]) } }

    var siriusVolume: Float
        get() = prefs.getFloat("sirius_volume", .6f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else .6f }
        set(value) { prefs.edit().putFloat("sirius_volume", if (value.isFinite()) value.coerceIn(0f, 1f) else .6f).apply() }

    // Only action names, outcomes and timestamps are stored, never command arguments or screen content.
    fun readActionLog(): String = prefs.getString("action_log", "[]") ?: "[]"
    fun saveActionLog(json: String) { prefs.edit().putString("action_log", json).apply() }

    @Synchronized private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized fun read(): ServerSettings {
        cachedServer?.let { return it }
        val blob = prefs.getString("server", null) ?: return ServerSettings().also { cachedServer = it }
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            require(raw.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
            ServerSettings(json.getString("address"), json.getString("username"), json.getString("password"))
        } catch (_: Exception) { ServerSettings() }.also { cachedServer = it }
    }

    @Synchronized fun save(value: ServerSettings) {
        val normalized = normalizeAddress(value.address)
        require(value.username.isNotBlank() && ':' !in value.username && '\n' !in value.username && '\r' !in value.username) { "Vérifie ton identifiant." }
        require(value.password.isNotBlank()) { "Entre ton mot de passe." }
        val json = JSONObject().put("address", normalized.toString()).put("username", value.username).put("password", value.password)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val blob = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("server", Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) { "Réglages non enregistrés." }
        cachedServer = value.copy(address = normalized.toString())
    }
}

fun normalizeAddress(input: String): HttpUrl {
    val url = input.trim().toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "Entre une adresse HTTPS sans identifiant ni paramètres."
    }
    val path = url.encodedPath.trimEnd('/').ifEmpty { "/voix" }
    return url.newBuilder().encodedPath("$path/").build()
}
