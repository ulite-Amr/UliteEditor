package com.uliteeditor.editor.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints

/**
 * The IME-held text of the field's current composing span, capped at the
 * first newline. While the IME holds text in composition (autocorrect /
 * suggestions / multi-tap), the engine buffer stays unchanged until the
 * span is released — without help, the canvas draws nothing new and typing
 * looks dead. The preview re-renders the caret's row with this text inserted
 * at the caret, tinted to mark it unreleased. The engine stays authoritative;
 * the preview vanishes as soon as the IME commits.
 */
internal fun composingTextOf(imeField: TextFieldValue): String? {
    val span = imeField.composition ?: return null
    val start = span.min.coerceIn(0, imeField.text.length)
    val end = span.max.coerceIn(start, imeField.text.length)
    if (start >= end) return null
    // A composition crossing a newline would re-flow the whole row from a
    // stale top; preview only up to the first break, the rest commits
    // normally on release.
    return imeField.text.substring(start, end).substringBefore('\n').takeIf { it.isNotEmpty() }
}

/**
 * Measures the caret's row with the composing span inserted at the caret so
 * every observed keystroke is visible immediately, in its real position.
 */
internal fun measureComposingLayout(
    row: String,
    composing: String,
    caretUtf16: Int,
    composingColor: Color,
    textStyle: TextStyle,
    wrapWidthPx: Float,
    wrapEnabled: Boolean,
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
        textMeasurer.measure(
            merged,
            textStyle,
            softWrap = true,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
            constraints = Constraints(maxWidth = wrapWidthPx.toInt().coerceAtLeast(1)),
        )
    } else {
        textMeasurer.measure(
            merged,
            textStyle,
            softWrap = false,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Clip,
        )
    }
}