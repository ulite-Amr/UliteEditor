package com.uliteeditor.editor.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * IME-facing state for the Compose layer, ported from the old editor's
 * cursor model (`CursorManager.cursorRow/cursorCol` plus its blink and
 * idle-hide timers).
 *
 * Pure-Compose host until the Rust core is bridged (UniFFI/JNI follow-up
 * task): it owns the edited line's content and the caret offset, both in
 * the units the eventual bridge will serve. Blink and idle-hide timings
 * match `CursorManager` exactly.
 */
class EditorImeState {

    /** The edited line's content. A single line today; becomes a full buffer on bridging. */
    var content: String by mutableStateOf("")

    /** Caret offset within [content] in UTF-16 code units (the old cursor-col model). */
    var caretOffset: Int by mutableIntStateOf(0)

    /** True while the caret should draw; the input connection toggles this on focus. */
    var isInputActive: Boolean by mutableStateOf(false)

    /** Wall-clock ms of the last edit or tap; compared against [HIDE_AFTER_IDLE_MS]. */
    var lastInteractedAtMillis: Long by mutableLongStateOf(0L)

    /** Clamps an offset into [0, lineLength] so UI taps always land on the line. */
    fun caretClamped(lineLength: Int, offset: Int = caretOffset): Int = offset.coerceIn(0, lineLength)

    companion object {
        /** `CursorManager.blinkRunnable` re-posted a blink every 500 ms. */
        const val BLINK_PERIOD_MS = 500L

        /** `CursorManager` hid the caret after a full second without input. */
        const val HIDE_AFTER_IDLE_MS = 1_000L
    }
}