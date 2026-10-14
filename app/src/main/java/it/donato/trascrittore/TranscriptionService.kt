package it.donato.trascrittore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Servizio in primo piano: continua a trascrivere anche a schermo spento
 * o con l'app in background. L'audio viene elaborato a blocchi di 5 minuti,
 * tagliati nel punto piu silenzioso per non spezzare le parole.
 */
class TranscriptionService : Service() {

    companion object {
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_LANG = "lang"
        private const val EXTRA_TRANSLATE = "translate"
        private const val ACTION_CANCEL = "it.donato.trascrittore.CANCEL"
        private const val CHANNEL_ID = "trascrizione"
        private const val NOTIF_RUNNING = 1
        private const val NOTIF_DONE = 2

        private const val RATE = AudioDecoder.TARGET_RATE
        private const val CHUNK_SAMPLES = 300L * RATE      // 5 minuti
        private const val SEARCH_SAMPLES = 10L * RATE      // cerca il silenzio negli ultimi 10 s
        private const val FRAME = 400                      // 25 ms
        private const val PROMPT_CHARS = 200               // contesto passato al blocco successivo
        private const val ACTION_BENCH = "it.donato.trascrittore.BENCH"
        private const val EXTRA_BENCH_LABELS = "benchLabels"
        private const val EXTRA_BENCH_PATHS = "benchPaths"
        private const val BENCH_SAMPLES = 60L * RATE       // la prova usa il primo minuto
        const val PREF_THREADS = "threads"

        @Volatile private var cancelRequested = false

        fun start(ctx: Context, uri: Uri, name: String, modelPath: String, lang: String, translate: Boolean) {
            val i = Intent(ctx, TranscriptionService::class.java).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_MODEL, modelPath)
                putExtra(EXTRA_LANG, lang)
                putExtra(EXTRA_TRANSLATE, translate)
            }
            ctx.startForegroundService(i)
        }

        /** Prova velocita: trascrive il primo minuto con ogni modello e numero di thread. */
        fun startBenchmark(ctx: Context, uri: Uri, lang: String, models: List<ModelInfo>) {
            val i = Intent(ctx, TranscriptionService::class.java).apply {
                action = ACTION_BENCH
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(EXTRA_LANG, lang)
                putExtra(EXTRA_BENCH_LABELS, models.map { it.id }.toTypedArray())
                putExtra(EXTRA_BENCH_PATHS, models.map { Models.file(ctx, it).path }.toTypedArray())
            }
            ctx.startForegroundService(i)
        }

        fun cancel() {
            cancelRequested = true
            WhisperLib.nativeSetAbort(true)
        }
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifiedPct = -1
    private var lastNotifiedPhase: Phase? = null
    private var previousText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (worker?.isAlive == true) cancel() else stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY

        val uri = intent?.data
        val bench = intent?.action == ACTION_BENCH
        val modelPath = if (bench) "" else intent?.getStringExtra(EXTRA_MODEL)
        createChannel()
        startForegroundCompat(buildNotification("Preparazione…", 0, true))
        if (uri == null || modelPath == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val name = if (bench) "prova-velocita" else intent.getStringExtra(EXTRA_NAME) ?: "video"
        val lang = intent.getStringExtra(EXTRA_LANG) ?: "auto"
        val translate = intent.getBooleanExtra(EXTRA_TRANSLATE, false)

        cancelRequested = false
        previousText = null
        WhisperLib.nativeSetAbort(false)
        AppState.resetForNewJob(name)
        AppState.benchmark = bench
        AppState.phase = Phase.DECODING
        AppState.changed()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trascrittore:job").apply {
            acquire(12 * 60 * 60 * 1000L)
        }

        worker = if (bench) {
            val labels = intent.getStringArrayExtra(EXTRA_BENCH_LABELS).orEmpty()
            val paths = intent.getStringArrayExtra(EXTRA_BENCH_PATHS).orEmpty()
            Thread({ runBenchmark(uri, lang, labels.zip(paths)) }, "prova-velocita")
        } else {
            Thread({ runJob(uri, name, modelPath, lang, translate) }, "trascrizione")
        }.apply { start() }
        return START_NOT_STICKY
    }

    private fun runJob(uri: Uri, name: String, modelPath: String, lang: String, translate: Boolean) {
        val pcm = File(cacheDir, "audio.pcm")
        try {
            // 1) Estrazione audio
            setStatus(Phase.DECODING, 0f, "Estrazione audio dal video…")
            val total = AudioDecoder.decodeToPcm(this, uri, pcm,
                onProgress = { f -> setStatus(Phase.DECODING, f, "Estrazione audio… ${(f * 100).toInt()}%") },
                isCancelled = { cancelRequested })
            if (total < RATE) throw Exception("Audio troppo corto o assente")
            val durationLabel = Exporter.formatClock(total * 1000 / RATE)

            // 2) Caricamento modello
            setStatus(Phase.LOADING_MODEL, 0f, "Caricamento modello…")
            if (!WhisperLib.nativeLoadBackends(applicationInfo.nativeLibraryDir)) {
                throw Exception("Processore non supportato")
            }
            val ctx = WhisperLib.nativeInit(modelPath)
            if (ctx == 0L) throw Exception("Modello non valido o danneggiato: riscaricalo")

            // 3) Trascrizione a blocchi
            val threads = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .getInt(PREF_THREADS, CpuInfo.defaultThreads())
            val started = SystemClock.elapsedRealtime()
            // Con "auto" la lingua si rileva sul primo blocco e poi resta fissa:
            // rilevarla a ogni blocco la farebbe cambiare a meta video.
            var language = lang
            var languageLabel = ""
            // Coda del testo gia trascritto: tiene il filo tra un blocco e l'altro.
            val tail = StringBuilder()
            try {
                RandomAccessFile(pcm, "r").use { raf ->
                    var start = 0L
                    while (start < total) {
                        if (cancelRequested) throw CancelledException()
                        var end = minOf(start + CHUNK_SAMPLES, total)
                        if (end < total) end = quietestCut(raf, end - SEARCH_SAMPLES, end)
                        val samples = readFloats(raf, start, (end - start).toInt())
                        val offsetMs = start * 1000 / RATE
                        val chunkStart = start
                        val chunkLen = end - start

                        val listener = object : TranscribeListener {
                            override fun onProgress(progress: Int) {
                                val doneSamples = chunkStart + chunkLen * progress / 100
                                val frac = doneSamples.toFloat() / total
                                val elapsed = SystemClock.elapsedRealtime() - started
                                val eta = if (frac > 0.02f) ((elapsed / frac) * (1 - frac)).toLong() else -1L
                                val etaLabel = if (eta >= 0) " · mancano ~${Exporter.formatClock(eta)}" else ""
                                setStatus(Phase.TRANSCRIBING, frac,
                                    "Trascrizione ${(frac * 100).toInt()}% di $durationLabel$languageLabel$etaLabel")
                            }

                            override fun onSegment(t0Ms: Long, t1Ms: Long, text: ByteArray) {
                                val s = String(text, Charsets.UTF_8)
                                if (TextFilter.isHallucination(s, previousText)) return
                                previousText = s
                                AppState.addSegment(Segment(offsetMs + t0Ms, offsetMs + t1Ms, s))
                                AppState.changed()
                                if (tail.isNotEmpty()) tail.append(' ')
                                tail.append(s.trim())
                                if (tail.length > PROMPT_CHARS) {
                                    tail.delete(0, tail.length - PROMPT_CHARS)
                                    tail.delete(0, tail.indexOf(" ") + 1) // niente parole tagliate a meta
                                }
                            }
                        }
                        val prompt = tail.toString()
                        listener.onProgress(0)
                        val rc = WhisperLib.nativeTranscribe(ctx, samples, language, prompt, translate, threads, listener)
                        if (rc == WhisperLib.RESULT_ABORTED || cancelRequested) throw CancelledException()
                        if (rc != WhisperLib.RESULT_OK) throw Exception("Errore durante la trascrizione (codice $rc)")
                        if (language == "auto") {
                            WhisperLib.nativeDetectedLanguage(ctx).takeIf { it.isNotEmpty() }?.let {
                                language = it
                                languageLabel = " · lingua: $it"
                            }
                        }
                        start = end
                    }
                }
            } finally {
                WhisperLib.nativeFree(ctx)
            }

            val elapsedMs = (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)
            val speed = (total * 1000.0 / RATE) / elapsedMs
            saveResults(name)
            val where = if (AppState.savedLocation.isNotEmpty()) " File salvati in ${AppState.savedLocation}"
                        else " Salvataggio file non riuscito: usa Copia o Condividi."
            setStatus(Phase.DONE, 1f,
                "Completata in ${Exporter.formatClock(elapsedMs)} (${"%.1f".format(speed)}× il tempo reale, $threads thread).$where")
            notifyFinished("Trascrizione completata", name)
        } catch (e: CancelledException) {
            if (AppState.segmentCount() > 0) saveResults(name)
            val where = if (AppState.savedLocation.isNotEmpty()) " La parte trascritta è salvata in ${AppState.savedLocation}" else ""
            setStatus(Phase.CANCELLED, AppState.progress, "Annullata.$where")
        } catch (e: Throwable) {
            if (AppState.segmentCount() > 0) saveResults(name)
            setStatus(Phase.ERROR, AppState.progress, "Errore: ${e.message ?: e.toString()}")
            notifyFinished("Trascrizione non riuscita", e.message ?: "")
        } finally {
            pcm.delete()
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun runBenchmark(uri: Uri, lang: String, models: List<Pair<String, String>>) {
        val pcm = File(cacheDir, "bench.pcm")
        val report = StringBuilder()
        fun line(s: String) {
            report.append(s).append('\n')
            AppState.report = report.toString()
            AppState.changed()
        }
        try {
            setStatus(Phase.DECODING, 0f, "Prova velocità: estrazione del primo minuto…")
            val total = AudioDecoder.decodeToPcm(this, uri, pcm,
                onProgress = {}, isCancelled = { cancelRequested }, maxSamples = BENCH_SAMPLES)
            if (total < 10L * RATE) throw Exception("Il video deve durare almeno 10 secondi")
            val samples = RandomAccessFile(pcm, "r").use { readFloats(it, 0, total.toInt()) }
            val audioSec = total.toDouble() / RATE

            if (!WhisperLib.nativeLoadBackends(applicationInfo.nativeLibraryDir)) {
                throw Exception("Processore non supportato")
            }
            line("PROVA VELOCITÀ · ${"%.0f".format(audioSec)} s di audio")
            line("Processore: ${CpuInfo.describe()}")
            line("Motore: ${WhisperLib.nativeSystemInfo().substringAfter("CPU :").trim().trimEnd('|').trim()}")
            line("Velocità = secondi di audio trascritti per secondo di calcolo (più alto è meglio).")
            line("")

            val threadOptions = CpuInfo.threadCandidates()
            val noop = object : TranscribeListener {
                override fun onProgress(progress: Int) {}
                override fun onSegment(t0Ms: Long, t1Ms: Long, text: ByteArray) {}
            }
            // score[t] = somma delle velocita relative (al migliore di ogni modello)
            val score = HashMap<Int, Double>()
            val runs = models.size * threadOptions.size
            var done = 0
            for ((label, path) in models) {
                setStatus(Phase.LOADING_MODEL, done.toFloat() / runs, "Prova velocità: caricamento $label…")
                val loadStart = SystemClock.elapsedRealtime()
                val ctx = WhisperLib.nativeInit(path)
                if (ctx == 0L) {
                    line("$label: modello non caricabile")
                    done += threadOptions.size
                    continue
                }
                line("$label (caricamento ${"%.1f".format((SystemClock.elapsedRealtime() - loadStart) / 1000.0)} s)")
                val speeds = HashMap<Int, Double>()
                try {
                    for (t in threadOptions) {
                        if (cancelRequested) throw CancelledException()
                        setStatus(Phase.TRANSCRIBING, done.toFloat() / runs, "Prova velocità: $label, $t thread…")
                        val t0 = SystemClock.elapsedRealtime()
                        val rc = WhisperLib.nativeTranscribe(ctx, samples, lang, "", false, t, noop)
                        if (rc == WhisperLib.RESULT_ABORTED || cancelRequested) throw CancelledException()
                        val sec = (SystemClock.elapsedRealtime() - t0).coerceAtLeast(1) / 1000.0
                        if (rc == WhisperLib.RESULT_OK) {
                            speeds[t] = audioSec / sec
                            line("  $t thread: ${"%.1f".format(audioSec / sec)}× (${"%.1f".format(sec)} s)")
                        } else {
                            line("  $t thread: errore $rc")
                        }
                        done++
                    }
                } finally {
                    WhisperLib.nativeFree(ctx)
                }
                val best = speeds.values.maxOrNull() ?: continue
                for ((t, s) in speeds) score[t] = (score[t] ?: 0.0) + s / best
                val minutesPerHour = 60.0 / best
                line("  → un'ora di audio richiede circa ${"%.0f".format(minutesPerHour)} min")
                line("")
            }

            val bestThreads = score.maxByOrNull { it.value }?.key
            if (bestThreads != null) {
                getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                    .putInt(PREF_THREADS, bestThreads).apply()
                line("Impostazione scelta: $bestThreads thread (salvata per le prossime trascrizioni).")
            }
            line("Per risultati affidabili: telefono freddo, in carica, schermo acceso.")
            setStatus(Phase.DONE, 1f, "Prova velocità completata.")
        } catch (e: CancelledException) {
            setStatus(Phase.CANCELLED, AppState.progress, "Prova velocità annullata.")
        } catch (e: Throwable) {
            setStatus(Phase.ERROR, AppState.progress, "Errore: ${e.message ?: e.toString()}")
        } finally {
            pcm.delete()
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun saveResults(name: String) {
        try {
            AppState.savedTxtUri = Exporter.saveAll(this, name, AppState.allSegments())
            AppState.savedLocation = Exporter.locationLabel(this)
        } catch (e: Exception) {
            AppState.savedLocation = ""
        }
    }

    /** Legge n campioni PCM16 a partire da `from` e li converte in float -1..1. */
    private fun readFloats(raf: RandomAccessFile, from: Long, n: Int): FloatArray {
        val bytes = ByteArray(n * 2)
        raf.seek(from * 2)
        raf.readFully(bytes)
        val sb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(n) { sb.get(it) / 32768f }
    }

    /** Trova il frame da 25 ms con meno energia nell'intervallo e ritorna il punto di taglio. */
    private fun quietestCut(raf: RandomAccessFile, from: Long, to: Long): Long {
        val n = (to - from).toInt()
        val samples = readFloats(raf, from, n)
        var bestFrame = n / FRAME - 1
        var bestEnergy = Double.MAX_VALUE
        var f = 0
        while ((f + 1) * FRAME <= n) {
            var e = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) e += samples[i] * samples[i]
            if (e < bestEnergy) {
                bestEnergy = e
                bestFrame = f
            }
            f++
        }
        return from + bestFrame.toLong() * FRAME + FRAME / 2
    }

    // ---------- notifiche ----------

    private fun setStatus(phase: Phase, progress: Float, detail: String) {
        AppState.phase = phase
        AppState.progress = progress
        AppState.detail = detail
        AppState.changed()
        val pct = (progress * 100).toInt()
        val active = phase != Phase.DONE && phase != Phase.ERROR && phase != Phase.CANCELLED
        if (active && (pct != lastNotifiedPct || phase != lastNotifiedPhase)) {
            lastNotifiedPct = pct
            lastNotifiedPhase = phase
            val nm = getSystemService(NotificationManager::class.java)!!
            nm.notify(NOTIF_RUNNING, buildNotification(detail, pct, phase == Phase.LOADING_MODEL))
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)!!
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Trascrizione", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(text: String, pct: Int, indeterminate: Boolean): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Trascrittore")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "Annulla", cancelIntent).build())
            .build()
    }

    private fun notifyFinished(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        getSystemService(NotificationManager::class.java)!!.notify(NOTIF_DONE, n)
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_RUNNING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_RUNNING, n)
        }
    }
}
