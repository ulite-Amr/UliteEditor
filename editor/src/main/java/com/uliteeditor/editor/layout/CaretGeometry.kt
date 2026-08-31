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
 * getBoundingBox): it returns `layout.getPrimaryHorizontal(offset)`. When a
 * run is right-flushed (a trailing RTL run, or an RTL line), those
 * coordinates are measured from the run's right edge, so the x must be
 * mirrored the way the platform field does it
 * (`layoutWidth - caretRect.right`, cf. androidx
 * `TextFieldCoreModifier.getCursorRectInScroller`) — without the mirror, an
 * Arabic run leaves its caret back on the English/left side.
 *
 * The mirror is decided per *run* (`getBidiRunDirection`), not per paragraph
 * (`getParagraphDirection`), and the run is looked up one offset before the
 * caret (`max(utf16 - 1, 0)`): `getBidiRunDirection` resolves the run that
 * *contains* the offset, and at a run's boundary or the end of a line it
 * falls through to LTR (AOSP `Layout.isRtlCharAt`). The caret sits exactly
 * on such a boundary while typing forward, so probing the caret offset
 * itself would always pick the left branch there; stepping back picks the
 * run the caret actually edits, the same trick the platform's selection
 * handles use. On an empty or leading position the probe is offset 0,
 * which has no run and stays LTR (caret on the left), matching a blank
 * platform field.
 *
 * This is the deliberate divergence chosen for the mixed-line follow-up
 * (English paragraph with Arabic typed into it → right-side Arabic caret);
 * a mid-line RTL run that is *not* trailing keeps an absolute glyph
 * position, and the mirror for such a spot is an approximation of the
 * right-edge convention, not a bug.
 */
internal fun caretXIn(layout: TextLayoutResult, utf16: Int, leftMarginPx: Float): Float {
    val caretRect = layout.getCursorRect(utf16)
    // The character immediately before the caret — see the KDoc above.
    val caretRun = layout.getBidiRunDirection((utf16 - 1).coerceAtLeast(0))
    return when (caretRun) {
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