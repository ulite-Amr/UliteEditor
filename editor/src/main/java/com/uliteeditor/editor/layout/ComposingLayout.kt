package com.uliteeditor.editor.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * Measures the caret's row with the composing span inserted at the caret so
 * every observed keystroke is visible immediately, in its real position.
 * [composing] is the IME's live composing text (already capped at the first
 * newline by the caller); the engine buffer stays unchanged until the span is
 * released, and this preview vanishes the moment the IME commits.
 *
 * [textAlign] mirrors the caret row's own paragraph alignment so the preview
 * leans on the same side as the real row it replaces; it is applied to a copy
 * of [textStyle] (alignment is a `TextStyle` property — `TextMeasurer.measure`
 * has no `textAlign` parameter), in the wrapped branch only.
 */
internal fun measureComposingLayout(
    row: String,
    composing: String,
    caretUtf16: Int,
    composingColor: Color,
    textStyle: TextStyle,
    wrapWidthPx: Float,
    wrapEnabled: Boolean,
    textAlign: TextAlign,
    textMeasurer: TextMeasurer,
): TextLayoutResult {
    val merged = buildAnnotatedString {
        append(row.substring(0, caretUtf16))
        val composingStart = length
        append(composing)
        addStyle(
            SpanStyle(color = composingColor),
            composingStart,
            composingStart + composing.length,
        )
        append(row.substring(caretUtf16))
    }
    return if (wrapEnabled) {
        val wrapWidthInt = wrapWidthPx.toInt().coerceAtLeast(1)
        // Fixed-width (min == max) constraint like [buildEditorLayout]: bound
        // the preview to the full wrap width so the row's alignment leans on
        // its script instead of being invisible inside a content-sized box.
        textMeasurer.measure(
            merged,
            textStyle.copy(textAlign = textAlign),
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            constraints = Constraints(minWidth = wrapWidthInt, maxWidth = wrapWidthInt),
        )
    } else {
        // No-wrap composing preview mirrors [buildEditorLayout]'s no-wrap row:
        // explicit alignment so an RTL preview leans right once anchored.
        textMeasurer.measure(
            merged,
            textStyle.copy(textAlign = textAlign),
            softWrap = false,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
    }
}