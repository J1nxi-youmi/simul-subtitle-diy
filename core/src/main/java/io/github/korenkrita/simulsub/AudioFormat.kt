package io.github.korenkrita.simulsub

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How captured samples become the bytes of a recording (see [AudioRecorder]).
 *
 * The recorder owns the file and the `*.part` → complete rename; a format only says what to write:
 * a header reserved at the front (rewritten on close with the real length, or nothing for a stream whose
 * frames carry their own header), the encoded bytes of each captured frame, and whatever the encoder still
 * holds when the recording is finished.
 *
 * [encode] and [finish] are called on the recorder's writer thread, one frame at a time; the same thread
 * closes the encoder, so an encoder that owns a native handle needs no locking of its own.
 */
interface AudioFormat {
    /** file extension without the dot: `wav`, `aac` */
    val extension: String

    /** bytes reserved at the front, rewritten by [header] on close; 0 when the stream needs no header */
    val headerSize: Int

    /** encoded bytes for one captured frame; empty while the encoder is still buffering */
    fun encode(samples: FloatArray): ByteArray

    /** everything the encoder still holds; null when the encoder failed */
    fun finish(): ByteArray?

    /** the [headerSize]-byte header of a file whose payload is [dataBytes] bytes */
    fun header(dataBytes: Long): ByteArray

    /** bytes a killed recording should keep before it is renamed, or null to keep the file as it is */
    fun repairLength(data: ByteArray): Int? = null
}

/**
 * Mono 16 kHz 16-bit PCM in a RIFF/WAVE container: what the desktop records and what Android falls back to.
 * Float samples are expected in [-1, 1] and are clamped before conversion to int16, so out-of-range values clip
 * instead of wrapping.
 */
object WavPcm : AudioFormat {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    const val HEADER_SIZE = 44

    override val extension = "wav"
    override val headerSize = HEADER_SIZE

    override fun encode(samples: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val clamped = s.coerceIn(-1f, 1f)
            buf.putShort((clamped * 32767f).toInt().toShort())
        }
        return buf.array()
    }

    /** PCM needs no flush: every byte the recorder received is already in the file */
    override fun finish(): ByteArray? = ByteArray(0)

    override fun header(dataBytes: Long): ByteArray {
        val byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = (CHANNELS * BITS_PER_SAMPLE / 8).toShort()
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt((36 + dataBytes).toInt())
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(CHANNELS.toShort())
            putInt(SAMPLE_RATE)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(BITS_PER_SAMPLE.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes.toInt())
        }.array()
    }
}
