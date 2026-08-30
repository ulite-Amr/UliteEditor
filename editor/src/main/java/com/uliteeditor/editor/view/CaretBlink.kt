package com.uliteeditor.editor.view

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.uliteeditor.editor.EditorDimensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Port of `CursorManager.resetBlink`: the caret stays solid while the user
 * is editing or has just moved it, and only starts blinking after
 * [EditorDimensions.BLINK_RESET_MS] of inactivity. Every applied edit
 * (typing/deleting), every caret move via tap, and focus-in re-arms it;
 * focus-out hides the caret instantly (same "visible && focused" gate as the
 * old code).
 */
internal class CaretBlink(
    private val scope: CoroutineScope,
) {
    var visible by mutableStateOf(true)
        private set

    private var blinkJob: Job? = null

    /** Keeps the caret solid and schedules the idle blink cycle. */
    fun reset() {
        blinkJob?.cancel()
        blinkJob = null
        visible = true
        blinkJob = scope.launch {
            delay(EditorDimensions.BLINK_RESET_MS)
            while (true) {
                delay(EditorDimensions.BLINK_PERIOD_MS)
                visible = !visible
            }
        }
    }

    /** Hides the caret the instant focus leaves. */
    fun hide() {
        blinkJob?.cancel()
        blinkJob = null
        visible = false
    }
}