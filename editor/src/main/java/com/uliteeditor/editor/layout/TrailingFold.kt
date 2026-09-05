package com.uliteeditor.editor.layout

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection

/**
 * The manual visual fold for an RTL trailing-blank run that overflows the wrap
 * width (Bug B).
 *
 * [buildEditorLayout] lays each row out through Compose's softWrap, which is
 * the real wrap for anything with break opportunities — except one regime: the
 * platform never breaks trailing whitespace, so a run of spaces at a row's end
 * stays on the single final visual line and spills past the box edge no matter
 * how wide it gets. [caretXIn] used to paper over the caret's position with a
 * `prefix % wrapW` approximation, but the text itself never folded.
 *
 * This model folds the run for real, mirroring how an overflowing character
 * in a wrapped paragraph rolls onto the next right-aligned visual line: the
 * run fills the free space left on the base line (the platform's own single
 * line), then continues over one-line-tall rows of pure spaces, each holding
 * up to [perFull] of them. The fold is *arithmetic* — a trailing run is a run
 * of identical monospace blank glyphs, so no per-glyph shaping is needed — and
 * it is the single source of truth for caret x/y, tap hit-testing, and the
 * row's extra rendering height.
 *
 * Eligibility is deliberately strict (the Bug B regime): an RTL row whose
 * text ends in a run of plain spaces, whose platform layout kept the whole
 * row on ONE visual line (the prefix did not itself wrap), and whose base
 * content is narrower than the wrap box so the platform's single line has
 * room to report. Rows outside the gate keep today's behavior untouched
 * (including the [caretXIn] modulo emulation as the fallback). LTR trailing
 * blank overflow and non-space (NBSP/ZWSP) runs are out of scope.
 */
internal data class TrailingFold(
    /** First UTF-16 index of the trailing run in the row's text. */
    val runStartUtf16: Int,
    /** Number of plain `' '` characters in the run. */
    val runChars: Int,
    /** Measured advance of one space in the row's text style. */
    val spaceWidthPx: Float,
    /** The wrap box width the row was measured against. */
    val wrapWidthPx: Float,
    /** Width of the row text before the run (its unconstrained content). */
    val baseContentWidthPx: Float,
    /** Height of one visual line (the platform row layout's own height). */
    val lineHeightPx: Float,
) {
    /** Index after the run's last character, i.e. the row text length. */
    val runEndUtf16: Int = runStartUtf16 + runChars

    /** How many run spaces still fit on the platform's base line. */
    val fitOnBase: Int =
        ((wrapWidthPx - baseContentWidthPx) / spaceWidthPx).toInt().coerceAtLeast(0)

    /** Capacity of one continuation line (whole spaces that fit the box). */
    val perFull: Int = (wrapWidthPx / spaceWidthPx).toInt().coerceAtLeast(1)

    /** Run spaces that could not fit on the base line. */
    val overflow: Int = runChars - fitOnBase

    /** Continuation lines the overflow needs, each a full line of spaces. */
    val extraLines: Int = if (overflow > 0) (overflow + perFull - 1) / perFull else 0

    /** Vertical room the fold adds to the row, in content-space pixels. */
    val extraHeightPx: Float = extraLines * lineHeightPx

    /** A caret's spot on the folded layout: local line index and x (no margin). */
    data class FoldedLine(val line: Int, val x: Float)

    /**
     * Where the caret after the [n]'th run character (1-based, [n] = 0 means the
     * run start) sits once the run is folded. Returns null when the caret is on
     * the base line ([n] <= [fitOnBase]) and the platform layout already placed
     * it; otherwise the continuation line index (≥ 1) and the x of the space's
     * left edge measured from the row's left margin: right-aligned spaces walk
     * left from the box's right edge.
     */
    fun foldLineAndXForCaret(n: Int): FoldedLine? {
        if (n <= fitOnBase) return null
        val j = (n - fitOnBase).coerceAtLeast(1).coerceAtMost(overflow)
        val k = (j - 1) / perFull
        val g = (j - 1) % perFull + 1
        return FoldedLine(k + 1, wrapWidthPx - g * spaceWidthPx)
    }

    /**
     * Maps a tap on a continuation line back to the run's character count: the
     * line index from the tapped y (each continuation line is one [lineHeightPx]
     * tall, right after the platform's single base line), and the space count
     * from the tapped x (spaces fill right-aligned, so a tap at [xLocal] sits a
     * half-advance-rounded [round]((wrapWidthPx - xLocal) / spaceWidthPx) spaces
     * in from the right edge). Returns the absolute UTF-16 offset of the caret
     * landing, clamped to the row's text length.
     */
    fun caretOffsetAt(xLocal: Float, yLocal: Float): Int {
        val k = ((yLocal - lineHeightPx) / lineHeightPx).toInt().coerceAtLeast(0)
        val g = Math.round((wrapWidthPx - xLocal) / spaceWidthPx).coerceIn(1, perFull)
        val n = fitOnBase + k * perFull + g
        return (runStartUtf16 + n).coerceAtMost(runEndUtf16)
    }
}

/**
 * Builds the fold for one logical row, or null when the row is outside the
 * eligibility gate (see [TrailingFold]). [layout] is the platform measurement
 * [buildEditorLayout] just made for [text], so the fold and the drawn text can
 * never disagree about the base line.
 */
internal fun buildTrailingFold(
    text: String,
    direction: ResolvedTextDirection,
    layout: TextLayoutResult,
    wrapWidthPx: Float,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
): TrailingFold? {
    if (direction != ResolvedTextDirection.Rtl) return null
    if (wrapWidthPx <= 0f) return null
    // The fold models a run on top of a single base line; a prefix that
    // already wraps is a different regime and keeps platform behavior.
    if (layout.lineCount != 1) return null
    val runStart = trailingSpaceRunStart(text) ?: return null
    val runChars = text.length - runStart
    val spaceWidthPx = textMeasurer.measure(AnnotatedString(" "), textStyle).size.width.toFloat()
    if (spaceWidthPx <= 0f) return null
    val baseContentWidthPx = textMeasurer.measure(
        AnnotatedString(text.substring(0, runStart)),
        textStyle,
    ).size.width.toFloat()
    // A prefix as wide as the box is the unbreakable-word regime (it stayed on
    // the single line only because nothing in it can break); out of scope.
    if (baseContentWidthPx >= wrapWidthPx) return null
    val fold = TrailingFold(
        runStartUtf16 = runStart,
        runChars = runChars,
        spaceWidthPx = spaceWidthPx,
        wrapWidthPx = wrapWidthPx,
        baseContentWidthPx = baseContentWidthPx,
        lineHeightPx = layout.size.height.toFloat(),
    )
    return if (fold.extraLines > 0) fold else null
}

/** Index of the first character of the row's trailing run of plain spaces, or
 * null when the row does not end in spaces (a run stuck mid-line does not
 * overflow past the final line the way Bug B's does). */
private fun trailingSpaceRunStart(text: String): Int? {
    var index = text.length - 1
    while (index >= 0 && text[index] == ' ') index--
    return if (index < text.length - 1) index + 1 else null
}