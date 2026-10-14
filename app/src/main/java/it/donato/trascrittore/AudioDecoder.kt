package it.donato.trascrittore

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteOrder

class CancelledException : Exception("Annullato")

/**
 * Estrae la traccia audio da un video (o file audio) e la scrive su disco come
 * PCM 16 bit mono a 16 kHz, il formato richiesto da Whisper.
 * Lavora a flusso: anche un video di ore non viene mai caricato tutto in memoria.
 */
object AudioDecoder {
    const val TARGET_RATE = 16_000
    private const val TIMEOUT_US = 10_000L

    /** Ritorna il numero di campioni scritti. */
    fun decodeToPcm(
        context: Context,
        uri: Uri,
        out: File,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        maxSamples: Long = Long.MAX_VALUE
    ): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) throw Exception("Il file non contiene una traccia audio")
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

            val dec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw Exception("Formato audio non supportato dal telefono ($mime)")
            }
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmFloat = false
            val writer = Pcm16Writer(FileOutputStream(out))
            var resampler = Resampler(format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100), writer)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReported = -1
            var idleAfterInput = 0

            writer.use {
                while (!outputDone) {
                    if (isCancelled()) throw CancelledException()

                    if (!inputDone) {
                        val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val buf = dec.getInputBuffer(inIdx)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // Alcuni decoder non segnalano mai la fine: dopo ~3 s di silenzio chiudiamo.
                        if (inputDone && ++idleAfterInput > 300) outputDone = true
                    } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = dec.outputFormat
                        channels = of.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                        pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        val rate = of.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, resampler.sourceRate)
                        if (rate != resampler.sourceRate) {
                            resampler.flush()
                            resampler = Resampler(rate, writer)
                        }
                    } else if (outIdx >= 0) {
                        idleAfterInput = 0
                        if (info.size > 0) {
                            val ob = dec.getOutputBuffer(outIdx)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.order(ByteOrder.nativeOrder())
                            if (pcmFloat) {
                                val fb = ob.asFloatBuffer()
                                val frames = fb.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += fb.get()
                                    resampler.push(sum / channels)
                                }
                            } else {
                                val sb = ob.asShortBuffer()
                                val frames = sb.remaining() / channels
                                for (f in 0 until frames) {
                                    var sum = 0f
                                    for (c in 0 until channels) sum += sb.get()
                                    resampler.push(sum / (channels * 32768f))
                                }
                            }
                        }
                        dec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (writer.samplesWritten >= maxSamples) outputDone = true

                        if (durationUs > 0 && info.size > 0) {
                            val pct = ((info.presentationTimeUs * 100) / durationUs).toInt().coerceIn(0, 100)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct / 100f)
                            }
                        }
                    }
                }
                resampler.flush()
            }
            return writer.samplesWritten
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun MediaFormat.getIntegerOr(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}

/**
 * Ricampionamento in streaming verso 16 kHz.
 * Verso il basso: media dei campioni che cadono in ogni intervallo di uscita
 * (fa anche da filtro anti-aliasing). Verso l'alto: interpolazione lineare.
 */
private class Resampler(val sourceRate: Int, private val writer: Pcm16Writer) {
    private val step = sourceRate.toDouble() / AudioDecoder.TARGET_RATE
    private var inIndex = 0L      // indice del prossimo campione in ingresso
    private var outIndex = 0L     // indice del prossimo campione in uscita
    private var acc = 0.0
    private var accN = 0
    private var prev = 0f

    fun push(x: Float) {
        if (step >= 1.0) {
            acc += x
            accN++
            inIndex++
            if (inIndex >= (outIndex + 1) * step) {
                writer.write((acc / accN).toFloat())
                outIndex++
                acc = 0.0
                accN = 0
            }
        } else {
            if (inIndex == 0L) prev = x
            while (outIndex * step <= inIndex) {
                val t = outIndex * step
                val frac = (t - (inIndex - 1)).toFloat().coerceIn(0f, 1f)
                writer.write(prev + (x - prev) * frac)
                outIndex++
            }
            prev = x
            inIndex++
        }
    }

    fun flush() {
        if (step >= 1.0 && accN > 0) {
            writer.write((acc / accN).toFloat())
            outIndex++
            acc = 0.0
            accN = 0
        }
    }
}

/** Scrive campioni float come PCM 16 bit little-endian con un buffer proprio (molto piu veloce). */
private class Pcm16Writer(private val os: OutputStream) : AutoCloseable {
    private val buf = ByteArray(1 shl 16)
    private var pos = 0
    var samplesWritten = 0L
        private set

    fun write(v: Float) {
        val s = (v * 32767f).toInt().coerceIn(-32768, 32767)
        buf[pos++] = (s and 0xff).toByte()
        buf[pos++] = ((s shr 8) and 0xff).toByte()
        samplesWritten++
        if (pos == buf.size) {
            os.write(buf, 0, pos)
            pos = 0
        }
    }

    override fun close() {
        if (pos > 0) os.write(buf, 0, pos)
        pos = 0
        os.close()
    }
}
