package it.donato.trascrittore

import java.io.File

/**
 * I processori dei telefoni hanno core "grandi" e core "piccoli" (big.LITTLE).
 * Whisper divide il lavoro in parti uguali tra i thread: un thread su un core
 * piccolo rallenta tutti gli altri. Conviene usare solo i core piu veloci.
 */
object CpuInfo {
    private val cores = Runtime.getRuntime().availableProcessors()

    /** Frequenza massima di ogni core (kHz), o null se il sistema non la espone. */
    private fun maxFreqs(): List<Long>? = try {
        (0 until cores).map {
            File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
        }
    } catch (_: Exception) {
        null
    }

    /** Core esclusi quelli del gruppo piu lento (es. Pixel 10: 1 X4 + 5 A725 = 6). */
    fun performanceCores(): Int {
        val f = maxFreqs()
        if (f == null || f.toSet().size < 2) return (cores - 1).coerceIn(1, 4)
        val slowest = f.min()
        return f.count { it > slowest }.coerceAtLeast(1)
    }

    /** Numero di thread se la prova velocita non e ancora stata fatta. */
    fun defaultThreads(): Int = performanceCores().coerceIn(1, 8)

    /** Configurazioni confrontate dalla prova velocita. */
    fun threadCandidates(): List<Int> =
        listOf(4, performanceCores(), cores).filter { it in 1..cores }.distinct().sorted()

    fun describe(): String {
        val f = maxFreqs()?.joinToString(" ") { "%.1f".format(it / 1_000_000.0) }
        return "$cores core" + (if (f != null) " (GHz: $f)" else "") + ", core veloci: ${performanceCores()}"
    }
}
