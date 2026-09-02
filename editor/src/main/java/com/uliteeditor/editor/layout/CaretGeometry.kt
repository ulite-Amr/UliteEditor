package com.uliteeditor.editor.layout

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.uliteeditor.editor.bidi.TextIndex
import uniffi.ulite_editor_core.CursorPosition

/** The caret's visual spot in content space (x includes the left margin). */
internal data class CaretSpot(val x: Float, val y: Float)

/**
 * X, in content space, of the caret at UTF-16 [utf16] inside [layout].
 *
 * The caret sits at the platform rect's left edge in every case — there is
 * deliberately **no** RTL mirror (no `layoutWidth - rect.right`) and no
 * direction-dependent rebuild. Compose already resolves bidi internally, so
 * [TextLayoutResult.getCursorRect] returns the caret's *visual* x for an RTL
 * run just as it does for an LTR one; paragraph alignment (which side of the
 * viewport a run leans on) is applied at layout time in [buildEditorLayout],
 * not here.
 *
 * The old mirror was lifted from androidx
 * `TextFieldCoreModifier.getCursorRectInScroller`, which serves a single-line,
 * horizontally scrolling, `LayoutDirection.Ltr`-forced scroller container —
 * none of which this plain, multi-line, unfixed-direction Text has. Applying
 * that mirror here double-inverted a position that was already correct: it
 * only appeared right on paragraphs that were entirely base-RTL (where the
 * inversion cancels), and was wrong for a paragraph that began LTR and later
 * gained Arabic, or on a trailing blank after an Arabic run (where the
 * inversion was effectively applied twice).
 */
internal fun caretXIn(
    layout: TextLayoutResult,
    utf16: Int,
    leftMarginPx: Float,
): Float {
    val text = layout.layoutInput.text.text
    if (text.isEmpty()) return leftMarginPx
    val caret = utf16.coerceIn(0, text.length)
    val rect = layout.getCursorRect(caret)
    return leftMarginPx + rect.left
}

/**
 * The paragraph's base direction, derived from its first strong character
 * (the UBA first-strong rule): a line that opens with an RTL character
 * (Arabic, Hebrew, …) is a right-to-left paragraph for layout alignment, and
 * one that opens with any strong Latin/other character is LTR; a line with no
 * strong character at all (blank) defaults to LTR, matching Compose's default.
 *
 * This is the single source of truth for *alignment* (where the paragraph sits
 * horizontally against the margin). The paragraph base direction never changes
 * as text is appended — the first strong char is fixed — so it is computed once
 * per row at layout time and reused by layout and caret alike. (The caret no
 * longer reads it directly: since the RTL mirror and the trailing-blank rebuild
 * were removed, the caret sits at the platform rect's left in every case.)
 */
internal fun paragraphBaseDirection(text: String): ResolvedTextDirection =
    firstStrongDirectionAfter(text, 0) ?: ResolvedTextDirection.Ltr

/** Direction of the strong character at UTF-16 [index], or null when neutral. */
private fun strongCharDirection(text: String, index: Int): ResolvedTextDirection? {
    val codePoint = text.codePointAt(index)
    return when {
        isNeutralCodePoint(codePoint) || isScanNeutralCodePoint(codePoint) -> null
        isRtlCodePoint(codePoint) -> ResolvedTextDirection.Rtl
        else -> ResolvedTextDirection.Ltr
    }
}

/**
 * Bidi-neutral for the first-strong paragraph scan: Unicode punctuation (P*),
 * decimal digits (Nd — the UBA EN/AN classes) and format controls (F, incl.
 * ZWJ, ZWNJ and the bidi control marks) are not strong, so [paragraphBaseDirection]
 * steps past them to the first strong character (a paragraph that opens with
 * `!`, `؟` or digits borrows the direction of the strong char after them).
 */
private fun isScanNeutralCodePoint(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.START_PUNCTUATION,
        Character.END_PUNCTUATION,
        Character.OTHER_PUNCTUATION,
        Character.DASH_PUNCTUATION,
        Character.CONNECTOR_PUNCTUATION,
        Character.INITIAL_QUOTE_PUNCTUATION,
        Character.FINAL_QUOTE_PUNCTUATION,
        Character.DECIMAL_DIGIT_NUMBER,
        Character.FORMAT,
        -> true
        else -> false
    }

private fun isNeutralCodePoint(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) ||
        codePoint == '\u00A0'.code ||
        codePoint == '\u200B'.code

private fun isRtlCodePoint(codePoint: Int): Boolean = when {
    codePoint in 0x0590..0x05FF -> true // Hebrew
    codePoint in 0x0600..0x06FF -> true // Arabic
    codePoint in 0x0700..0x074F -> true // Syriac
    codePoint in 0x0750..0x077F -> true // Arabic Supplement
    codePoint in 0x0780..0x07BF -> true // Thaana
    codePoint in 0x07C0..0x07FF -> true // NKo
    codePoint in 0x0840..0x085F -> true // Mandaic
    codePoint in 0x0870..0x089F -> true // Arabic Extended-B
    codePoint in 0x08A0..0x08FF -> true // Arabic Extended-A
    codePoint in 0xFB50..0xFDFF -> true // Arabic Presentation Forms-A
    codePoint in 0xFE70..0xFEFF -> true // Arabic Presentation Forms-B
    else -> false
}

/** First strong direction scanning right from [utf16]; null if none. */
private fun firstStrongDirectionAfter(text: String, utf16: Int): ResolvedTextDirection? {
    var index = utf16
    while (index < text.length) {
        strongCharDirection(text, index)?.let { return it }
        index += Character.charCount(text.codePointAt(index))
    }
    return null
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
    val rowText = layout.layoutInput.text.text
    val utf16 = TextIndex.utf16IndexAtByteOffset(
        rowText,
        cursor.column.toLong(),
    )
    val caretRect = layout.getCursorRect(utf16)
    return CaretSpot(
        x = caretXIn(layout, utf16, leftMarginPx),
        y = rebuilt.rowTops[row] + caretRect.top,
    )
}
