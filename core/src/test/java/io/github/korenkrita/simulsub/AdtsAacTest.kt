package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADTS framing: the header layout and the frame walk a killed recording is cut down with. */
class AdtsAacTest {
    @Test
    fun theHeaderDescribesTheFrameItPrecedes() {
        val frame = ByteArray(100) { 0x5A }
        val h = AdtsAac.header(frame.size, sampleRate = 16_000, channels = 1)
        assertEquals(7, h.size)
        // 12-bit sync, MPEG-4, layer 00, no CRC
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals(0xF1, h[1].toInt() and 0xFF)
        // AAC-LC profile (01), 16 kHz index 8, one channel
        assertEquals(1, (h[2].toInt() shr 6) and 0x03)
        assertEquals(8, (h[2].toInt() shr 2) and 0x0F)
        assertEquals(1, ((h[2].toInt() and 0x01) shl 2) or ((h[3].toInt() shr 6) and 0x03))
        // frame length = header + payload
        val len = ((h[3].toInt() and 0x03) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or
            ((h[5].toInt() and 0xE0) shr 5)
        assertEquals(107, len)
    }

    @Test
    fun theFrameWalkStopsAtTheFirstIncompleteFrame() {
        val one = AdtsAac.header(50) + ByteArray(50)
        val two = AdtsAac.header(30) + ByteArray(30)
        val whole = one + two
        assertNull("nothing to repair when every frame is complete", AdtsAac.completeLength(whole))
        // a write that stopped inside the second frame: the first frame survives, the rest is dropped
        val cut = whole.copyOfRange(0, one.size + 10)
        assertEquals(one.size, AdtsAac.completeLength(cut))
        // a file that does not even hold one complete frame has nothing to keep
        assertEquals(0, AdtsAac.completeLength(one.copyOfRange(0, 20)))
    }

    @Test
    fun aFileThatStartsWithGarbageIsNotTreatedAsAdts() {
        val wav = WavPcm.header(0)
        assertFalse(AdtsAac.looksLikeAdts(wav))
        assertFalse(AdtsAac.looksLikeAdts(ByteArray(0)))
        assertTrue(AdtsAac.looksLikeAdts(AdtsAac.header(10) + ByteArray(10)))
    }

    @Test
    fun everySampleRateTheRecorderMayUseHasAnIndex() {
        // the header refuses what ADTS cannot describe instead of writing a wrong index
        assertEquals(3, (AdtsAac.header(1, 48_000, 1)[2].toInt() shr 2) and 0x0F)
        assertEquals(4, (AdtsAac.header(1, 44_100, 1)[2].toInt() shr 2) and 0x0F)
        var threw = false
        try {
            AdtsAac.header(1, 16_001, 1)
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
