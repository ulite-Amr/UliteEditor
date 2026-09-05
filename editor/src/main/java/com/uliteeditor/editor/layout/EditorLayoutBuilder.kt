package com.uliteeditor.editor.layout

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import uniffi.ulite_editor_core.EditorSession

/**
 * The laid-out geometry of the whole document for one rebuild: one
 * [TextLayoutResult] per logical row, laid out by real glyph measurement
 * (bidi, shaping, actual advances). This is the single source of truth the
 * caret, wrap, and tap hit-testing all read (mnemonic: what Compose draws is
 * what Compose hit-tests), replacing the old two-model design where Rust broke
 * lines from a uniform per-character width guess — the cause of mid-screen
 * wrap folds and the LTR-only caret.
 *
 * Row tops are the text-y of each row's layout in content space, so the
 * caret/gesture layers only ever add this layout's own offsets — never
 * recompute width math.
 */
internal data class RebuiltEditorLayout(
    val rowLayouts: List<TextLayoutResult>,
    val rowTops: List<Float>,
    val rowDirections: List<ResolvedTextDirection>,
    /** Per-logical-row manual trailing-run fold, or null when the row has none
     * (Bug B overflow). Null entries keep the list aligned with [rowLayouts]. */
    val trailingFolds: List<TrailingFold?>,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
    /** Total visual lines in the document: platform wrapped lines plus every
     * row's folded continuation lines. Unlike [rowLayouts].size (logical rows)
     * this is what a word processor would show as the line count. */
    val visualLines: Int,
)

/**
 * Measures the whole document once per rebuild. Requests only change what
 * this function re-runs: an edit, a resize, a font change, or a settings
 * toggle. The engine owns the buffer and cursor; this layer owns only
 * glyph-space geometry.
 */
internal fun buildEditorLayout(
    session: EditorSession,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    topMarginPx: Float,
    leftMarginPx: Float,
    rightPadPx: Float,
    wrapWidthPx: Float,
    wrapEnabled: Boolean,
): RebuiltEditorLayout {
    val rowCount = session.rowCount().toInt()
    val rowLayouts = mutableListOf<TextLayoutResult>()
    val rowTops = mutableListOf<Float>()
    val rowDirections = mutableListOf<ResolvedTextDirection>()
    val trailingFolds = mutableListOf<TrailingFold?>()
    var contentHeightPx = topMarginPx
    var maxLineWidthPx = 0f
    var visualLines = 0
    // A fixed-width constraint (min == max) makes TextMeasurer lay the
    // paragraph out across the full wrap width instead of collapsing the box
    // to the line's own content width. Inside a content-sized box TextAlign
    // is geometrically inert (a single non-wrapping line fills the whole
    // box), so an RTL paragraph would sit at the left with Left and Right
    // indistinguishable; bounding the box to the full width lets the row's
    // explicit alignment actually lean on its script.
    val wrapWidthInt = wrapWidthPx.toInt().coerceAtLeast(1)
    val wrapConstraints = Constraints(minWidth = wrapWidthInt, maxWidth = wrapWidthInt)
    for (row in 0 until rowCount) {
        val text = session.lineText(row.toULong())
        val direction = paragraphBaseDirection(text)
        // Alignment is fixed per row from the paragraph's own first strong
        // character. Explicit `Left`/`Right` (never `Start`/`End`) so the
        // paragraph leans on its script, not the app's implicit
        // LocalLayoutDirection (the editor is LTR-shelled and must not flip
        // under a different device direction). Wrapped lines inherit the
        // paragraph's alignment so an RTL paragraph soft-wraps to the right.
        val textAlign = when (direction) {
            ResolvedTextDirection.Rtl -> TextAlign.Right
            ResolvedTextDirection.Ltr -> TextAlign.Left
        }
        val layout = if (wrapEnabled) {
            textMeasurer.measure(
                AnnotatedString(text),
                textStyle.copy(textAlign = textAlign),
                softWrap = true,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
                constraints = wrapConstraints,
            )
        } else {
            // No-wrap still carries the paragraph's explicit alignment: the box
            // is only as wide as the line's own text, so it leans on its script
            // (Right for RTL, Left for LTR) once horizontally anchored — without
            // it an RTL row would default to Start (left) and contradict the
            // caret, which is drawn from the same layout.
            textMeasurer.measure(
                AnnotatedString(text),
                textStyle.copy(textAlign = textAlign),
                softWrap = false,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        }
        // Bug B fold: an RTL trailing-space run wider than the box is kept on
        // one platform line, so it is folded manually into continuation lines of
        // pure spaces (see TrailingFold); the extra height they occupy is added
        // below so the camera can scroll to them and later rows shift down.
        val fold = if (wrapEnabled) {
            buildTrailingFold(text, direction, layout, wrapWidthPx, textMeasurer, textStyle)
        } else {
            null
        }
        val lineHeightPx = layout.size.height.toFloat()
        trailingFolds += fold
        rowLayouts += layout
        rowTops += contentHeightPx
        rowDirections += direction
        contentHeightPx += (fold?.extraLines ?: 0) * lineHeightPx + lineHeightPx
        visualLines += (layout.lineCount + (fold?.extraLines ?: 0))
        if (!wrapEnabled) {
            maxLineWidthPx = maxOf(maxLineWidthPx, layout.size.width.toFloat())
        }
    }
    // Wrap locks horizontal scroll to the wrap width; no-wrap widens the
    // canvas to the longest row instead.
    val contentWidthPx = if (wrapEnabled) {
        leftMarginPx + rightPadPx + wrapWidthPx
    } else {
        leftMarginPx + rightPadPx + maxLineWidthPx
    }
    return RebuiltEditorLayout(
        rowLayouts = rowLayouts,
        rowTops = rowTops,
        rowDirections = rowDirections,
        trailingFolds = trailingFolds,
        contentWidthPx = contentWidthPx,
        contentHeightPx = contentHeightPx,
        visualLines = visualLines,
    )
}