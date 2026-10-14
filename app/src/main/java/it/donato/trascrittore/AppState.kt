package it.donato.trascrittore

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

data class Segment(val startMs: Long, val endMs: Long, val text: String)

enum class Phase { IDLE, DECODING, LOADING_MODEL, TRANSCRIBING, DONE, CANCELLED, ERROR }

/** Stato condiviso tra servizio e interfaccia (stesso processo). */
object AppState {
    // --- trascrizione ---
    @Volatile var phase = Phase.IDLE
    @Volatile var progress = 0f          // avanzamento della fase corrente, 0..1
    @Volatile var detail = ""            // riga di stato leggibile
    @Volatile var fileName = ""
    @Volatile var savedLocation = ""     // dove sono stati salvati i file
    @Volatile var savedTxtUri: Uri? = null
    @Volatile var benchmark = false      // true durante e dopo una prova velocita
    @Volatile var report = ""            // risultati della prova velocita
    private val segmentList = ArrayList<Segment>()

    // --- download modello ---
    @Volatile var downloadingModelId: String? = null
    @Volatile var downloadBytes = 0L
    @Volatile var downloadTotal = 0L
    @Volatile var downloadError: String? = null
    @Volatile var downloadErrorModelId: String? = null

    val isRunning: Boolean
        get() = phase == Phase.DECODING || phase == Phase.LOADING_MODEL || phase == Phase.TRANSCRIBING

    fun resetForNewJob(name: String) {
        synchronized(segmentList) { segmentList.clear() }
        fileName = name
        progress = 0f
        detail = ""
        savedLocation = ""
        savedTxtUri = null
        benchmark = false
        report = ""
    }

    fun addSegment(s: Segment) {
        synchronized(segmentList) { segmentList.add(s) }
    }

    fun segmentCount(): Int = synchronized(segmentList) { segmentList.size }

    fun segmentsFrom(index: Int): List<Segment> = synchronized(segmentList) {
        if (index >= segmentList.size) emptyList() else ArrayList(segmentList.subList(index, segmentList.size))
    }

    fun allSegments(): List<Segment> = segmentsFrom(0)

    // --- notifiche all'interfaccia ---
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var pending = false

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** Accorpa gli aggiornamenti: al massimo uno ogni ~100 ms. */
    fun changed() {
        if (pending) return
        pending = true
        main.postDelayed({
            pending = false
            listeners.forEach { it() }
        }, 100)
    }
}
