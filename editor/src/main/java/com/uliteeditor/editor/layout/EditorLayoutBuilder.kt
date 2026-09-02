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
    val contentWidthPx: Float,
    val contentHeightPx: Float,
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
    var contentHeightPx = topMarginPx
    var maxLineWidthPx = 0f
    val wrapConstraints = Constraints(maxWidth = wrapWidthPx.toInt().coerceAtLeast(1))
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
            textMeasurer.measure(
                AnnotatedString(text),
                textStyle,
                softWrap = false,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Clip,
            )
        }
        rowLayouts += layout
        rowTops += contentHeightPx
        rowDirections += direction
        contentHeightPx += layout.size.height
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
        contentWidthPx = contentWidthPx,
        contentHeightPx = contentHeightPx,
    )
}