package io.github.korenkrita.simulsub

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The session on the phone and the desktop, with a fake platform: how it shows captions and how it ends. */
class CaptionSessionTest {
    // the translator: every request gets "译文" as a stream (one request at a time is enough here)
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val mtCalls = AtomicInteger()
    /** the system prompts of the translation requests, in order */
    private val systems = java.util.concurrent.CopyOnWriteArrayList<String>()

    init {
        Thread {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                Thread {
                    s.use { sock ->
                        val inp = sock.getInputStream()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) { val b = inp.read(); if (b < 0) return@use; head.append(b.toChar()) }
                        val len = head.lines().firstOrNull { it.lowercase().startsWith("content-length:") }
                            ?.substringAfter(':')?.trim()?.toInt() ?: 0
                        var o = 0; val body = ByteArray(len)
                        while (o < len) { val n = inp.read(body, o, len - o); if (n < 0) return@use; o += n }
                        val req = Json.parse(String(body)) as? Map<*, *>
                        ((req?.get("messages") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("content")?.let { systems += it as String }
                        mtCalls.incrementAndGet()
                        val sse = listOf("""{"choices":[{"delta":{"content":"译文"}}]}""",
                            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""").joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
                        sock.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n$sse".toByteArray()); flush()
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
    }

    private val hosts = mutableListOf<FakeHost>()
    @After fun close() { server.close(); hosts.forEach { it.shutdown() } }

    private val logs = File(System.getProperty("java.io.tmpdir"), "simulsub-session-test-${System.nanoTime()}")

    private inner class FakePrefs : SessionPrefs {
        override val jevOn = false
        override val jevCheck = false
        // set per test, before start(): the session reads both once while starting
        @Volatile override var keepLogs = false
        @Volatile override var recordAudio = false
        override val keepSentences = CaptionBoard.DEFAULT_SENTENCES
        override val keepChars = CaptionBoard.DEFAULT_CHARS
        override val finalOnly = false
        override val showJa = true
        override val showStats = false
        @Volatile override var scene = Scene.GENERAL
        override val sourceLang: Lang? = Lang.JA
        override val targetLang = Target.ZH
        override val dedupeJoin = false
        override val contextOn = false
        override val contextSentences = 0
        override val jevProvider = JevProvider.OFFICIAL
        // the shipped defaults (Prefs.kt on both platforms): the session cuts at the same thresholds a user gets
        override val judgeModel = ""
        override val judgeUrl = ""
        override fun judgeKey(): String? = null
        override val endSilenceMs = 350
        override val maxUtteranceMs = 6_000
        override fun jevKey(p: JevProvider): String? = null
        override fun byokService() = ByokService("http://127.0.0.1:${server.localPort}/v1/chat/completions", "m-1", "")
        // tested before: no probe
        override fun byokVariant(url: String, model: String): String? = ByokTranslator.NONE_WORKED
        override fun setByokVariant(url: String, model: String, name: String?) {}
    }

    /**
     * Audio: [speech] s of a tone between half a second of silence before and two after, then [end] (null: the
     * source never ends by itself and waits for cancel). Read as fast as the session takes it.
     */
    private class FakeAudio(speech: Float, private val end: String?, private val endsByItself: Boolean = true,
                            utterances: Int = 1) : AudioSource {
        private val one = ((0.5f + speech + 2f) * 16_000).toInt()
        private val samples = FloatArray(one * utterances).also { s ->
            for (u in 0 until utterances) for (i in (8_000 until 8_000 + (speech * 16_000).toInt())) s[u * one + i] = 0.3f * kotlin.math.sin(i * 0.2f)
        }
        /** read up to [n] samples; the rest waits until [more] is counted down */
        @Volatile var gateAt = Int.MAX_VALUE
        val more = CountDownLatch(1)
        private var pos = 0
        private val cancelled = CountDownLatch(1)
        @Volatile var closed = false
        private val t0 = monoMs()
        /** the monoMs the file's sample 0 corresponds to (read()'s clock, one read granularity accurate) */
        val startAt: Long get() = t0
        /** all samples [read] hands out before it ends by itself */
        val totalSamples: Int get() = samples.size
        override val estimated = false
        override fun read(frame: FloatArray): Long {
            if (cancelled.count == 0L) return -1
            if (pos + frame.size > samples.size) {
                if (endsByItself) return -1
                cancelled.await(); return -1
            }
            if (pos >= gateAt) more.await()
            System.arraycopy(samples, pos, frame, 0, frame.size); pos += frame.size
            return t0 + pos * 1000L / 16_000
        }
        @Volatile var endAsked = false
        override fun endReason(): String? { endAsked = true; return end }
        override fun close() { closed = true }
        override fun cancel() = cancelled.countDown()
    }

    private inner class FakeHost(val audio: FakeAudio, val asrFails: Boolean = false,
                                 /** a recognition of speech waits for this (not interruptible, as a native call) */
                                 val asrGate: CountDownLatch? = null) : SessionHost {
        init { hosts += this }
        private val ui = Executors.newSingleThreadScheduledExecutor()
        fun shutdown() = ui.shutdownNow()
        val inAsr = CountDownLatch(1)
        override val prefs = FakePrefs()
        val released = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var lines: List<CaptionLine> = emptyList()
        @Volatile var diag: String? = null
        override fun post(r: () -> Unit) { ui.execute(r) }
        override fun postDelayed(r: () -> Unit, ms: Long): AutoCloseable {
            val f = ui.schedule(r, ms, TimeUnit.MILLISECONDS)
            return AutoCloseable { f.cancel(false) }
        }
        override fun clock() = monoMs()
        override fun logInfo(m: String) {}
        override fun logWarn(m: String, e: Throwable?) {}
        override val logRoot = logs
        override val appVersion = "test"
        override fun deviceInfo(byokUrl: String?) = mapOf<String, Any?>("device" to "test")
        override val deviceWord = "电脑"
        override val endHint = "点 ✕ 结束"
        override fun loadModels(asrThreads: Int) = object : SpeechModels {
            override val asr = object : Asr {
                override fun recognize(samples: FloatArray): String {
                    // the warm-up is silence; real speech carries the tone
                    if (samples.none { it != 0f }) return ""
                    if (asrFails) error("识别坏了")
                    asrGate?.let { g -> inAsr.countDown(); while (g.count > 0) try { g.await() } catch (_: InterruptedException) {} }
                    return "テスト"
                }
            }
            override fun vadProb(frame: FloatArray) = if (frame.any { kotlin.math.abs(it) > 0.01f }) 0.95f else 0.02f
            override fun release() = released.countDown()
        }
        override fun openAudio(log: SessionLog?) = audio
        override fun show(lines: List<CaptionLine>, diag: String?, mtLight: ServiceStatus.Light, jevLight: ServiceStatus.Light,
                          mtReason: String?, jevReason: String?) { this.lines = lines; this.diag = diag }
        override fun ended() = ended.countDown()
        override fun finished() = finished.countDown()
        /** waits for what was posted to the UI thread so far */
        fun settle() { ui.submit {}.get(5, TimeUnit.SECONDS) }
    }

    @Test
    fun audioThatEndsByItselfIsStillTranslatedAndItsReasonStays() {
        val host = FakeHost(FakeAudio(1.5f, "⚠ 系统声音采集停止了（退出码 1）"))
        val s = CaptionSession(host)
        host.post { s.start() }
        assertTrue(host.ended.await(20, TimeUnit.SECONDS))
        assertTrue(host.released.await(5, TimeUnit.SECONDS))
        host.settle()
        assertTrue(s.ended)
        // what was heard before the end is on screen as a finished sentence, with why it ended and how to go on
        val last = host.lines.last()
        assertEquals("译文", last.zh); assertEquals("テスト", last.ja); assertTrue(last.isFinal)
        assertTrue(host.diag!!, host.diag!!.startsWith("⚠ 系统声音采集停止了（退出码 1）。点 ✕ 结束后可以重新开始。"))
        assertTrue(host.audio.closed)
        assertEquals(1L, host.finished.count) // the window stays
    }

    @Test
    fun audioThatEndsWithNothingToSayClosesTheSession() {
        val host = FakeHost(FakeAudio(1.5f, null))
        host.post { CaptionSession(host).start() }
        assertTrue(host.finished.await(20, TimeUnit.SECONDS))
        assertEquals(1L, host.ended.count)
    }

    @Test
    fun stoppingEndsTheCaptureAndReleasesEverythingWithoutKeepingTheWindow() {
        val host = FakeHost(FakeAudio(1.5f, null, endsByItself = false))
        val s = CaptionSession(host)
        host.post { s.start() }
        // the sentence comes through while the audio still runs
        val t0 = monoMs()
        while (host.lines.none { it.isFinal } && monoMs() - t0 < 20_000) Thread.sleep(20)
        assertEquals("译文", host.lines.last { it.isFinal }.zh)
        host.post { s.stop() }
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
        assertTrue(host.audio.closed)
        Thread.sleep(200); host.settle()
        assertFalse(s.ended)
        assertEquals(1L, host.ended.count); assertEquals(1L, host.finished.count)
    }

    @Test
    fun aFailedWorkerKeepsTheWindowWithTheError() {
        // the phone used to close its overlay here, so the error was never seen; now both platforms keep it
        val host = FakeHost(FakeAudio(1.5f, null, endsByItself = false), asrFails = true)
        val s = CaptionSession(host)
        host.post { s.start() }
        assertTrue(host.ended.await(20, TimeUnit.SECONDS))
        assertTrue(host.released.await(5, TimeUnit.SECONDS))
        host.settle()
        assertTrue(host.diag!!, host.diag!!.startsWith("出错：识别坏了。点 ✕ 结束后可以重新开始。"))
        assertTrue(host.audio.closed)
        assertTrue(host.audio.endAsked) // the desktop logs how sysaudio ended
        assertEquals(1L, host.finished.count)
        assertEquals(0, mtCalls.get())
    }

    @Test
    fun theModelsAreFreedOnlyAfterARecognitionInProgressReturns() {
        val gate = CountDownLatch(1)
        val host = FakeHost(FakeAudio(1.5f, null, endsByItself = false), asrGate = gate)
        val s = CaptionSession(host)
        host.post { s.start() }
        assertTrue(host.inAsr.await(20, TimeUnit.SECONDS))
        host.post { s.stop() }
        // the stop is done at once, but nothing native is freed while a worker is still inside it
        Thread.sleep(500)
        assertEquals(1L, host.released.count)
        gate.countDown()
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
        assertEquals(1L, host.ended.count); assertEquals(1L, host.finished.count)
    }

    @Test
    fun aSettingChangedWhileRunningTranslatesFromTheNextSentence() {
        val audio = FakeAudio(1.5f, null, endsByItself = false, utterances = 2).apply { gateAt = ((0.5f + 1.5f + 2f) * 16_000).toInt() }
        val host = FakeHost(audio)
        val s = CaptionSession(host)
        host.post { s.start() }
        val t0 = monoMs()
        while (host.lines.none { it.isFinal } && monoMs() - t0 < 20_000) Thread.sleep(20)
        val before = systems.size
        assertTrue(before > 0); assertFalse(systems.any { "VTuber" in it })
        // the home screen switches the scene: the session rebuilds its setup, the second sentence uses it
        host.prefs.scene = Scene.VTUBER
        s.prefChanged("scene")
        Thread.sleep(300) // the rebuild runs off the UI thread
        audio.more.countDown()
        while (host.lines.count { it.isFinal } < 2 && monoMs() - t0 < 20_000) Thread.sleep(20)
        assertEquals(2, host.lines.count { it.isFinal })
        assertTrue(systems.drop(before).isNotEmpty() && systems.drop(before).all { "VTuber" in it })
        host.post { s.stop() }
        assertTrue(host.released.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun recordedAudioKeepsAWavAnchoredByAnAudioStartEvent() {
        val audio = FakeAudio(1.5f, null)
        val host = FakeHost(audio)
        host.prefs.keepLogs = true
        host.prefs.recordAudio = true
        host.post { CaptionSession(host).start() }
        assertTrue(host.finished.await(20, TimeUnit.SECONDS))
        val dir = SessionLog.dir(logs)
        val jsonl = dir.listFiles { f: File -> f.name.endsWith(".jsonl") }?.singleOrNull()
            ?: error("the session log is missing: ${dir.listFiles()?.joinToString { it.name }}")
        assertFalse("the closed log is no .part", jsonl.name.endsWith(".part"))
        val events = jsonl.readLines().map { Json.parse(it) as Map<*, *> }
        val start = events.firstOrNull { it["ev"] == "audio_start" } ?: error("no audio_start in $events")
        // the WAV sits next to the JSONL with the same stem, finished (no .part left behind)
        assertEquals(jsonl.name.removeSuffix(".jsonl") + ".wav", start["file"])
        val wav = File(dir, start["file"] as String)
        assertTrue("the wav must exist after a normal close", wav.isFile)
        assertTrue(dir.listFiles { f: File -> f.name.endsWith(".part") }.isNullOrEmpty())
        // hal says the timestamps are capture times (FakeAudio reports no read time)
        assertEquals(true, start["hal"])
        val t0 = (start["t0"] as Number).toLong()
        // 对齐公式：文件第 n 个采样点 ≈ t0 + n/16 毫秒。整个文件写了音频源给出的每一个样本，
        // 所以第一个与最后一个采样点都要落在公式给的位置上（±2 ms 的取整余量）。
        val samples = (wav.length() - 44) / 2
        assertEquals("every sample the capture read is in the file", audio.totalSamples.toLong(), samples)
        val firstMs = t0.toDouble()
        val lastMs = t0 + (samples - 1) / 16.0
        val trueFirstMs = audio.startAt.toDouble()
        val trueLastMs = audio.startAt + audio.totalSamples * 1000.0 / 16_000.0
        assertTrue("first sample: $firstMs vs $trueFirstMs", kotlin.math.abs(firstMs - trueFirstMs) <= 2.0)
        assertTrue("last sample: $lastMs vs $trueLastMs", kotlin.math.abs(lastMs - trueLastMs) <= 2.0)
        // the session_end closes the log after the audio was finalized
        assertEquals("session_end", (events.lastOrNull()?.get("ev")))
    }

    @Test
    fun theSwitchesDecideWhetherAnythingIsRecorded() {
        val dir = SessionLog.dir(logs)
        // keepLogs off: no log, so no audio either, whatever recordAudio says
        val hostOff = FakeHost(FakeAudio(1.5f, null))
        hostOff.prefs.recordAudio = true
        hostOff.post { CaptionSession(hostOff).start() }
        assertTrue(hostOff.finished.await(20, TimeUnit.SECONDS))
        assertFalse(dir.exists() && dir.listFiles().orEmpty().isNotEmpty())
        // recordAudio off: a log is written, but with no audio_start and no wav
        val host = FakeHost(FakeAudio(1.5f, null))
        host.prefs.keepLogs = true
        host.prefs.recordAudio = false
        host.post { CaptionSession(host).start() }
        assertTrue(host.finished.await(20, TimeUnit.SECONDS))
        val jsonl = dir.listFiles { f: File -> f.name.endsWith(".jsonl") }?.singleOrNull()
            ?: error("the session log is missing: ${dir.listFiles()?.joinToString { it.name }}")
        val events = jsonl.readLines().map { Json.parse(it) as Map<*, *> }
        assertTrue(events.none { it["ev"] == "audio_start" })
        assertTrue(dir.listFiles { f: File -> f.name.endsWith(".wav") || f.name.endsWith(".wav.part") }.isNullOrEmpty())
    }
}
