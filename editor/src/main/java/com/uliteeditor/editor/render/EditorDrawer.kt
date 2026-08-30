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
    val composingColor: Color,
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
 */
internal fun DrawScope.drawEditorContent(state: EditorDrawState) {
    translate(left = -state.scrollOffset.x, top = -state.scrollOffset.y) {
        for (row in state.rebuilt.rowLayouts.indices) {
            // While composing, the caret's row is redrawn from the merged
            // composing layout below; skip its engine pieces so the inserted
            // composing text is not double-rendered.
            if (state.composingLayout != null && row == state.caretRow) {
                continue
            }
            drawText(
                textLayoutResult = state.rebuilt.rowLayouts[row],
                color = state.contentColor,
                topLeft = Offset(state.leftMarginPx, state.rebuilt.rowTops[row]),
            )
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