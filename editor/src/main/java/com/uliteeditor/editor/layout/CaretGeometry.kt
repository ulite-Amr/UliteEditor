package com.uliteeditor.editor.layout

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.uliteeditor.editor.bidi.TextIndex
import uniffi.ulite_editor_core.CursorPosition

/** The caret's visual spot in content space (x includes the left margin). */
internal data class CaretSpot(val x: Float, val y: Float)

/**
 * X, in content space, of the caret at UTF-16 [utf16] inside [layout].
 * `getCursorRect` is bidi-aware and valid at offset == end-of-line (unlike
 * getBoundingBox). RTL runs are measured from the line's right edge, so the
 * x must be mirrored exactly the way the platform's own field does it
 * (`layoutWidth - caretRect.right`, cf. androidx
 * `TextFieldCoreModifier.getCursorRectInScroller`) — without the mirror, an
 * Arabic run leaves its caret back on the English/left side.
 *
 * The mirror is decided per *run* (`getBidiRunDirection`), not per paragraph
 * (`getParagraphDirection`). A line whose base direction is LTR (it starts
 * with a strong LTR letter) but that has Arabic text typed in it still
 * places its caret on the right while editing that Arabic run — the
 * platform-edit-text behavior. The paragraph-level check put every caret on
 * the left of such a line even while typing Arabic (the on-device
 * misalignment): paragraph direction only governs base alignment, not the
 * caret of a mirror-embedded run. A neutral offset resolves as the run's
 * direction; an empty line has no run and stays LTR (caret on the left),
 * matching a platform field before any text is typed.
 */
internal fun caretXIn(layout: TextLayoutResult, utf16: Int, leftMarginPx: Float): Float {
    val caretRect = layout.getCursorRect(utf16)
    return when (layout.getBidiRunDirection(utf16)) {
        ResolvedTextDirection.Rtl -> layout.size.width - caretRect.right + leftMarginPx
        ResolvedTextDirection.Ltr -> leftMarginPx + caretRect.left
    }
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