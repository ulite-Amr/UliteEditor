package com.uliteeditor.editor.render

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import com.uliteeditor.editor.layout.RebuiltEditorLayout

/**
 * Everything the editor canvas needs for one frame. Drawing is a pure
 * function of this state: the canvas reads exactly what the layout derived,
 * with no hidden mutable geometry.
 */
internal data class EditorDrawState(
    val scrollOffset: Offset,
    val rebuilt: RebuiltEditorLayout,
    val composingLayout: TextLayoutResult?,
    val caretRow: Int,
    val caretRowFirstTop: Float,
    val contentColor: Color,
    val caretColor: Color,
    val blinkVisible: Boolean,
    val caretX: Float,
    val caretY: Float,
    val caretWidthPx: Float,
    val caretHeightPx: Float,
    val leftMarginPx: Float,
)

/**
 * Draws the editor's content: the document rows, the live composing preview
 * over its caret row, and the caret. The whole body is translated by the
 * scroll offset so all draw coordinates stay in content space.
 *
 * Only the rows intersecting the visible viewport are drawn (a binary search
 * over the ascending row tops finds the window): the fling/pan camera never
 * repaints the whole document, which keeps long-document scrolling smooth.
 */
internal fun DrawScope.drawEditorContent(state: EditorDrawState) {
    translate(left = -state.scrollOffset.x, top = -state.scrollOffset.y) {
        val rowTops = state.rebuilt.rowTops
        // A row's own Top height is its platform layout's box, plus the folded
        // continuation lines (Bug B trailing-run overflow) that hang below it in
        // the same visual column — the draw window must not cull them away.
        val heightOf = { index: Int ->
            state.rebuilt.rowLayouts[index].size.height.toFloat() +
                (state.rebuilt.trailingFolds.getOrNull(index)?.extraHeightPx ?: 0f)
        }
        if (rowTops.isNotEmpty()) {
            val viewTop = state.scrollOffset.y
            val viewBottom = state.scrollOffset.y + size.height
            // First row whose bottom edge lies strictly below the viewport top
            // (a row that ends exactly at the top edge has zero overlap and is
            // skipped the same way the canvas clip would hide it).
            var low = 0
            var high = rowTops.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (rowTops[mid] + heightOf(mid) <= viewTop) low = mid + 1 else high = mid
            }
            val firstRow = low
            // Last row whose top edge lies above the viewport bottom.
            low = 0
            high = rowTops.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (rowTops[mid] < viewBottom) low = mid + 1 else high = mid
            }
            val lastRow = low - 1
            for (row in firstRow..lastRow) {
                // While composing, the caret's row is redrawn from the merged
                // composing layout below; skip its engine pieces so the
                // inserted composing text is not double-rendered.
                if (state.composingLayout != null && row == state.caretRow) {
                    continue
                }
                drawText(
                    textLayoutResult = state.rebuilt.rowLayouts[row],
                    color = state.contentColor,
                    topLeft = Offset(state.leftMarginPx, state.rebuilt.rowTops[row]),
                )
            }
        }
        state.composingLayout?.let { layout ->
            drawText(
                textLayoutResult = layout,
                color = state.contentColor,
                topLeft = Offset(state.leftMarginPx, state.caretRowFirstTop),
            )
        }
        if (state.blinkVisible) {
            drawRect(
                color = state.caretColor,
                topLeft = Offset(state.caretX, state.caretY),
                size = Size(state.caretWidthPx, state.caretHeightPx),
            )
        }
    }
}