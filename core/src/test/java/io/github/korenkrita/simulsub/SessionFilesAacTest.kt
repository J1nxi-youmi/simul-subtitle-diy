package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pairing a log with an AAC recording — the format Android writes since the recordings became ADTS. */
class SessionFilesAacTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun root() = tmp.newFolder()
    private fun logs(root: File) = SessionLog.dir(root).apply { mkdirs() }
    private fun file(dir: File, name: String, size: Int = 4) = File(dir, name).apply { writeText("x".repeat(size)) }

    @Test
    fun anAacRecordingIsTheAudioOfItsLog() {
        val root = root()
        val log = file(logs(root), "session-20261025-040635.jsonl")
        val aac = file(logs(root), "session-20261025-040635.aac")
        assertEquals(aac, SessionFiles.audioOf(log))
        // a complete recording wins over the in-progress part of the same session
        val part = file(logs(root), "session-20261025-040635.aac.part")
        assertEquals(aac, SessionFiles.audioOf(log))
        assertTrue(part.exists())
    }

    @Test
    fun aWavAndAnAacInOneDirectoryBothStayPaired() {
        val root = root()
        val log = file(logs(root), "session-20261025-040635.jsonl")
        val wav = file(logs(root), "session-20261025-040635.wav")
        // WAV is preferred, but deleting the record removes both, so neither is orphaned
        assertEquals(wav, SessionFiles.audioOf(log))
        assertTrue(SessionFiles.deleteLog(log))
        assertFalse(wav.exists())
        assertFalse(File(logs(root), "session-20261025-040635.aac").exists())
        assertNull(SessionFiles.audioOf(log))
    }

    @Test
    fun deletingRemovesTheAacAndTheTotalCountsIt() {
        val root = root()
        val dir = logs(root)
        val log = file(dir, "session-20261025-040635.jsonl", size = 10)
        val aac = file(dir, "session-20261025-040635.aac", size = 100)
        assertEquals(110L, SessionFiles.totalBytes(root))
        assertTrue(SessionFiles.deleteLog(log))
        assertFalse(aac.exists())
        assertEquals(0L, SessionFiles.totalBytes(root))
    }
}
