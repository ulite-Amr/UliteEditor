package com.uliteeditor.editor.layout

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import com.uliteeditor.editor.bidi.TextIndex
import uniffi.ulite_editor_core.CursorPosition

/** The caret's visual spot in content space (x includes the left margin). */
internal data class CaretSpot(val x: Float, val y: Float)

/**
 * X, in content space, of the caret at UTF-16 [utf16] inside [layout].
 *
 * The caret sits at the platform rect's left edge in every case — there is
 * deliberately **no** RTL mirror (no `layoutWidth - rect.right`). Compose
 * already resolves bidi internally, so [TextLayoutResult.getCursorRect]
 * returns the caret's *visual* x for an RTL run just as it does for an LTR
 * one. Paragraph alignment (which side of the viewport a run leans on) is
 * applied at layout time in [buildEditorLayout], not here.
 *
 * One regime is not trusted: a caret facing a *trailing* run of blanks
 * (end-of-line Space/NBSP/ZWSP). The platform resolves a trailing neutral
 * flat (UBA rule L1): its caret spots at and past the blank run collapse onto
 * the preceding run's edge, so the caret looks glued (a typed Space never
 * advances it) and Backspace appears to snap across the boundary — the
 * "Space doesn't advance" and RTL end-of-text misplacement bugs. For that one
 * regime the x is rebuilt from the stable rect at the last non-blank char
 * before the run ([trailingNeutralAnchorBefore]) plus the *measured* advance
 * across everything up to the caret on the anchor side (RTL: −width left,
 * LTR: +width right). The anchor char's own advance is included, which is
 * exactly the distance a caret crossing it must travel. This is what the
 * absolute-position layout tests pin; without it, `rtlTrailingSpace*` and the
 * wrapped-blank tests fail.
 *
 * The rebuild's direction sign comes from the trailing run's own strong
 * character (the anchor's run direction) via [lastStrongDirectionBefore] —
 * never from an input-language signal, so the IME input-direction subsystem
 * stays removed.
 */
internal fun caretXIn(
    layout: TextLayoutResult,
    utf16: Int,
    leftMarginPx: Float,
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
): Float {
    val text = layout.layoutInput.text.text
    if (text.isEmpty()) return leftMarginPx
    val caret = utf16.coerceIn(0, text.length)
    val rect = layout.getCursorRect(caret)
    val baseX = leftMarginPx + rect.left
    // Rebuild a collapsed trailing-blank caret (see doc above); every other
    // position returns the platform rect's left as-is.
    val anchor = trailingNeutralAnchorBefore(text, caret) ?: return baseX
    val tail = text.substring(anchor, caret)
    val advance = measureAdvance(tail, textStyle, textMeasurer)
    val anchorRect = layout.getCursorRect(anchor)
    val anchorDirection = lastStrongDirectionBefore(text, anchor + 1) ?: ResolvedTextDirection.Ltr
    // The rebuild pins the caret's *magnitude* to the measured advance; only
    // its sign differs by the trailing run's direction (RTL walks left/−, LTR
    // walks right/+). The anchor side is the platform rect's left either way.
    return leftMarginPx + anchorRect.left +
        (if (anchorDirection == ResolvedTextDirection.Rtl) -advance else advance)
}

