package com.uliteeditor.app.sample

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import uniffi.ulite_editor_core.CursorPosition
import uniffi.ulite_editor_core.EditorSession
import uniffi.ulite_editor_core.VisualLine
import uniffi.ulite_editor_core.cursorScreenPosition
import uniffi.ulite_editor_core.locateTap

private const val FONT_SIZE_SP = 14
private const val LINE_HEIGHT_SP = 22
private const val TOP_MARGIN_DP = 12
private const val LEFT_MARGIN_DP = 12
private const val RIGHT_PAD_DP = 12
private const val CURSOR_WIDTH_DP = 2

/** `CursorManager.blinkRunnable` re-posted a blink every 500 ms (PORTING_NOTES row 20). */
private const val BLINK_PERIOD_MS = 500L

/** `CursorManager.moveTo` tweened the caret in 120 ms. */
private const val CARET_MOVE_ANIMATION_MS = 120

private val KEY_ROWS = listOf(
    listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
    listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
    listOf("Z", "X", "C", "V", "B", "N", "M", ",", "."),
)

/**
 * The interactive sample that owns a live [EditorSession] from the UniFFI
 * bridge and shows what "drive the Rust core" feels like: every keystroke
 * goes through [`EditorSession`], wrapping is computed in Rust from
 * [TextMeasurer] character widths, taps are hit-tested by `locateTap`,
 * the caret is placed by `cursorScreenPosition`, and the camera follows via
 * `ensureVisible` with a ported blink/move animation. Rendering and
 * measurement stay in Compose — the core stays measure-free — but the
 * document state the screen sees is the Rust buffer's.
 */
