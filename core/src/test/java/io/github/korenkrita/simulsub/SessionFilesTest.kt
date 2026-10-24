package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** One record = a session log plus its optional recording: pairing, paired deletes, and byte totals. */
class SessionFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** a fresh record directory (what the app hands over as `getExternalFilesDir(null)`) */
    private fun root() = tmp.newFolder()

    private fun logs(root: File) = SessionLog.dir(root).apply { mkdirs() }

    private fun file(dir: File, name: String, size: Int = 1) = File(dir, name).apply { writeText("x".repeat(size)) }

    // ---- audioOf ----

    @Test
    fun aCompleteWavIsTheAudioOfItsLog() {
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl", 20)
        val wav = file(d, "session-20261023-100000.wav", 4_000)
        assertEquals(wav, SessionFiles.audioOf(log))
    }

    @Test
    fun aRunningRecordingPairsThroughItsPartFiles() {
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl.part", 20)
        val wav = file(d, "session-20261023-100000.wav.part", 4_000)
        assertEquals(wav, SessionFiles.audioOf(log))
    }

    @Test
    fun aCompleteWavWinsOverALeftoverPart() {
        // 两者同时存在本不该发生（close 会转正 .part），但真发生了必须认完整的 .wav
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl")
        file(d, "session-20261023-100000.wav.part")
        val wav = file(d, "session-20261023-100000.wav")
        assertEquals(wav, SessionFiles.audioOf(log))
    }

    @Test
    fun aLogWithoutAudioHasNoAudio() {
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl")
        assertNull(SessionFiles.audioOf(log))
        // 别的会话的音频不算数：只认同一个文件名主干
        file(d, "session-20261023-110000.wav")
        assertNull(SessionFiles.audioOf(log))
    }

    @Test
    fun onlySomethingNamedLikeALogHasAudio() {
        val root = root(); val d = logs(root)
        file(d, "session-20261023-100000.wav")
        assertNull("a .wav is not a log, it IS the audio", SessionFiles.audioOf(file(d, "session-20261023-100000.wav")))
        assertNull(SessionFiles.audioOf(file(d, "notes.txt")))
        // 连父目录都没有的裸路径也一样安全，不会到别处找文件
        assertNull(SessionFiles.audioOf(File("session-20261023-100000.jsonl")))
    }

    // ---- deleteLog ----

    @Test
    fun deletingALogRemovesItsCompleteAudioWithIt() {
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl")
        val wav = file(d, "session-20261023-100000.wav", 4_000)
        assertTrue(SessionFiles.deleteLog(log))
        assertFalse(log.exists())
        assertFalse("删记录不能把录音留在盘上", wav.exists())
    }

    @Test
    fun deletingALogRemovesItsPartAudioWithIt() {
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl.part") // a session killed before it could finish
        val wav = file(d, "session-20261023-100000.wav.part", 4_000)
        assertTrue(SessionFiles.deleteLog(log))
        assertFalse(log.exists())
        assertFalse(wav.exists())
    }

    @Test
    fun aLogWithoutAudioDeletesOnItsOwn() {
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl")
        assertTrue(SessionFiles.deleteLog(log))
        assertFalse(log.exists())
    }

    @Test
    fun deletingAnAlreadyGoneLogStillSweepsItsOrphanedAudio() {
        // 旧版本或手动删除留下的孤儿音频：按同一个名字主干一并清掉
        val root = root(); val d = logs(root)
        val wav = file(d, "session-20261023-100000.wav", 4_000)
        assertTrue(SessionFiles.deleteLog(File(d, "session-20261023-100000.jsonl")))
        assertFalse(wav.exists())
    }

    @Test
    fun anAudioThatCannotBeDeletedKeepsItsLog() {
        // 完整录音的名字被一个删不掉的目录占着：宁可整体不删，也不把记录拆成两半
        val root = root(); val d = logs(root)
        val log = file(d, "session-20261023-100000.jsonl")
        val taken = File(d, "session-20261023-100000.wav").apply { mkdirs(); File(this, "keep.txt").writeText("x") }
        assertFalse(SessionFiles.deleteLog(log))
        assertTrue(log.exists())
        assertTrue(taken.isDirectory)
    }

    @Test
    fun aRunningSessionRefusesToDeleteAndKeepsItsAudio() {
        val root = root(); val d = logs(root)
        val log = SessionLog(d)
        val logPart = log.file
        val wavPart = file(d, "${log.stem}.wav.part", 100) // the recording the running session is writing
        assertTrue(SessionLog.isActive(logPart))
        assertFalse(SessionFiles.deleteLog(logPart))
        assertTrue(logPart.exists())
        assertTrue(wavPart.exists())
        // 「全部清空」数出正在记录的那一个，且它连着录音一起留下
        assertEquals(1, SessionFiles.deleteAll(root))
        assertTrue(logPart.exists())
        assertTrue(wavPart.exists())
        assertTrue(log.close())
        // 会话结束后同一对文件就能删了——音频侧还停在 .part 也一样跟着走
        assertTrue(SessionFiles.deleteLog(log.file))
        assertFalse(log.file.exists())
        assertFalse(wavPart.exists())
    }

    @Test
    fun deleteAllClearsFinishedRecordsWithTheirAudioAndCountsOnlyWhatStays() {
        val root = root(); val d = logs(root)
        val doneLog = SessionLog(d).also { assertTrue(it.close()) }.file
        val doneWav = file(d, "${doneLog.name.removeSuffix(".jsonl")}.wav", 4_000)
        val staleLog = file(d, "session-20260101-000000.jsonl.part")
        val staleWav = file(d, "session-20260101-000000.wav.part", 4_000)
        val running = SessionLog(d)
        val runningWav = file(d, "${running.stem}.wav.part", 100)
        assertEquals(1, SessionFiles.deleteAll(root))
        assertFalse(doneLog.exists()); assertFalse(doneWav.exists())
        assertFalse(staleLog.exists()); assertFalse(staleWav.exists())
        assertTrue(running.file.exists()); assertTrue(runningWav.exists())
        assertTrue(running.close())
        assertEquals(0, SessionFiles.deleteAll(root))
        assertTrue(SessionLog.list(root).isEmpty())
    }

    // ---- totalBytes ----

    @Test
    fun totalBytesCountsEveryLogAndRecordingInTheLogDirAndNothingElse() {
        val root = root(); val d = logs(root)
        file(d, "session-20261023-100000.jsonl", 100)
        file(d, "session-20261023-100000.wav", 4_000)
        file(d, "session-20261023-110000.jsonl.part", 50)
        file(d, "session-20261023-110000.wav.part", 60)
        file(d, "notes.txt", 999) // 无关文件不统计
        file(d, "session-20261023-120000.wav.bak", 999) // .wav 后缀必须收尾才算
        File(d, "session-20261023-130000.wav").apply { mkdirs() } // 占着音频名字的目录不是音频
        File(d, "older").apply { mkdirs() }
        file(File(d, "older"), "session-20261023-140000.wav", 500) // 只统计 logs 这一层
        assertEquals(100L + 4_000 + 50 + 60, SessionFiles.totalBytes(root))
    }

    @Test
    fun aMissingRootHasNoRecordsAndNoBytes() {
        val missing = File(tmp.newFolder(), "nope")
        assertEquals(0L, SessionFiles.totalBytes(missing))
        assertEquals(0, SessionFiles.deleteAll(missing))
    }
}
