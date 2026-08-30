package com.uliteeditor.editor.layout

import androidx.compose.ui.text.TextLayoutResult
import com.uliteeditor.editor.bidi.TextIndex
import uniffi.ulite_editor_core.CursorPosition

/** The caret's visual spot in content space (x includes the left margin). */
internal data class CaretSpot(val x: Float, val y: Float)

/**
 * X, in content space, of the caret at UTF-16 [utf16] inside [layout].
 * `getCursorRect` is bidi-aware and valid at offset == end-of-line (unlike
 * getBoundingBox), returning the caret spot inside this exact row layout —
 * which is also the one we draw.
 */
internal fun caretXIn(layout: TextLayoutResult, utf16: Int, leftMarginPx: Float): Float {
    val caretRect = layout.getCursorRect(utf16)
    return leftMarginPx + caretRect.left
}

/** Y offset of the caret inside [layout] at UTF-16 [utf16], relative to the row's own top. */
internal fun caretTopIn(layout: TextLayoutResult, utf16: Int): Float =
    layout.getCursorRect(utf16).top

/**
 * The caret spot derived from the exact laid-out row under the cursor
 * (invariant: the caret comes from the same row layouts the text is drawn
 * from, never from a stale layout computed inside an input handler — otherwise
 * edits that reflow, especially wrap, leave the caret a row away from where
 * text is inserted).
 */
internal fun steadyCaretSpot(
    rebuilt: RebuiltEditorLayout,
    cursor: CursorPosition,
    leftMarginPx: Float,
): CaretSpot {
    val row = cursor.row.toInt().coerceIn(0, rebuilt.rowLayouts.lastIndex)
    val layout = rebuilt.rowLayouts[row]
    val utf16 = TextIndex.utf16IndexAtByteOffset(
        layout.layoutInput.text.text,
        cursor.column.toLong(),
    )
    val caretRect = layout.getCursorRect(utf16)
    return CaretSpot(
        x = caretXIn(layout, utf16, leftMarginPx),
        y = rebuilt.rowTops[row] + caretTopIn(layout, utf16),
    )
}