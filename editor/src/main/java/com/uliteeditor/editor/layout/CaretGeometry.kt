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
 * The computed x is the run edge for the direction returned by
 * [caretAnchorDirection] ([inputDirection] is the active keyboard language, so
 * a caret on a BiDi run boundary hugs the side you are actually typing into):
 * LTR caret at the rect's left edge, RTL caret mirrored against the layout's
 * right edge (`layoutWidth - caretRect.right`, the same mirror androidx
 * `TextFieldCoreModifier.getCursorRectInScroller` applies — without it an
 * Arabic run leaves its caret on the English/left side).
 *
 * A caret facing a run of *trailing* blanks (end-of-line Space after an Arabic
 * run, NBSP, or ZWSP) cannot trust [TextLayoutResult.getCursorRect]: the
 * platform resolves a trailing neutral flat (UBA rule L1) and its caret spots
 * at and past the blank run collapse onto the preceding run's edge, so the
 * caret looks glued (a typed Space never advances it) and Backspace appears
 * to snap across the boundary. For that one regime x is rebuilt from the
 * stable rect at the last non-blank character before the run
 * ([trailingNeutralAnchorBefore]) plus the *measured* advance across
 * everything up to the caret on the anchor side (RTL: −width left, LTR:
 * +width right). The anchor char's own advance is included, which is exactly
 * the distance a caret crossing it must travel, so the position is correct
 * whether or not the platform preserved the blank widths. Every other
 * position — non-blank carets, mid-line blank runs, leading blank runs — is
 * placed by the platform as-is.
 *
 * The anchoring scans in [caretAnchorDirection] also step past bidi-neutral
 * punctuation, digits and format controls (see [isScanNeutralCodePoint]), so
 * a trailing `!`, `؟` or digit sequence after an Arabic run inherits the RTL
 * run exactly as trailing Space does; a trailing blank run after punctuation
 * (e.g. `مرحبا! `) anchors on the punctuation char, whose rect the platform
 * does not flatten.
 *
 * Mirrors are decided per run from the strong character that anchors the
 * caret (script class), never from the neutral that sits on the boundary.
 * A mid-line RTL run that is *not* trailing keeps an absolute glyph position,
 * and the mirror for such a spot is an approximation of the right-edge
 * convention, not a bug.
 */
internal fun caretXIn(
    layout: TextLayoutResult,
    utf16: Int,
    leftMarginPx: Float,
    textStyle: TextStyle,
    textMeasurer: TextMeasurer,
    inputDirection: ResolvedTextDirection?,
): Float {
    val text = layout.layoutInput.text.text
    if (text.isEmpty()) return leftMarginPx
    val caret = utf16.coerceIn(0, text.length)
    val decided = caretAnchorDirection(text, caret, inputDirection)
    val rect = layout.getCursorRect(caret)
    val baseX = when (decided) {
        ResolvedTextDirection.Rtl -> layout.size.width - rect.right + leftMarginPx
        ResolvedTextDirection.Ltr -> leftMarginPx + rect.left
    }
    // Rebuild the x for a collapsed trailing-blank caret (see doc above);
    // anything non-trailing returns the platform-rect base right away.
    val anchor = trailingNeutralAnchorBefore(text, caret) ?: return baseX
    val tail = text.substring(anchor, caret)
    val advance = measureAdvance(tail, textStyle, textMeasurer)
    val anchorRect = layout.getCursorRect(anchor)
    return when (decided) {
        ResolvedTextDirection.Rtl -> layout.size.width - anchorRect.right + leftMarginPx - advance
        ResolvedTextDirection.Ltr -> leftMarginPx + anchorRect.left + advance
    }
}

private fun measureAdvance(tail: String, textStyle: TextStyle, textMeasurer: TextMeasurer): Float {
    if (tail.isEmpty()) return 0f
    return textMeasurer.measure(AnnotatedString(tail), textStyle).size.width.toFloat()
}

/**
 * The strong-character direction that governs the caret at UTF-16 [utf16],
 * preferring (in order):
 *  1. A strong character exactly at the caret — the instant a strong char is
 *     typed it anchors immediately (typing Latin into an Arabic line flips
 *     the caret left right away).
 *  2. The nearest strong character across neutrals on either side (whitespace
 *     plus bidi-neutral punctuation, digits and format controls): if both
 *     sides agree, that direction; if only one side has a strong char, that
 *     one (so a trailing Space — or a trailing `!`, `؟`, or digits — after
 *     Arabic inherits the RTL run, and a leading position on an RTL start
 *     hugs the right edge); an empty text stays LTR (blank-field match).
 *  3. When the nearest strongs bracket the caret in *opposite* directions — a
 *     true LTR↔RTL BiDi run boundary — [inputDirection] (the active keyboard
 *     language) picks the side you are typing into, falling back to the left
 *     run when there is no keyboard language signal.
 *
 * Direction is decided from the strong characters' script class (RTL: Arabic,
 * Hebrew and the other RTL blocks; everything else strong is LTR); the mirror
 * in [caretXIn] uses this decided direction, so no [TextLayoutResult]
 * is needed here and the rule is fully unit-testable.
 */
internal fun caretAnchorDirection(
    text: String,
    utf16: Int,
    inputDirection: ResolvedTextDirection?,
): ResolvedTextDirection {
    if (text.isEmpty()) return ResolvedTextDirection.Ltr
    // 1. A strong char exactly at the caret boundary.
    if (utf16 in 0 until text.length) {
        strongCharDirection(text, utf16)?.let { return it }
    }
    // 2/3. Nearest strong char on each side, across neutrals.
    val left = lastStrongDirectionBefore(text, utf16)
    val right = firstStrongDirectionAfter(text, utf16)
    return when {
        left != null && right == null -> left
        right != null && left == null -> right
        left != null && left == right -> left
        left != null && right != null -> inputDirection ?: left
        else -> ResolvedTextDirection.Ltr
    }
}

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
 * Bidi-neutral for the anchoring scans: Unicode punctuation (P*), decimal
 * digits (Nd — the UBA EN/AN classes) and format controls (F, incl. ZWJ, ZWNJ
 * and the bidi control marks) are not strong, so the scans in
 * [caretAnchorDirection] step across them to the nearest strong character —
 * a trailing `!`/`؟`/digit run after an Arabic run inherits the RTL run
 * instead of flipping the caret to LTR. Kept separate from
 * [isNeutralCodePoint] because caret-width reservation ([neutralRunAtCaret])
 * concerns only blank characters.
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

/** Last strong direction scanning left from just before [utf16]; null if none. */
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
 * The contiguous run of direction-neutral characters touching UTF-16 [utf16],
 * as an inclusive [kotlin.ranges.IntRange], or null when [utf16] is not on a
 * neutral run. If the caret is just past end-of-text, the run is the maximal
 * neutral tail ending at [utf16]. Only plain space, NBSP and ZWSP are treated
 * as neutral here (interior whitespace is already placed by the platform).
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
    val anchor = run.first - 1
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
    inputDirection: ResolvedTextDirection?,
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
        x = caretXIn(layout, utf16, leftMarginPx, textStyle, textMeasurer, inputDirection),
        y = rebuilt.rowTops[row] + top,
    )
}