@Composable
fun EditorBridgeSample() {
    val session = remember { EditorSession() }
    val textMeasurer = rememberTextMeasurer()

    var wrapEnabled by remember { mutableStateOf(true) }
    var contentTick by remember { mutableIntStateOf(0) }
    var scrollTick by remember { mutableIntStateOf(0) }
    var blinkVisible by remember { mutableStateOf(true) }
    var editorSize by remember { mutableStateOf(IntSize.Zero) }
    var caretContent by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(session) {
        while (true) {
            delay(BLINK_PERIOD_MS)
            blinkVisible = !blinkVisible
        }
    }

    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val contentColor = MaterialTheme.colorScheme.onSurface
    val caretColor = MaterialTheme.colorScheme.primary
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = FONT_SIZE_SP.sp,
        lineHeight = LINE_HEIGHT_SP.sp,
    )
    val lineHeightPx = with(density) { LINE_HEIGHT_SP.sp.toPx() }
    val topMarginPx = with(density) { TOP_MARGIN_DP.dp.toPx() }
    val leftMarginPx = with(density) { LEFT_MARGIN_DP.dp.toPx() }
    val rightPadPx = with(density) { RIGHT_PAD_DP.dp.toPx() }
    val cursorWidthPx = with(density) { CURSOR_WIDTH_DP.dp.toPx() }
    val charWidthPx = with(density) {
        textMeasurer.measure(AnnotatedString("M"), textStyle).size.width.toFloat()
    }

    val viewportWidthPx = with(density) { editorSize.width.toPx() }
    val viewportHeightPx = with(density) { editorSize.height.toPx() }
    val wrapWidthPx = (viewportWidthPx - leftMarginPx - rightPadPx).coerceAtLeast(0f)

    val rebuilt = remember(session, textMeasurer, textStyle, charWidthPx, contentTick, wrapEnabled, wrapWidthPx) {
        buildEditorLayout(
            session = session,
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            charWidthPx = charWidthPx,
            lineHeightPx = lineHeightPx,
            topMarginPx = topMarginPx,
            leftMarginPx = leftMarginPx,
            rightPadPx = rightPadPx,
            wrapWidthPx = wrapWidthPx,
            wrapEnabled = wrapEnabled,
        )
    }
    val visualLines = rebuilt.visualLines

    LaunchedEffect(rebuilt.contentWidthPx, rebuilt.contentHeightPx, wrapWidthPx, viewportHeightPx) {
        session.updateBounds(
            rebuilt.contentWidthPx,
            rebuilt.contentHeightPx,
            viewportWidthPx,
            viewportHeightPx,
        )
    }

    // Read at composition so scroll frames (which only bump scrollTick)
    // actually repaint the canvas: the canvas lambda is rebuilt whenever the
    // keyed state changes, and reads session.scrollX/Y fresh on rebuild.
    val scrollOffset = remember(scrollTick + contentTick) {
        Offset(session.scrollX(), session.scrollY())
    }

    fun applyInteraction() {
        val caret = cursorScreenPosition(
            visualLines,
            session.cursor(),
            lineHeightPx,
            topMarginPx,
            leftMarginPx,
        )
        val viewWidth = with(density) { editorSize.width.toPx() }
        val viewHeight = with(density) { editorSize.height.toPx() }
        session.ensureVisible(caret.x, caret.y, lineHeightPx, viewWidth, viewHeight)
        caretContent = Offset(caret.x, caret.y)
        contentTick++
    }

    val animatedCaretX by animateFloatAsState(
        targetValue = caretContent.x,
        animationSpec = tween(CARET_MOVE_ANIMATION_MS),
        label = "caretX",
    )

    // Read caret visibility at composition so the blink repaints the canvas.
    val caretVisible = blinkVisible

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(MaterialTheme.colorScheme.background),
    ) {
        EditorStatusBar(
            cursor = session.cursor(),
            rowCount = session.rowCount(),
            scrollOffset = scrollOffset,
            wrapEnabled = wrapEnabled,
            onToggleWrap = {
                wrapEnabled = !wrapEnabled
                applyInteraction()
            },
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .onSizeChanged { editorSize = it }
                .pointerInput(rebuilt.visualLines) {
                    val velocityTracker = VelocityTracker()
                    var gestureStart: Offset? = null
                    var movedBeyondSlop = false
                    var panning = false
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull() ?: continue
                            val position = change.position
                            if (change.pressed) {
                                if (gestureStart == null) {
                                    gestureStart = position
                                    movedBeyondSlop = false
                                    panning = false
                                    velocityTracker.resetTracking()
                                } else if (!movedBeyondSlop) {
                                    val travelled = position - gestureStart!!
                                    if (travelled.getDistance() > viewConfiguration.touchSlop) {
                                        movedBeyondSlop = true
                                        session.startFling(0f, 0f)
                                    }
                                }
                                if (movedBeyondSlop) {
                                    val delta = position - change.previousPosition
                                    if (delta != Offset.Zero) {
                                        velocityTracker.addPosition(change.uptimeMillis, position)
                                        session.scrollBy(delta.x, delta.y)
                                        panning = true
                                        scrollTick++
                                    }
                                    change.consume()
                                }
                            } else {
                                if (panning) {
                                    val velocity = velocityTracker.calculateVelocity()
                                    session.startFling(velocity.x, velocity.y)
                                } else if (!movedBeyondSlop && gestureStart != null) {
                                    val hit = locateTap(
                                        visualLines,
                                        session.rowCount(),
                                        position.x + session.scrollX(),
                                        position.y + session.scrollY(),
                                        lineHeightPx,
                                        topMarginPx,
                                        leftMarginPx,
                                    )
                                    session.setCursor(CursorPosition(hit.row, hit.column))
                                    applyInteraction()
                                }
                                gestureStart = null
                                movedBeyondSlop = false
                                panning = false
                            }
                        }
                    }
                },
        ) {
            if (session.bufferText().isEmpty()) {
                Text(
                    text = "type with the keys below",
                    style = textStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(left = LEFT_MARGIN_DP.dp + 4.dp, top = TOP_MARGIN_DP.dp + 4.dp),
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                translate(left = -scrollOffset.x, top = -scrollOffset.y) {
                    for (index in rebuilt.drawLayouts.indices) {
                        drawText(
                            textLayoutResult = rebuilt.drawLayouts[index],
                            color = contentColor,
                            topLeft = Offset(leftMarginPx, rebuilt.drawTops[index]),
                        )
                    }
                    if (caretVisible) {
                        drawRect(
                            color = caretColor,
                            topLeft = Offset(animatedCaretX, caretContent.y),
                            size = Size(cursorWidthPx, lineHeightPx),
                        )
                    }
                }
            }
        }

        LaunchedEffect(session) {
            var lastFrameMillis = 0L
            while (true) {
                withFrameMillis { frameMillis ->
                    if (lastFrameMillis != 0L) {
                        val dtSeconds = (frameMillis - lastFrameMillis) / 1000f
                        if (session.tickFling(dtSeconds)) {
                            scrollTick++
                        }
                    }
                    lastFrameMillis = frameMillis
                }
            }
        }

        SampleKeyboard(
            onKey = {
                session.insertChar(it)
                applyInteraction()
            },
            onSpace = {
                session.insertChar(" ")
                applyInteraction()
            },
            onBackspace = {
                session.backspace()
                applyInteraction()
            },
            onEnter = {
                session.newline()
                applyInteraction()
            },
            onClear = {
                session.replaceContent("")
                applyInteraction()
            },
        )
    }

    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    BackHandler {
        if (session.bufferText().isNotEmpty()) {
            session.backspace()
            applyInteraction()
        } else {
            backDispatcher?.onBackPressed()
        }
    }
}

