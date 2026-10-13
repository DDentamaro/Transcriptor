package it.donato.trascrittore

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

data class ModelInfo(
    val id: String,
    val label: String,
    val fileName: String,
    val approxMb: Int,
    /** I modelli "turbo" non sono addestrati per tradurre: ignorerebbero l'opzione. */
    val canTranslate: Boolean = true
)

object Models {
    const val CUSTOM_ID = "custom"

    val list = listOf(
        ModelInfo("base", "Base: veloce, qualità discreta (~60 MB)", "ggml-base-q5_1.bin", 60),
        ModelInfo("small", "Small: consigliato (~190 MB)", "ggml-small-q5_1.bin", 190),
        ModelInfo("turbo", "Large v3 Turbo: il migliore, molto lento (~550 MB)", "ggml-large-v3-turbo-q5_0.bin", 550, canTranslate = false),
        ModelInfo(CUSTOM_ID, "Importa un tuo file .bin", "custom.bin", 0)
    )

    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").apply { mkdirs() }
    fun file(ctx: Context, m: ModelInfo): File = File(dir(ctx), m.fileName)
    fun isReady(ctx: Context, m: ModelInfo): Boolean = file(ctx, m).let { it.exists() && it.length() > 1_000_000 }

    /** Scarica il modello in background, riprendendo un download interrotto. */
    fun startDownload(ctx: Context, m: ModelInfo, fresh: Boolean = false) {
        if (AppState.downloadingModelId != null) return
        val app = ctx.applicationContext
        if (fresh) {
            file(app, m).delete()
            File(file(app, m).path + ".part").delete()
        }
        AppState.downloadingModelId = m.id
        AppState.downloadError = null
        AppState.downloadErrorModelId = null
        AppState.downloadBytes = 0
        AppState.downloadTotal = 0
        AppState.changed()
        Thread({
            try {
                download(app, m)
            } catch (e: Exception) {
                AppState.downloadError = e.message ?: e.toString()
                AppState.downloadErrorModelId = m.id
            } finally {
                AppState.downloadingModelId = null
                AppState.changed()
            }
        }, "download-modello").start()
    }

    private fun download(ctx: Context, m: ModelInfo) {
        val target = file(ctx, m)
        val part = File(target.path + ".part")
        var existing = if (part.exists()) part.length() else 0L

        val conn = URL(BASE_URL + m.fileName).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Trascrittore-Android")
        if (existing > 0) conn.setRequestProperty("Range", "bytes=$existing-")

        try {
            val code = conn.responseCode
            when (code) {
                416 -> { // gia completo
                    part.renameTo(target)
                    return
                }
                200 -> existing = 0 // il server non supporta la ripresa: ricomincia
                206 -> {}
                else -> throw Exception("Download fallito (HTTP $code)")
            }
            val remaining = conn.contentLengthLong
            val total = if (remaining > 0) existing + remaining else -1L
            AppState.downloadTotal = total
            AppState.downloadBytes = existing

            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(existing)
                raf.seek(existing)
                conn.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    var done = existing
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        AppState.downloadBytes = done
                        AppState.changed()
                    }
                    if (total > 0 && done != total) throw Exception("Download incompleto, riprova per riprendere")
                }
            }
            if (!hasGgmlMagic(part)) {
                part.delete()
                throw Exception("Il file scaricato non è un modello valido, riprova")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw Exception("Impossibile salvare il modello")
        } finally {
            conn.disconnect()
        }
    }

    /** I modelli ggml iniziano con il numero magico 0x67676d6c ("lmgg" su disco). */
    private fun hasGgmlMagic(f: File): Boolean = try {
        val b = ByteArray(4)
        f.inputStream().use { it.read(b) } == 4 &&
            b[0] == 'l'.code.toByte() && b[1] == 'm'.code.toByte() &&
            b[2] == 'g'.code.toByte() && b[3] == 'g'.code.toByte()
    } catch (_: Exception) {
        false
    }

    /** Copia un modello .bin scelto dall'utente nella cartella privata dell'app. */
    fun importCustom(ctx: Context, uri: Uri) {
        val target = file(ctx, list.first { it.id == CUSTOM_ID })
        val tmp = File(target.path + ".tmp")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out, 1 shl 16) }
        } ?: throw Exception("Impossibile leggere il file")
        if (tmp.length() < 1_000_000 || !hasGgmlMagic(tmp)) {
            tmp.delete()
            throw Exception("Il file non è un modello Whisper in formato ggml (.bin)")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw Exception("Impossibile salvare il modello")
    }
}
