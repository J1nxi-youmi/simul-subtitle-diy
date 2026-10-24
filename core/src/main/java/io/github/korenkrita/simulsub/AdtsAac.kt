package io.github.korenkrita.simulsub

/**
 * ADTS framing for the AAC recordings Android writes: a 7-byte header in front of every access unit.
 *
 * ADTS is chosen over an MP4/M4A container on purpose: every frame carries its own header, so a session that is
 * killed mid-write leaves a file that still decodes up to the last complete frame, while an MP4 would need its
 * `moov` atom written on close and would be unplayable — exactly the failure the WAV `.part` handling avoids.
 *
 * Pure functions only: this is the part of the AAC path the JVM tests can cover.
 */
object AdtsAac {
    /** header in front of every access unit */
    const val HEADER = 7

    /** audio object type 2 = AAC-LC, the profile `audio/mp4a-latm` encodes by default */
    private const val PROFILE = 2

    /** ADTS sampling frequency index; 16 kHz is 8, which is also what the recorder captures */
    private fun frequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4; 32_000 -> 5
        24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9; 11_025 -> 10; 8_000 -> 11
        else -> throw IllegalArgumentException("unsupported sample rate: $sampleRate")
    }

    /** The 7-byte ADTS header for an access unit of [frameBytes] payload bytes. */
    fun header(frameBytes: Int, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
        require(frameBytes in 0..0x1FFF) { "frame too large: $frameBytes" }
        require(channels in 1..7) { "unsupported channel count: $channels" }
        val total = HEADER + frameBytes
        val index = frequencyIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(), // MPEG-4, layer 00, no CRC
            (((PROFILE - 1) shl 6) or (index shl 2) or (channels shr 2)).toByte(),
            (((channels and 3) shl 6) or ((total shr 11) and 0x03)).toByte(),
            ((total shr 3) and 0xFF).toByte(),
            (((total and 0x07) shl 5) or 0x1F).toByte(), // buffer fullness: 0x7FF = variable
            0xFC.toByte(), // still buffer fullness, then one raw data block
        )
    }

    /**
     * The length of the complete frames at the front of [data] — what a killed recording keeps. Null when every
     * frame already is complete, so a caller can tell "nothing to repair" from "repair to zero".
     */
    fun completeLength(data: ByteArray): Int? {
        var i = 0
        while (i + HEADER <= data.size) {
            if ((data[i].toInt() and 0xFF) != 0xFF || (data[i + 1].toInt() and 0xF0) != 0xF0) return i
            val frame = ((data[i + 3].toInt() and 0x03) shl 11) or
                ((data[i + 4].toInt() and 0xFF) shl 3) or
                ((data[i + 5].toInt() and 0xE0) shr 5)
            if (frame < HEADER) return i
            if (i + frame > data.size) return i
            i += frame
        }
        return if (i == data.size) null else i
    }

    /** Whether [data] starts with a plausible ADTS frame (used before renaming a killed `.part` file). */
    fun looksLikeAdts(data: ByteArray): Boolean =
        data.size >= HEADER + 1 &&
            (data[0].toInt() and 0xFF) == 0xFF &&
            (data[1].toInt() and 0xF0) == 0xF0
}
