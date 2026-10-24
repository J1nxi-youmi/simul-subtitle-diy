package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The recorder with a format that has no header (ADTS AAC): the file is named by the format, no bytes are reserved
 * at the front, the encoder's tail still reaches the disk, and a killed `.aac.part` is renamed like a finished
 * recording.
 */
class AudioRecorderAacTest {
    @get:Rule val tmp = TemporaryFolder()

    /** One byte per sample, plus a tail at the end: enough to tell header-less writing from the WAV path. */
    private object FakeAac : AudioFormat {
        override val extension = "aac"
        override val headerSize = 0
        override fun encode(samples: FloatArray): ByteArray = ByteArray(samples.size) { 7 }
        override fun finish(): ByteArray = byteArrayOf(9)
        override fun header(dataBytes: Long): ByteArray = ByteArray(0)
    }

    @Test
    fun aHeaderlessFormatWritesItsOwnExtensionAndNoReservedHeader() {
        val dir = tmp.newFolder()
        val part = File(dir, "session-20261025-040635.aac.part")
        val rec = AudioRecorder(part, format = FakeAac)
        rec.write(FloatArray(10))
        rec.write(FloatArray(5))
        assertTrue(rec.close(AudioRecorder.CLOSE_TIMEOUT_MS))
        assertFalse("the part is gone once the recording is complete", part.exists())
        val file = File(dir, "session-20261025-040635.aac")
        assertTrue(file.isFile)
        // 15 sample bytes + the encoder tail; nothing reserved at the front
        assertEquals(16L, file.length())
        assertEquals(file, rec.file)
    }

    @Test
    fun aKilledAacRecordingIsRenamedAsItIs() {
        val dir = tmp.newFolder()
        val frames = AdtsAac.header(20) + ByteArray(20) + AdtsAac.header(30) + ByteArray(30)
        val part = File(dir, "session-20261025-040635.aac.part").apply { writeBytes(frames) }
        assertEquals(1, AudioRecorder.recover(dir))
        assertFalse(part.exists())
        val file = File(dir, "session-20261025-040635.aac")
        assertTrue(file.isFile)
        // ADTS needs no header rewritten: the bytes are untouched
        assertEquals(frames.size.toLong(), file.length())
        assertTrue(file.readBytes().contentEquals(frames))
    }

    @Test
    fun aPartThatIsNotAdtsIsLeftAlone() {
        val dir = tmp.newFolder()
        val junk = File(dir, "session-20261025-040635.aac.part").apply { writeBytes(WavPcm.header(100)) }
        val empty = File(dir, "session-20261025-050000.aac.part").apply { writeBytes(ByteArray(4)) }
        assertEquals(0, AudioRecorder.recover(dir))
        assertTrue(junk.exists()); assertTrue(empty.exists())
        assertFalse(File(dir, "session-20261025-040635.aac").exists())
    }

    @Test
    fun theTrimCountsBothFormats() {
        val dir = tmp.newFolder()
        val now = System.currentTimeMillis()
        fun file(name: String, size: Int, age: Long) = File(dir, name).apply {
            writeText("x".repeat(size)); setLastModified(now - age)
        }
        val oldWav = file("session-20261025-010000.wav", 2_000, 300_000)
        val oldAac = file("session-20261025-020000.aac", 3_000, 200_000)
        val running = file("session-20261025-030000.aac.part", 5_000, 100_000)
        AudioRecorder.trim(dir, maxBytes = 3_000) // 5 000 bytes of finished recordings: the oldest goes first
        assertFalse(oldWav.exists())
        assertTrue(oldAac.exists())
        assertTrue("a recording still being written is never trimmed", running.exists())
    }
}
