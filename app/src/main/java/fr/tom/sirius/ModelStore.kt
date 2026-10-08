package fr.tom.sirius

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class ModelStore(context: Context) {
    private val root = File(context.filesDir, "vosk")
    val directory = File(root, NAME)
    fun installed() = File(directory, "am/final.mdl").isFile && File(directory, "conf/model.conf").isFile
    suspend fun install(progress: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (installed()) return@withContext
        val staging = File(root, "download")
        staging.deleteRecursively(); check(staging.mkdirs())
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.MINUTES).build()
        try {
            progress("Téléchargement du modèle français...")
            client.newCall(Request.Builder().url("https://alphacephei.com/vosk/models/$NAME.zip").build()).execute().use { response ->
                check(response.isSuccessful) { "Téléchargement indisponible. Réessaie plus tard." }
                val body = response.body ?: error("Modèle vide.")
                require(body.contentLength() <= 100_000_000) { "Archive trop grande." }
                var total = 0L
                var entries = 0
                ZipInputStream(body.byteStream()).use { zip ->
                    var entry = zip.nextEntry
                    val buffer = ByteArray(32768)
                    while (entry != null) {
                        check(++entries <= 1000) { "Archive invalide." }
                        val output = File(staging, entry.name).canonicalFile
                        require(output.path.startsWith(staging.canonicalPath + File.separator)) { "Archive invalide." }
                        if (entry.isDirectory) output.mkdirs() else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { stream ->
                                var n = zip.read(buffer)
                                while (n != -1) {
                                    total += n
                                    require(total <= 300_000_000) { "Modèle trop grand." }
                                    stream.write(buffer, 0, n); n = zip.read(buffer)
                                }
                            }
                        }
                        zip.closeEntry(); entry = zip.nextEntry
                    }
                }
            }
            val unpacked = File(staging, NAME)
            check(File(unpacked, "am/final.mdl").isFile && File(unpacked, "conf/model.conf").isFile) { "Modèle incomplet." }
            directory.deleteRecursively()
            check(unpacked.renameTo(directory)) { "Impossible de garder le modèle." }
            progress("Modèle français prêt")
        } finally {
            staging.deleteRecursively()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
    companion object { const val NAME = "vosk-model-small-fr-0.22" }
}
