package it.donato.trascrittore

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Exporter {
    private const val FOLDER = "Trascrizioni"

    fun formatClock(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    private fun srtTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    /** Testo continuo, senza tempi: comodo da leggere o incollare altrove. */
    fun toPlainText(segments: List<Segment>): String {
        val sb = StringBuilder()
        for (s in segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
        }
        return sb.toString()
    }

    fun toTimestampedText(segments: List<Segment>): String =
        segments.filter { it.text.isNotBlank() }
            .joinToString("\n") { "[${formatClock(it.startMs)}] ${it.text.trim()}" }

    fun toSrt(segments: List<Segment>): String {
        val sb = StringBuilder()
        var i = 1
        for (s in segments) {
            val t = s.text.trim()
            if (t.isEmpty()) continue
            sb.append(i++).append('\n')
            sb.append(srtTime(s.startMs)).append(" --> ").append(srtTime(s.endMs)).append('\n')
            sb.append(t).append("\n\n")
        }
        return sb.toString()
    }

    fun baseName(videoName: String): String {
        val noExt = videoName.substringBeforeLast('.', videoName)
        val clean = noExt.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return clean.ifEmpty { "trascrizione" }
    }

    /**
     * Salva .txt (con tempi), .srt e testo semplice in Download/Trascrizioni.
     * Ritorna l'URI del .txt (per condividerlo) o null.
     */
    fun saveAll(ctx: Context, videoName: String, segments: List<Segment>): Uri? {
        val base = baseName(videoName)
        val txtUri = save(ctx, "$base.txt", "text/plain",
            toPlainText(segments) + "\n\n----- CON TEMPI -----\n\n" + toTimestampedText(segments) + "\n")
        save(ctx, "$base.srt", "application/x-subrip", toSrt(segments))
        return txtUri
    }

    fun locationLabel(ctx: Context): String =
        if (Build.VERSION.SDK_INT >= 29) "Download/$FOLDER"
        else ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.path ?: "memoria dell'app"

    private fun save(ctx: Context, name: String, mime: String, content: String): Uri? {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Impossibile creare $name")
            val os = ctx.contentResolver.openOutputStream(uri)
                ?: throw Exception("Impossibile scrivere $name")
            os.use { it.write(bytes) }
            uri
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: ctx.filesDir
            File(dir, name).writeBytes(bytes)
            null
        }
    }
}
