package com.uliteeditor.editor.view

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToPx
import com.uliteeditor.editor.ime.EditorImeState
import java.lang.System

/**
 * The editor's primary screen.
 *
 * Renders the buffered line (or the empty-buffer hint), places the caret at
 * the tapped offset, and blinks it on the timings `CursorManager` used (500 ms
 * period, hidden after a second of idleness). Text is laid out by Compose
 * itself — the wrap/hit-test geometry moves from this screen into the Rust
 * core once the bridge lands (see `crates/ulite-editor-core`).
 */
@Composable
fun EditorScreen(state: EditorImeState = remember { EditorImeState() }) {
    val infiniteTransition = rememberInfiniteTransition(label = "caretBlink")
    val caretAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = EditorImeState.BLINK_PERIOD_MS.toInt()),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretAlpha",
    )

    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val content = state.content
    val textPadding = 16.dp
    val density = LocalDensity.current

    Box(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    textLayout?.let { layout ->
                        state.caretOffset = layout.getOffsetForPosition(position)
                        state.lastInteractedAtMillis = System.currentTimeMillis()
                    }
                }
            },
    ) {
        if (content.isEmpty()) {
            Text(
                text = "Your notes start here",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(textPadding),
            )
        } else {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                onTextLayout = { textLayout = it },
                modifier = Modifier.padding(textPadding),
            )
        }

        if (state.isInputActive) {
            val rect = textLayout?.getCursorRect(state.caretOffset)
            val isIdle = System.currentTimeMillis() - state.lastInteractedAtMillis >= EditorImeState.HIDE_AFTER_IDLE_MS
            Caret(
                alpha = if (isIdle) 0f else caretAlpha,
                heightPx = rect?.height ?: with(density) { 20.dp.toPx() },
                modifier = Modifier.offset {
                    with(density) {
                        IntOffset(
                            x = (textPadding + (rect?.left ?: 0f).toDp()).roundToPx(),
                            y = (textPadding + (rect?.top ?: 0f).toDp()).roundToPx(),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun Caret(
    alpha: Float,
    heightPx: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(2.dp)
            .height(heightPx.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
    )
}