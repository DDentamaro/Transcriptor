package it.donato.trascrittore

/** Callback chiamate dal codice nativo durante la trascrizione (stesso thread). */
interface TranscribeListener {
    /** Avanzamento del blocco corrente, 0..100. */
    fun onProgress(progress: Int)

    /** Nuovo segmento: tempi in millisecondi relativi al blocco, testo in UTF-8. */
    fun onSegment(t0Ms: Long, t1Ms: Long, text: ByteArray)
}

object WhisperLib {
    init {
        System.loadLibrary("trascrittore")
    }

    const val RESULT_OK = 0
    const val RESULT_ABORTED = 1

    external fun nativeInit(modelPath: String): Long
    external fun nativeFree(ctx: Long)
    external fun nativeSetAbort(value: Boolean)
    external fun nativeSystemInfo(): String
    external fun nativeTranscribe(
        ctx: Long,
        samples: FloatArray,
        language: String,
        translate: Boolean,
        threads: Int,
        listener: TranscribeListener
    ): Int
}
