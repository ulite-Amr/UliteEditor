package com.uliteeditor.editor.effects

import androidx.compose.runtime.withFrameMillis
import com.uliteeditor.editor.bidi.TextIndex
import com.uliteeditor.editor.metrics.EditorMetrics
import uniffi.ulite_editor_core.EditorSession

/**
 * Per-frame fling driver. Each frame ticks the engine's scroll physics and
 * reports whether the camera moved, so the host can bump its repaint tick.
 */
internal suspend fun runFlingLoop(
    session: EditorSession,
    onScrolled: () -> Unit,
) {
    var lastFrameMillis = 0L
    while (true) {
        withFrameMillis { frameMillis ->
            if (lastFrameMillis != 0L) {
                val dtSeconds = (frameMillis - lastFrameMillis) / 1000f
                if (session.tickFling(dtSeconds)) {
                    onScrolled()
                }
            }
            lastFrameMillis = frameMillis
        }
    }
}

/**
 * Emits live metrics to the host once per frame, but only when a value
 * actually changed, so listeners can react without constant churn. The host's
 * callback lambda changes identity on every host recomposition, so it is read
 * through [listener] inside the stable loop instead of being captured.
 */
internal suspend fun runMetricsLoop(
    session: EditorSession,
    fontSizeSp: () -> Float,
    listener: () -> ((EditorMetrics) -> Unit)?,
) {
    if (listener() == null) return
    var last: EditorMetrics? = null
    while (true) {
        withFrameMillis {
            val cursor = session.cursor()
            val current = EditorMetrics(
                line = cursor.row,
                column = cursor.column,
                charIndex = TextIndex.utf16IndexAtByteOffset(
                    session.bufferText(),
                    TextIndex.absoluteByteOffsetOfCursor(session),
                ).toLong(),
                scrollX = session.scrollX(),
                scrollY = session.scrollY(),
                fontSizeSp = fontSizeSp(),
            )
            if (current != last) {
                last = current
                listener()?.invoke(current)
            }
        }
    }
}