package com.uliteeditor.app

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A tiny append-only, crash-safe session logger that writes to the app's
 * *internal* files dir — reachable from a phone with no adb by sharing the
 * file out (`shareLatest`, used by the "Share logs" action), or by browsing
 * `context.filesDir/logs`. Every line is flushed immediately so a process
 * that dies mid-repro still leaves everything written up to that point.
 *
 * The device operator is on a phone with no computer, so `logcat` is out of
 * reach; this file is the ground truth for caret/alignment debugging. It is
 * deliberately verbose where it matters (per-keystroke caret geometry) and is
 * meant to be switched off (or its call sites gated) once a bug is settled.
 */
internal object EditorLog {

    private val lock = ReentrantLock()
    private var dir: File? = null
    private var writer: BufferedWriter? = null
    private var currentLabel: String? = null
    private var lineCount = 0

    /** Rotate after a generous number of lines so the newest session survives. */
    private const val MAX_LINES = 40_000
    private const val MAX_KEPT_SESSIONS = 5

    /** Must be called once at app start (see [UliteApp]). Idempotent. */
    fun open(context: Context) = lock.withLock {
        // Idempotent: if a session is already open (e.g. open() was called from
        // both Application.onCreate and the first Activity.onStart), keep it.
        if (writer != null && currentLabel != null) return
        val logsDir = File(context.filesDir, "logs").apply { mkdirs() }
        dir = logsDir
        trimOldSessions(logsDir)
        val label = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(logsDir, "session-$label.txt")
        val w = BufferedWriter(FileWriter(file, true))
        writer = w
        currentLabel = label
        lineCount = 0
        w.appendLine("=== UliteEditor session $label ===")
        w.appendLine("sha ${BuildConfig.GIT_SHA}" + if (BuildConfig.GIT_DIRTY) " (dirty)" else "")
        w.flush()
    }

    /** Log one line with a timestamp. Safe from any thread. */
    fun info(message: String) = lock.withLock {
        val w = writer ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        w.appendLine("$ts  $message")
        w.flush()
        lineCount++
        if (lineCount >= MAX_LINES) {
            writer = null
            try { w.close() } catch (_: Exception) { /* best effort */ }
        }
    }

    /** Flush + close the current session. Idempotent. Called on Activity stop. */
    fun close() = lock.withLock {
        val w = writer ?: return
        val label = currentLabel ?: ""
        try {
            w.appendLine("=== end session $label ===")
            w.flush()
            w.close()
        } catch (_: Exception) { /* best effort */ }
        writer = null
        currentLabel = null
    }

    /**
     * The newest log file, ready to attach to a share sheet, or null when
     * nothing has been written yet. The caller builds an ACTION_SEND intent
     * with a FileProvider content URI from this path.
     */
    fun latestSessionFile(): File? = lock.withLock {
        dir?.listFiles()?.filter { it.name.startsWith("session-") && it.extension == "txt" }
            ?.maxByOrNull { it.name }
    }

    private fun trimOldSessions(logsDir: File) {
        val sessions = logsDir.listFiles()
            ?.filter { it.name.startsWith("session-") && it.extension == "txt" }
            ?.sortedByDescending { it.name }
            ?: return
        sessions.drop(MAX_KEPT_SESSIONS).forEach { runCatching { it.delete() } }
    }
}
