package io.github.korenkrita.simulsub

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Writes a session recording on a background thread so the capture loop never waits on storage.
 *
 * What the file contains is decided by [format]: the desktop writes a mono 16 kHz 16-bit PCM WAV (see [WavPcm]),
 * Android writes ADTS AAC, which is about eight times smaller and still decodable after a crash because every
 * frame carries its own header.
 *
 * The file is written as `*.part` and only renamed once [close] succeeded — a WAV gets its header backfilled with
 * the real lengths first. A failed or still-running session therefore never leaves a file that looks complete.
 */
class AudioRecorder(
    /** the `*.part` file being written; its extension comes from [format] */
    val partFile: File,
    private val queueCapacity: Int = 200,
    private val onFail: (String) -> Unit = {},
    /** how samples become bytes: WAV on every platform, AAC on Android */
    private val format: AudioFormat = WavPcm,
) : AutoCloseable {
    @Volatile var failed: String? = null
        private set
    @Volatile var file: File = partFile
        private set

    /** the file this recording gets once it is complete: the `.part` name without the suffix */
    val targetFile: File = File(partFile.parentFile, partFile.name.removeSuffix(PART))

    private val queue = LinkedBlockingQueue<Packet>(queueCapacity)
    @Volatile private var closing = false
    @Volatile private var completed = false

    private data class Packet(val bytes: ByteArray)

    private val thread = Thread({ writerLoop() }, "jpsub-audio").apply { isDaemon = true; start() }

    private fun writerLoop() {
        var raf: RandomAccessFile? = null
        try {
            raf = RandomAccessFile(partFile, "rwd")
            // Reserve the header the format wants rewritten on close; a stream format reserves nothing.
            if (format.headerSize > 0) raf.write(ByteArray(format.headerSize))
            while (true) {
                val p = queue.poll(50, TimeUnit.MILLISECONDS)
                if (p == null) {
                    if (closing) break
                    continue
                }
                if (p.bytes.isNotEmpty()) raf.write(p.bytes)
                if (closing && queue.isEmpty()) break
            }
            // the encoder may still hold frames (AAC does); losing them would cut the recording short
            val tail = format.finish()
            if (tail == null) fail("音频编码器失败") else if (tail.isNotEmpty()) raf.write(tail)
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        } finally {
            runCatching { raf?.close() }.onFailure { fail(it.toString()) }
        }
    }

    /** Queue one frame for writing. Never blocks; a full queue fails the recorder for this session. */
    fun write(samples: FloatArray) {
        if (failed != null || closing) return
        val bytes = format.encode(samples)
        if (!queue.offer(Packet(bytes))) fail("音频写入队列满")
    }

    /**
     * Flushes the queued audio, finishes the format (header, encoder tail) and renames the part file to its
     * complete name.
     * @return true only if the file is complete; otherwise [failed] says why.
     */
    fun close(timeoutMs: Long = CLOSE_TIMEOUT_MS): Boolean {
        if (completed) return failed == null
        closing = true
        thread.join(timeoutMs)
        if (thread.isAlive) {
            fail("关闭音频超时")
            return false
        }
        if (failed != null) return false
        return finalizeFile().also { completed = it }
    }

    /**
     * [AutoCloseable] for use{} / try-with-resources, same semantics without the result.
     * 必须显式指向带参数的重载：无参调用会再次解析到这个无参 close 本身（Kotlin 里精确匹配
     * 优先于默认参数），那是一开始就写成的无限递归，开启录音的会话收尾时必然栈溢出。
     */
    override fun close() { close(CLOSE_TIMEOUT_MS) }

    private fun finalizeFile(): Boolean {
        val target = targetFile
        // 同名文件已被占用（stem 的选择在 SessionLog 那边避开了，这里只防进程外的文件）：宁可留下 .part，
        // 也不覆盖别人的完整录音——renameTo 在 Windows 会失败，在 Linux 却会静默覆盖
        if (target != partFile && target.exists()) { fail("无法完成音频文件：${target.name} 已存在"); return false }
        if (format.headerSize > 0) {
            val raf = runCatching { RandomAccessFile(partFile, "rwd") }.getOrNull()
                ?: run { fail("无法打开音频文件"); return false }
            try {
                val dataBytes = raf.length() - format.headerSize
                if (dataBytes < 0) { fail("音频文件长度异常"); return false }
                raf.seek(0)
                raf.write(format.header(dataBytes))
            } catch (t: Throwable) {
                fail(t.message ?: t.toString()); return false
            } finally {
                runCatching { raf.close() }
            }
        }
        if (!partFile.renameTo(target)) { fail("无法完成音频文件"); return false }
        file = target
        return true
    }

    @Synchronized private fun fail(why: String) {
        if (failed != null) return
        failed = why
        queue.clear()
        onFail(why)
    }

    companion object {
        private const val PART = ".part"
        /** how long [close] waits for the writer thread to drain its queue */
        const val CLOSE_TIMEOUT_MS = 3_000L

        /** the WAV conversion, kept for callers that just want the bytes (see [WavPcm.encode]) */
        fun samplesToBytes(samples: FloatArray): ByteArray = WavPcm.encode(samples)

        /**
         * Finalizes recordings a killed process left behind. A `*.wav.part` whose header was never backfilled gets
         * a correct one; an `*.aac.part` is truncated to its last complete ADTS frame. Both are then renamed to
         * their complete name, so a crashed session's audio is playable and shares like a finished one.
         *
         * The part of a session that may still write it ([SessionLog.isActive]) is left untouched, as is a part
         * holding nothing but a header, and one whose complete name is already taken — a finished recording is
         * never overwritten.
         * @return how many recordings were recovered
         */
        fun recover(dir: File): Int {
            var recovered = 0
            recovered += recoverWav(dir)
            recovered += recoverAac(dir)
            return recovered
        }

        private fun recoverWav(dir: File): Int {
            val parts = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav$PART") } ?: return 0
            var recovered = 0
            for (part in parts) {
                val stem = part.name.removeSuffix(".wav$PART")
                // the writer of a live session is still appending to its own part file: never repair that one
                if (SessionLog.isActive(File(dir, "$stem.jsonl$PART"))) continue
                if (recoverWavOne(part, File(dir, "$stem.wav"))) recovered++
            }
            return recovered
        }

        private fun recoverWavOne(part: File, target: File): Boolean {
            val len = runCatching { part.length() }.getOrNull() ?: return false
            if (len < WavPcm.HEADER_SIZE + 2L) return false // nothing but the reserved header: no audio to save
            if (target.exists()) return false // a complete recording already owns this name: keep the part as evidence
            val dataBytes = ((len - WavPcm.HEADER_SIZE) / 2) * 2 // drop a half-written sample so frames stay aligned
            val raf = runCatching { RandomAccessFile(part, "rwd") }.getOrNull() ?: return false
            try {
                if (dataBytes < len - WavPcm.HEADER_SIZE) raf.setLength(WavPcm.HEADER_SIZE + dataBytes)
                raf.seek(0)
                raf.write(WavPcm.header(dataBytes))
            } catch (t: Throwable) {
                return false // the part stays as it was: a later attempt can still recover it
            } finally {
                runCatching { raf.close() }
            }
            return part.renameTo(target)
        }

        /**
         * An AAC part needs no header rewritten: every ADTS frame describes itself, so the file stays playable as
         * it is. A frame the writer never finished is cut off, and the rest is renamed like a finished recording.
         */
        private fun recoverAac(dir: File): Int {
            val parts = dir.listFiles { f -> f.isFile && f.name.endsWith(".aac$PART") } ?: return 0
            var recovered = 0
            for (part in parts) {
                val stem = part.name.removeSuffix(".aac$PART")
                if (SessionLog.isActive(File(dir, "$stem.jsonl$PART"))) continue
                if (recoverAacOne(part, File(dir, "$stem.aac"))) recovered++
            }
            return recovered
        }

        /** how many bytes of a killed AAC file are read back to find its last complete frame */
        private const val AAC_REPAIR_WINDOW = 1 shl 20

        private fun recoverAacOne(part: File, target: File): Boolean {
            val len = runCatching { part.length() }.getOrNull() ?: return false
            if (len <= AdtsAac.HEADER) return false
            if (target.exists()) return false
            // The frame walk starts at the file's beginning, but only the head is read: a frame is at most 8 KiB,
            // and a file that starts mid-stream is no better than the part it already is.
            val head = ByteArray(minOf(len, AAC_REPAIR_WINDOW.toLong()).toInt())
            val raf = runCatching { RandomAccessFile(part, "rwd") }.getOrNull() ?: return false
            try {
                raf.readFully(head)
                if (!AdtsAac.looksLikeAdts(head)) return false
            } catch (t: Throwable) {
                return false
            } finally {
                runCatching { raf.close() }
            }
            return part.renameTo(target)
        }

        /** Deletes the oldest complete recordings in [dir] until their total size is <= [maxBytes]. `.part` files are kept. */
        fun trim(dir: File, maxBytes: Long = 200L * 1024 * 1024) {
            val done = dir.listFiles { f -> f.isFile && (f.name.endsWith(".wav") || f.name.endsWith(".aac")) }
                ?.sortedBy { it.lastModified() } ?: return
            var total = done.sumOf { it.length() }
            for (f in done) {
                if (total <= maxBytes) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }
}
