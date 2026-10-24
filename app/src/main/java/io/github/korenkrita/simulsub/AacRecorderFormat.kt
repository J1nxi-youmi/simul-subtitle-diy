package io.github.korenkrita.simulsub

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADTS AAC recording for Android (see [AudioFormat]): the platform encoder (`audio/mp4a-latm`, AAC-LC) encodes the
 * captured frames and every access unit gets a 7-byte [AdtsAac] header in front.
 *
 * ADTS instead of an M4A container is deliberate — each frame describes itself, so a session the system kills
 * mid-write still decodes up to its last complete frame, while an MP4 would need its index written on close and
 * would be unplayable.
 *
 * The whole encoder is used from the recorder's single writer thread, so no locking is needed. Creation can fail
 * on a device whose encoder rejects 16 kHz mono; the caller then records WAV instead of failing the session.
 */
class AacRecorderFormat(
    private val bitRate: Int = 48_000,
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1,
) : AudioFormat {
    private val encoder: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (t: Throwable) {
            runCatching { encoder.release() }
            throw t
        }
    }

    override val extension = "aac"

    /** ADTS frames carry their own header: nothing is reserved at the front of the file */
    override val headerSize = 0

    override fun encode(samples: FloatArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) pcm.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        pcm.flip()
        val out = ByteArrayOutputStream(samples.size / 4)
        queueInput(pcm, endOfStream = false, out = out)
        drain(out, endOfStream = false)
        return out.toByteArray()
    }

    override fun finish(): ByteArray? {
        if (released) return ByteArray(0)
        released = true
        return try {
            val out = ByteArrayOutputStream()
            queueInput(ByteBuffer.allocate(0), endOfStream = true, out = out)
            drain(out, endOfStream = true)
            out.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    /** AAC has no header to rewrite */
    override fun header(dataBytes: Long): ByteArray = ByteArray(0)

    private fun queueInput(pcm: ByteBuffer, endOfStream: Boolean, out: ByteArrayOutputStream) {
        val flags = if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        val size = pcm.remaining()
        var queued = false
        repeat(8) {
            if (queued) return
            val index = encoder.dequeueInputBuffer(2_000)
            if (index < 0) {
                drain(out, endOfStream = false) // free an output buffer and try again
                return@repeat
            }
            val buffer = encoder.getInputBuffer(index) ?: return@repeat
            buffer.clear()
            if (size > 0) buffer.put(pcm)
            encoder.queueInputBuffer(index, 0, size, presentationUs, flags)
            presentationUs += size / 2L * 1_000_000L / sampleRate
            queued = true
        }
        // a frame the encoder refused is dropped rather than blocking the capture thread: the recording is a
        // debugging aid, and the subtitle pipeline must keep running
    }

    private fun drain(out: ByteArrayOutputStream, endOfStream: Boolean) {
        while (true) {
            val index = encoder.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    val buffer = encoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val frame = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(frame)
                        out.write(AdtsAac.header(frame.size, sampleRate, channels))
                        out.write(frame)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }
}
