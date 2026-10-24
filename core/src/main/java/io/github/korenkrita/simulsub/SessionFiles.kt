package io.github.korenkrita.simulsub

import java.io.File

/**
 * Pairs a session log with the recording of the same session: `session-….jsonl[.part]` ↔
 * `session-….wav[.part]` or `session-….aac[.part]`, in the same directory (the shared name is [SessionLog.stem];
 * AudioRecorder renames its `.part` on a successful close, exactly like the log does). Deleting, sharing and sizing
 * a record are done as a pair, so removing a log never leaves the recording behind as an orphan. Only file pairing
 * lives here; size formatting (KB/MB text) is the UI's.
 *
 * Two audio extensions are recognised because the format depends on the platform and on the app version that made
 * the recording: WAV on the desktop (and on Android before AAC was introduced), AAC on Android since. A directory may
 * hold both, so every audio owner has to be looked at, not just the first.
 */
object SessionFiles {
    private const val PART = ".part"

    /** the extensions an audio file may carry, complete ones first */
    private val AUDIO_EXTENSIONS = listOf("wav", "aac")

    /** The stem shared by one session's files; null when [log] is not named like a session log. */
    private fun stemOf(log: File): String? = log.name.let { n ->
        when {
            n.endsWith(".jsonl$PART") -> n.removeSuffix(".jsonl$PART")
            n.endsWith(".jsonl") -> n.removeSuffix(".jsonl")
            else -> null
        }
    }

    /** Every complete and in-progress recording of [log]'s session, existing or not (normally at most one does). */
    private fun audioFiles(log: File): List<File> {
        val stem = stemOf(log) ?: return emptyList()
        val dir = log.parentFile ?: return emptyList()
        return AUDIO_EXTENSIONS.flatMap { ext -> listOf(File(dir, "$stem.$ext"), File(dir, "$stem.$ext$PART")) }
    }

    /**
     * The audio belonging to session log [log]: a complete recording before an in-progress one, WAV before AAC —
     * the first that exists, or null when the record has none.
     */
    fun audioOf(log: File): File? =
        audioFiles(log).sortedBy { if (it.name.endsWith(PART)) 1 else 0 }.firstOrNull { it.exists() }

    /**
     * Deletes session log [log] together with its recording. A log a session may still write ([SessionLog.isActive])
     * is refused — and its recording stays with it; a recording that cannot be deleted keeps its log, so a failed
     * delete never splits the pair. Deleting a log that is already gone still sweeps an orphaned recording.
     * @return true if the log is gone afterwards
     */
    fun deleteLog(log: File): Boolean {
        if (SessionLog.isActive(log)) return false
        if (audioFiles(log).any { it.exists() && !it.delete() }) return false
        return SessionLog.delete(log)
    }

    /**
     * Deletes every record (log and recording) no session may still write.
     * @return how many records are left (running or undeletable)
     */
    fun deleteAll(root: File): Int = SessionLog.list(root).count { !deleteLog(it) }

    /** Total size of every session log and recording (complete and `.part`) in [SessionLog.dir] below [root]. */
    fun totalBytes(root: File): Long = SessionLog.dir(root).listFiles { f ->
        f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".jsonl$PART") ||
            AUDIO_EXTENSIONS.any { f.name.endsWith(".$it") || f.name.endsWith(".$it$PART") })
    }?.sumOf { it.length() } ?: 0L
}
