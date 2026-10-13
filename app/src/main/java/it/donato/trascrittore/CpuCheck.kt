package it.donato.trascrittore

import java.io.File

/**
 * La libreria nativa e compilata con istruzioni ARMv8.2 fp16 (se useFp16 = true).
 * Su telefoni piu vecchi andrebbe in crash: controlliamo prima di caricarla.
 */
object CpuCheck {
    fun nativeCompatible(): Boolean {
        if (!BuildConfig.NATIVE_FP16) return true
        return try {
            val features = File("/proc/cpuinfo").readLines()
                .filter { it.startsWith("Features", ignoreCase = true) }
                .flatMap { it.substringAfter(':').trim().split(Regex("\\s+")) }
                .toSet()
            // Se non riusciamo a leggere le feature, proviamo comunque.
            features.isEmpty() || ("asimdhp" in features && "fphp" in features)
        } catch (_: Exception) {
            true
        }
    }
}
