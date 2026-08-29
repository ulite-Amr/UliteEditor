package com.uliteeditor.editor.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorImeStateTest {

    @Test
    fun caretClampsToLineBounds() {
        val state = EditorImeState()
        assertEquals(5, state.caretClamped(lineLength = 5, offset = 12))
        assertEquals(0, state.caretClamped(lineLength = 5, offset = -2))
        assertEquals(3, state.caretClamped(lineLength = 3, offset = 3))
    }

    @Test
    fun blinkTimingsMatchTheOldCursorManager() {
        assertEquals(500L, EditorImeState.BLINK_PERIOD_MS)
        assertEquals(1_000L, EditorImeState.HIDE_AFTER_IDLE_MS)
    }
}