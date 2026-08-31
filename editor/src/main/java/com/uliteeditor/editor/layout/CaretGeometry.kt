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
 * (`getParagraphDirection`), and the run is resolved from the *strong*
 * character that anchors the caret rather than from whatever neutral sits on
 * the boundary (see [caretRunDirection]): a trailing Space after an Arabic
 * run inherits the RTL run instead of collapsing the caret to the left. On
 * an empty or leading position the probe stays LTR (caret on the left),
 * matching a blank platform field.
 *
 * This is the deliberate divergence chosen for the mixed-line follow-up
 * (English paragraph with Arabic typed into it → right-side Arabic caret);
 * a mid-line RTL run that is *not* trailing keeps an absolute glyph
 * position, and the mirror for such a spot is an approximation of the
 * right-edge convention, not a bug.
 */
internal fun caretXIn(layout: TextLayoutResult, utf16: Int, leftMarginPx: Float): Float {
    val caretRect = layout.getCursorRect(utf16)
    val caretRun = caretRunDirection(layout, utf16)
    return when (caretRun) {
        ResolvedTextDirection.Rtl -> layout.size.width - caretRect.right + leftMarginPx
        ResolvedTextDirection.Ltr -> leftMarginPx + caretRect.left
    }
}

/**
 * Bidi direction of the run the caret at [utf16] actually edits, choosing a
 * strong character to anchor the mirror instead of the directionally-neutral
 * whitespace that usually sits on the caret boundary.
 *
 * A Space typed after an Arabic run is itself neutral, and a plain
 * `getBidiRunDirection(utf16 - 1)` on it falls through to LTR — the "caret
 * jumps far-left on Space" symptom. So the probe prefers, in order:
 * 1. A strong character AT the caret (index [utf16]) — the instant a strong
 *    char is typed it lands here, so the caret snaps back to that char's
 *    direction right away (typing Latin into an Arabic line flips the caret
 *    left immediately; ignoring this would let a Space, then a letter, keep
 *    the stale right-side caret for one extra char).
 * 2. The last strong character before the caret, walking back across
 *    trailing whitespace / NBSP (U+00A0) / ZWSP (U+200B) / lone low
 *    surrogates — so a Space typed after Arabic inherits the trailing RTL
 *    run and the caret stays on the right.
 * 3. LTR for an empty text or a fully-leading position, matching a blank
 *    platform field.
 */
private fun caretRunDirection(layout: TextLayoutResult, utf16: Int): ResolvedTextDirection {
    val text = layout.layoutInput.text.text
    if (text.isEmpty()) return ResolvedTextDirection.Ltr
    // 1. A strong char sits exactly at the caret boundary.
    if (utf16 in 0 until text.length && isStrongChar(text, utf16)) {
        return layout.getBidiRunDirection(utf16)
    }
    // 2. Walk back to the last strong char across trailing neutrals.
    var index = (utf16 - 1).coerceAtMost(text.length - 1)
    while (index >= 0) {
        if (isStrongChar(text, index)) return layout.getBidiRunDirection(index)
        index--
    }
    return ResolvedTextDirection.Ltr
}

private fun isStrongChar(text: String, index: Int): Boolean {
    val char = text[index]
    return when {
        char.isWhitespace() -> false
        // NBSP and ZWSP are non-breaking / zero-width spaces: neutral, so
        // they must not anchor the caret on their own.
        char == '\u00A0' || char == '\u200B' -> false
        // A lone low surrogate carries no code point; keep walking to its
        // high surrogate so surrogate pairs resolve as one strong char.
        char.isLowSurrogate() -> false
        else -> true
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