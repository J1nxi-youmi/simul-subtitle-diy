package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.StringWriter
import java.io.File
import java.io.Writer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class SessionLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun sessionsStartedInTheSameSecondGetTheirOwnFiles() {
        val root = tmp.newFolder()
        val a = SessionLog(SessionLog.dir(root)); val b = SessionLog(SessionLog.dir(root))
        a.write(mapOf("ev" to "a")); b.write(mapOf("ev" to "b"))
        assertTrue(a.close()); assertTrue(b.close())
        assertTrue(a.file.name != b.file.name)
        assertTrue(a.file.readText().contains("\"ev\":\"a\""))
        assertTrue(b.file.readText().contains("\"ev\":\"b\""))
        assertEquals(2, SessionLog.list(root).count(SessionLog::complete))
    }

    @Test
    fun aFileIsOnlyListedAsCompleteAfterAClose() {
        val root = tmp.newFolder()
        val log = SessionLog(SessionLog.dir(root))
        log.write(mapOf("ev" to "x"))
        val open = SessionLog.list(root).single()
        assertFalse(SessionLog.complete(open))
        assertTrue(SessionLog.isActive(open)) // the home screen does not offer it for sharing
        assertTrue(log.close())
        assertFalse(SessionLog.isActive(open))
        val done = SessionLog.list(root).single()
        assertTrue(SessionLog.complete(done))
        assertFalse(open.exists()) // a URI made from the listed .part before close would point at nothing
        assertTrue(done.readLines().single().contains("\"ev\":\"x\""))
    }

    @Test
    fun deleteRemovesFinishedAndInterruptedLogsButNeverOneStillBeingWritten() {
        val root = tmp.newFolder()
        val done = SessionLog(SessionLog.dir(root)).also { it.write(mapOf("ev" to "a")); assertTrue(it.close()) }.file
        val stale = File(SessionLog.dir(root), "session-20260101-000000.jsonl.part").apply { writeText("{}\n") }
        val running = SessionLog(SessionLog.dir(root))
        val runningFile = SessionLog.list(root).single(SessionLog::isActive)
        assertTrue(SessionLog.delete(done)); assertFalse(done.exists())
        assertFalse("a running session's file must stay", SessionLog.delete(runningFile)); assertTrue(runningFile.exists())
        // "clear all": the running one stays, the interrupted one goes; the count says what is left
        assertEquals(1, SessionLog.deleteAll(root))
        assertFalse(stale.exists())
        assertEquals(listOf(runningFile), SessionLog.list(root))
        assertTrue(running.close())
        assertEquals(0, SessionLog.deleteAll(root))
        assertTrue(SessionLog.list(root).isEmpty())
    }

    @Test
    fun aStalledDiskDropsEventsVisiblyInsteadOfGrowingWithoutBound() {
        val release = CountDownLatch(1)
        val stuck = object : Writer() {
            val out = StringWriter()
            override fun write(c: CharArray, off: Int, len: Int) { release.await(); out.write(c, off, len) }
            override fun flush() {}
            override fun close() {}
        }
        val reported = AtomicReference<String?>()
        val log = SessionLog(tmp.root, capacity = 4, open = { stuck }, onFail = { reported.set(it) })
        repeat(20) { log.write(mapOf("ev" to "e$it")) }
        assertNotNull(log.failed); assertNotNull(reported.get())
        release.countDown()
        assertFalse("an incomplete log is never reported as complete", log.close())
    }

    @Test
    fun aCloseThatTimesOutIsReported() {
        val never = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stuck = object : Writer() {
            override fun write(c: CharArray, off: Int, len: Int) { never.await() }
            override fun flush() {}
            override fun close() { closed.countDown() }
        }
        val reported = AtomicReference<String?>()
        val log = SessionLog(tmp.root, open = { stuck }, onFail = { reported.set(it) })
        log.write(mapOf("ev" to "x"))
        assertFalse(log.close(timeoutMs = 200))
        assertNotNull(reported.get())
        // the disk recovers: the writer must still finish and close the file, not wait for events forever
        assertTrue("a file whose writer is still alive must not be shareable", SessionLog.isActive(log.file))
        never.countDown()
        assertTrue("writer never closed", closed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val until = System.currentTimeMillis() + 2000
        while (SessionLog.isActive(log.file) && System.currentTimeMillis() < until) Thread.sleep(10)
        assertFalse("released once the writer has really exited", SessionLog.isActive(log.file))
    }

    @Test
    fun anOlderSessionStillFinishingStaysProtectedWhenANewOneStarts() {
        val root = tmp.newFolder()
        val old = SessionLog(SessionLog.dir(root))
        val new = SessionLog(SessionLog.dir(root))
        assertTrue(SessionLog.isActive(old.file)); assertTrue(SessionLog.isActive(new.file))
        assertTrue(new.close())
        assertTrue(SessionLog.isActive(old.file))
        assertTrue(old.close())
        assertFalse(SessionLog.isActive(old.file))
    }

    @Test
    fun aFinishedLogIsNeverOverwrittenByAnotherSession() {
        // another session finished a file with this name between our name choice and our own finish
        val root = tmp.newFolder()
        val log = SessionLog(SessionLog.dir(root))
        log.write(mapOf("ev" to "mine"))
        val target = File(SessionLog.dir(root), log.file.name.removeSuffix(".part"))
        target.writeText("theirs\n")
        assertTrue(log.close())
        assertEquals("theirs\n", target.readText())
        assertTrue(log.file.readText().contains("\"ev\":\"mine\""))
        assertTrue(log.file != target)
    }

    @Test
    fun aWriteErrorOnTheLastEventIsStillReported() {
        val broken = object : Writer() {
            override fun write(c: CharArray, off: Int, len: Int) { throw java.io.IOException("disk full") }
            override fun flush() {}
            override fun close() {}
        }
        val reported = AtomicReference<String?>()
        val log = SessionLog(tmp.root, open = { broken }, onFail = { reported.set(it) })
        log.write(mapOf("ev" to "session_end"))
        assertFalse(log.close())
        assertEquals("disk full", reported.get())
        assertNull(SessionLog.list(tmp.root).firstOrNull()?.takeIf { SessionLog.complete(it) })
    }

    @Test
    fun theStemLeavesRoomForTheAudioFileWithTheSameName() {
        // 同一秒的 .wav 已经在了（用户删过日志、音频还在，或别的会话留下）：stem 必须避开它，
        // 否则录音结束时要么覆盖那份录音、要么转正失败留下 .part
        val root = tmp.newFolder()
        val dir = SessionLog.dir(root).apply { mkdirs() }
        val fmt = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        for (delta in -1..2) { // 现在前后各留几秒：无论挑中哪一秒，那一秒的 .wav 都已被占用
            cal.timeInMillis = System.currentTimeMillis() + delta * 1000L
            File(dir, "session-${fmt.format(cal.time)}.wav").writeText("keep me")
        }
        val log = SessionLog(dir)
        assertTrue("a stem whose .wav exists must be skipped, got ${log.stem}", log.stem.endsWith("-1"))
        assertFalse(File(dir, "${log.stem}.wav").exists())
        assertTrue(log.close())
        assertTrue(SessionLog.complete(log.file))
        // 占用的那份 .wav 原样还在
        assertEquals("keep me", File(dir, log.stem.removeSuffix("-1") + ".wav").readText())
    }
}