@Composable
private fun EditorStatusBar(
    cursor: CursorPosition,
    rowCount: ULong,
    scrollOffset: Offset,
    wrapEnabled: Boolean,
    onToggleWrap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "cursor ${cursor.row},${cursor.column} · rows $rowCount · " +
                "scroll ${scrollOffset.x.roundToInt()},${scrollOffset.y.roundToInt()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onToggleWrap) {
            Text("wrap: ${if (wrapEnabled) "on" else "off"}")
        }
    }
}

@Composable
private fun SampleKeyboard(
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (row in KEY_ROWS) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (key in row) {
                    KeyButton(label = key, onClick = { onKey(key) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KeyButton(label = "⌫", onClick = onBackspace)
            KeyButton(label = "space", onClick = onSpace, emphasis = true)
            KeyButton(label = "⏎", onClick = onEnter)
            KeyButton(label = "clear", onClick = onClear)
        }
    }
}

@Composable
private fun RowScope.KeyButton(
    label: String,
    onClick: () -> Unit,
    emphasis: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .weight(if (emphasis) 2f else 1f)
            .height(44.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

private data class RebuiltEditorLayout(
    val visualLines: List<VisualLine>,
    val drawLayouts: List<TextLayoutResult>,
    val drawTops: List<Float>,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
)

/**
 * Measures every logical row once and uses the engine's wrap to produce the
 * visual lines the sample draws and hit-tests against: Rust decides the
 * break points, Compose supplies the per-scalar widths and the text layout
 * (PORTING_NOTES rows 16–18 split). Each `VisualLine` mirrors one wrapped
 * segment (byte range + per-scalar widths), matching what
 * `locateTap`/`cursorScreenPosition` expect.
 */
private fun buildEditorLayout(
    session: EditorSession,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    charWidthPx: Float,
    lineHeightPx: Float,
    topMarginPx: Float,
    leftMarginPx: Float,
    rightPadPx: Float,
    wrapWidthPx: Float,
    wrapEnabled: Boolean,
): RebuiltEditorLayout {
    val rowCount = session.rowCount().toInt()
    val visualLines = mutableListOf<VisualLine>()
    val drawLayouts = mutableListOf<TextLayoutResult>()
    val drawTops = mutableListOf<Float>()
    var contentHeightPx = topMarginPx
    var widestRowPx = 0f
    for (row in 0 until rowCount) {
        val text = session.lineText(row.toULong())
        val scalarCount = Character.codePointCount(text, 0, text.length)
        widestRowPx = maxOf(widestRowPx, scalarCount * charWidthPx)
        val rowWidths = List(scalarCount) { charWidthPx }
        val wrapped = session.wrappedLines(
            row.toULong(),
            rowWidths,
            wrapWidthPx.toUInt(),
            wrapEnabled,
        )
        for (index in wrapped.indices) {
            val piece = wrapped[index]
            val pieceScalarCount = Character.codePointCount(piece.text, 0, piece.text.length)
            visualLines += VisualLine(
                row = row.toULong(),
                byteStart = piece.byteStart,
                text = piece.text,
                charWidths = List(pieceScalarCount) { charWidthPx },
            )
            drawLayouts += textMeasurer.measure(AnnotatedString(piece.text), textStyle)
            drawTops += contentHeightPx + index * lineHeightPx
        }
        contentHeightPx += wrapped.size * lineHeightPx
    }
    if (visualLines.isEmpty()) {
        visualLines += VisualLine(
            row = 0uL,
            byteStart = 0uL,
            text = "",
            charWidths = emptyList(),
        )
        drawLayouts += textMeasurer.measure(AnnotatedString(""), textStyle)
        drawTops += topMarginPx
        contentHeightPx = topMarginPx + lineHeightPx
    }
    val contentWidthPx = if (wrapEnabled) {
        // Wrap mode locks horizontal scroll to the wrap width, like the old engine.
        leftMarginPx + rightPadPx + wrapWidthPx
    } else {
        widestRowPx + leftMarginPx + rightPadPx
    }
    return RebuiltEditorLayout(
        visualLines = visualLines,
        drawLayouts = drawLayouts,
        drawTops = drawTops,
        contentWidthPx = contentWidthPx,
        contentHeightPx = contentHeightPx,
    )
}