private fun measureAdvance(tail: String, textStyle: TextStyle, textMeasurer: TextMeasurer): Float {
    if (tail.isEmpty()) return 0f
    return textMeasurer.measure(AnnotatedString(tail), textStyle).size.width.toFloat()
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
 * per row at layout time and reused by layout and caret alike. (The caret does
 * not read this value directly: the no-RTL-mirror [caretXIn] returns the
 * platform rect's left, and the restored trailing-blank rebuild picks its sign
 * from the trailing run's own strong char via [lastStrongDirectionBefore], so
 * both stay consistent with a paragraph whose base direction is already fixed.)
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

/**
 * Last strong direction scanning left from just before [utf16]; null if none.
 * Used by the trailing-blank rebuild in [caretXIn] to learn the trailing run's
 * own direction (the rule for a trailing blank: the strong char is only on the
 * caret's left, so the direction is that run's, independent of any keyboard
 * language signal).
 */
private fun lastStrongDirectionBefore(text: String, utf16: Int): ResolvedTextDirection? {
    var index = utf16 - 1
    while (index >= 0) {
        // Rewind from a low surrogate to its high surrogate so codePointAt
        // reads the whole pair as one strong character.
        val start = if (index > 0 && text[index].isLowSurrogate()) index - 1 else index
        strongCharDirection(text, start)?.let { return it }
        index = start - 1
    }
    return null
}

/**
 * The contiguous run of direction-neutral blank characters touching UTF-16
 * [utf16], as an inclusive [kotlin.ranges.IntRange], or null when [utf16] is
 * not on a neutral run. If the caret is just past end-of-text, the run is the
 * maximal neutral tail ending at [utf16]. Only plain space, NBSP and ZWSP are
 * treated as neutral here (interior whitespace is already placed by the
 * platform).
 */
internal fun neutralRunAtCaret(text: String, caretUtf16: Int): IntRange? {
    fun isNeutral(index: Int) = index in 0 until text.length &&
        (text[index] == ' ' || text[index] == '\u00A0' || text[index] == '\u200B')
    val start: Int
    val endExclusive: Int
    if (caretUtf16 == text.length) {
        var i = caretUtf16 - 1
        while (isNeutral(i)) i--
        start = i + 1
        endExclusive = caretUtf16
    } else {
        if (!isNeutral(caretUtf16)) return null
        var e = caretUtf16
        while (isNeutral(e)) e++
        var s = caretUtf16
        while (isNeutral(s - 1)) s--
        start = s
        endExclusive = e
    }
    return if (endExclusive > start) start..(endExclusive - 1) else null
}

/**
 * The index of the character that visually anchors a caret facing a *trailing*
 * blank run (Space/NBSP/ZWSP): the last non-blank char directly before the
 * run, or null when [caret] does not face one. A trailing blank run reaches
 * end-of-text, so the caret at or past the run's first char is on the L1
 * flattening zone [caretXIn] rebuilds; a run that does not reach end-of-text
 * (mid-line) or starts at 0 (wholly-blank line) has no such flattening and
 * returns null. [caret] is clamped to [text]'s length so an end-of-text caret
 * reports the run's anchor.
 */
internal fun trailingNeutralAnchorBefore(text: String, caret: Int): Int? {
    val run = neutralRunAtCaret(text, caret.coerceIn(0, text.length)) ?: return null
    if (run.last != text.length - 1) return null
    var anchor = run.first - 1
    // The char before the run may be the LOW surrogate of a supplementary
    // code point (e.g. `"😀 "`): the anchor, the measured substring and the
    // platform rect must all start at the pair's high surrogate, not inside
    // the pair (a lone low surrogate measures a replacement box).
    while (anchor > 0 && text[anchor].isLowSurrogate()) anchor--
    return if (anchor >= 0) anchor else null
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
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
): CaretSpot {
    val row = cursor.row.toInt().coerceIn(0, rebuilt.rowLayouts.lastIndex)
    val layout = rebuilt.rowLayouts[row]
    val rowText = layout.layoutInput.text.text
    val utf16 = TextIndex.utf16IndexAtByteOffset(
        rowText,
        cursor.column.toLong(),
    )
    val caretRect = layout.getCursorRect(utf16)
    // A collapsed trailing-blank caret keeps its own rect's line (the blank
    // run owns a visual line when it wraps), but when the caret rect's line
    // and the trailing anchor's line tie for the caret, prefer the *lower* of
    // the two rows so the caret never floats to a neighbor line's leading
    // character (wrapped trailing-whitespace misalignment).
    val anchorBefore = trailingNeutralAnchorBefore(rowText, utf16)
    val top = if (anchorBefore != null) {
        maxOf(caretRect.top, layout.getCursorRect(anchorBefore).top)
    } else {
        caretRect.top
    }
    return CaretSpot(
        x = caretXIn(layout, utf16, leftMarginPx, textStyle, textMeasurer),
        y = rebuilt.rowTops[row] + top,
    )
}
