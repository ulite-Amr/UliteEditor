package com.uliteeditor.editor.view

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

/**
 * sora-editor's pinch clamp: `EditorTouchEventHandler` keeps the text size
 * inside `scaleMinSize`..`scaleMaxSize` (8..26 sp). The base font is
 * `FONT_SIZE_SP`; scaling moves the font (a `setTextSize`-style mechanism)
 * and never the canvas transform.
 */
private const val MIN_FONT_SIZE_SP = 8f
private const val MAX_FONT_SIZE_SP = 26f

/**
 * The reusable editor composable: it owns a live [EditorSession] behind the
 * UniFFI bridge and renders it like a word processor — wrapping is decided
 * in Rust from measured character widths, taps are hit-tested by
 * `locateTap`, the caret is placed by `cursorScreenPosition`, and the
 * camera follows via `ensureVisible`.
 *
 * This is a library component, not a screen: it takes a plain [modifier]
 * so any host (activity, split pane, preview) can embed it. Input comes
 * from the *system* keyboard through an invisible [BasicTextField] bound to
 * the engine buffer — there is no built-in on-screen keyboard.
 *
 * Three invariants hold the component together:
 * - The caret is always *derived* from the same laid-out [visualLines]
 *   version that the text is drawn from (keyed `remember`), never computed
 *   from a stale layout inside an input handler — otherwise edits that
 *   reflow (especially wrap) leave the caret a row away from where text is
 *   inserted.
 * - Scroll input follows the finger: drag/fling deltas are negated before
 *   reaching the core camera, matching Android/sora-editor conventions.
 * - The engine buffer is the single source of truth; the IME field is just
 *   a pipe that gets a fresh authoritative TextFieldValue every edit
 *   (the real InputConnection-backed editor is a follow-up, see PROGRESS).
 */
@Composable
fun EditorComponent(
    modifier: Modifier = Modifier,
) {
    val session = remember { EditorSession() }
    val textMeasurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    var contentTick by remember { mutableIntStateOf(0) }
    var scrollTick by remember { mutableIntStateOf(0) }
    var blinkVisible by remember { mutableStateOf(true) }
    var editorSize by remember { mutableStateOf(IntSize.Zero) }
    var editing by remember { mutableStateOf(false) }
    var scaling by remember { mutableStateOf(false) }
    var fontSizeSp by remember { mutableFloatStateOf(FONT_SIZE_SP.toFloat()) }

    var imeField by remember { mutableStateOf(TextFieldValue(session.bufferText())) }
    val interactionScope = rememberCoroutineScope()

    fun syncImeField() {
        val current = session.bufferText()
        val selection = TextRange(
            utf16IndexAtByteOffset(current, absoluteByteOffsetOfCursor(session)),
        )
        val next = TextFieldValue(current, selection = selection)
        // Skip identical rewrites: setting the value again resets the IME's
        // composing/suggestion state, which reads as flicker while typing.
        if (imeField != next) imeField = next
    }

    LaunchedEffect(session) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(session) {
        while (true) {
            delay(BLINK_PERIOD_MS)
            blinkVisible = !blinkVisible
        }
    }

    // Every engine edit re-syncs the invisible field to the authoritative
    // buffer (invariant 3); the callback path already syncs immediately, so
    // this also covers edits that did not start at the keyboard.
    LaunchedEffect(contentTick) {
        if (contentTick > 0) syncImeField()
    }

    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val contentColor = MaterialTheme.colorScheme.onSurface
    val caretColor = MaterialTheme.colorScheme.primary
    // Pinch zoom scales the font (sora-editor's mechanism: setTextSize,
    // keep the paint/canvas un-transformed); line height follows the font
    // ratio, while margins and the caret stay physical-pixel fixed.
    val lineHeightSp = fontSizeSp * (LINE_HEIGHT_SP.toFloat() / FONT_SIZE_SP.toFloat())
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
    )
    val lineHeightPx = with(density) { lineHeightSp.sp.toPx() }
    val topMarginPx = with(density) { TOP_MARGIN_DP.dp.toPx() }
    val leftMarginPx = with(density) { LEFT_MARGIN_DP.dp.toPx() }
    val rightPadPx = with(density) { RIGHT_PAD_DP.dp.toPx() }
    val cursorWidthPx = with(density) { CURSOR_WIDTH_DP.dp.toPx() }
    val charWidthPx = with(density) {
        textMeasurer.measure(AnnotatedString("M"), textStyle).size.width.toFloat()
    }

    val viewportWidthPx = (editorSize.width.toFloat()).coerceAtLeast(0f)
    val viewportHeightPx = (editorSize.height.toFloat()).coerceAtLeast(0f)
    val wrapWidthPx = (viewportWidthPx - leftMarginPx - rightPadPx).coerceAtLeast(0f)

    val rebuilt = remember(session, textMeasurer, textStyle, charWidthPx, contentTick, wrapWidthPx) {
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
        )
    }
    val visualLines = rebuilt.visualLines

    // Keep the gesture loop alive across layout rebuilds: a pointerInput
    // keyed on visualLines would cancel mid-pinch every time the zoom
    // re-measures the layout. The handler reads the *latest* lines through
    // this state instead.
    val visualLinesState = remember { mutableStateOf(emptyList<VisualLine>()) }
    visualLinesState.value = visualLines

    // Same "live, not captured" treatment for the pixel metrics the gesture
    // loop reads (line height grows with the font during a pinch).
    val lineHeightPxState = remember { mutableStateOf(lineHeightPx) }
    lineHeightPxState.value = lineHeightPx

    LaunchedEffect(rebuilt.contentWidthPx, rebuilt.contentHeightPx, wrapWidthPx, viewportHeightPx) {
        session.updateBounds(
            rebuilt.contentWidthPx,
            rebuilt.contentHeightPx,
            viewportWidthPx,
            viewportHeightPx,
        )
        scrollTick++
    }

    // Read at composition so scroll frames (which only bump scrollTick)
    // actually repaint the canvas: the canvas lambda is rebuilt whenever
    // the keyed state changes, and reads session.scrollX/Y fresh on rebuild.
    val scrollOffset = remember(scrollTick + contentTick) {
        Offset(session.scrollX(), session.scrollY())
    }

    // The caret is derived from this exact layout version (invariant 1):
    // same visual lines that draw the text, same line-height/margin px
    // that positioned them. Keying on the cursor as well keeps taps that
    // move the caret without editing the buffer in sync.
    val cursor = session.cursor()
    // Taps move the caret without touching the buffer; the invisible IME
    // field must learn the new selection or the next keystroke edits at the
    // stale spot (the "caret jumps back to where it last edited" symptom).
    // The no-op skip in syncImeField keeps an already-correct field from
    // being rewritten mid-edit.
    LaunchedEffect(cursor) {
        syncImeField()
    }
    val caretContent = remember(rebuilt, cursor, lineHeightPx, topMarginPx, leftMarginPx) {
        val point = cursorScreenPosition(
            rebuilt.visualLines,
            cursor,
            lineHeightPx,
            topMarginPx,
            leftMarginPx,
        )
        Offset(point.x, point.y)
    }

    // ensure_visible must not fight the pinch's focus-anchored scroll while
    // a scale is in flight; it settles only when the gesture ends.
    LaunchedEffect(caretContent, lineHeightPx, viewportWidthPx, viewportHeightPx, scaling) {
        if (scaling) return@LaunchedEffect
        if (session.ensureVisible(caretContent.x, caretContent.y, lineHeightPx, viewportWidthPx, viewportHeightPx)) {
            scrollTick++
        }
    }

    // The caret tween snaps instantly during a pinch (sora skips caret
    // animation while the size is changing) and resumes after.
    val animatedCaretX by animateFloatAsState(
        targetValue = caretContent.x,
        animationSpec = if (scaling) tween(0) else tween(CARET_MOVE_ANIMATION_MS),
        label = "caretX",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { editorSize = it }
            .pointerInput(session) {
                val velocityTracker = VelocityTracker()
                var gestureStart: Offset? = null
                var movedBeyondSlop = false
                var panning = false
                var pinchCentroid = Offset.Zero
                var pinchSpan = 0f
                // A finger already down when a pinch ends must not register
                // as a fresh tap on release (it was part of the scale); any
                // genuinely new finger-down clears this.
                var suppressTap = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isNotEmpty()) {
                            event.changes.forEach { it.consume() }
                        }
                        val lines = visualLinesState.value
                        if (active.size >= 2 && !scaling) {
                            // A second finger starts the pinch: cancel any
                            // fling/pan and lock the reference span/centroid.
                            session.startFling(0f, 0f)
                            scaling = true
                            panning = false
                            gestureStart = null
                            movedBeyondSlop = false
                            pinchCentroid = (active[0].position + active[1].position) / 2f
                            pinchSpan = (active[0].position - active[1].position).getDistance()
                            continue
                        }
                        if (scaling) {
                            if (active.size >= 2) {
                                val first = active.take(2)
                                val newCentroid = (first[0].position + first[1].position) / 2f
                                val newSpan = (first[0].position - first[1].position).getDistance()
                                val scaleFactor = if (pinchSpan > 0f) newSpan / pinchSpan else 1f
                                if (scaleFactor != 1f && scaleFactor.isFinite()) {
                                    // Font-size grows/shrinks with the gesture,
                                    // clamped to sora's [8sp, 26sp] input range.
                                    val newSize = (fontSizeSp * scaleFactor)
                                        .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
                                    val effective = newSize / fontSizeSp
                                    if (effective != 1f) {
                                        // Keep the content under the pinch's focal
                                        // point pinned: newScroll = (oldScroll +
                                        // focus) * factor - focus (sora's formula).
                                        session.setScroll(
                                            (session.scrollX() + pinchCentroid.x) * effective - pinchCentroid.x,
                                            (session.scrollY() + pinchCentroid.y) * effective - pinchCentroid.y,
                                        )
                                        fontSizeSp = newSize
                                    }
                                }
                                // Centroid movement pans the document under the fingers.
                                val centroidDelta = newCentroid - pinchCentroid
                                if (centroidDelta != Offset.Zero) {
                                    session.scrollBy(-centroidDelta.x, -centroidDelta.y)
                                    pinchCentroid = newCentroid
                                }
                                scrollTick++
                            } else {
                                // One finger lifted: the pinch ends. The
                                // composition-side ensure_visible effect keys
                                // on `scaling` and settles the caret now. A
                                // still-down finger is scale residue, not a tap.
                                scaling = false
                                suppressTap = true
                            }
                            continue
                        }
                        val down = active.firstOrNull()
                        if (down != null) {
                            val position = down.position
                            if (down.pressed && !down.previousPressed) {
                                suppressTap = false
                            }
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
                                val delta = position - down.previousPosition
                                if (delta != Offset.Zero) {
                                    velocityTracker.addPosition(down.uptimeMillis, position)
                                    // Content follows the finger: the drag delta is negated
                                    // before it reaches the core camera (invariant 2).
                                    session.scrollBy(-delta.x, -delta.y)
                                    panning = true
                                    scrollTick++
                                }
                            }
                        }
                        val released = event.changes.firstOrNull { !it.pressed }
                        if (released != null) {
                            if (panning) {
                                val velocity = velocityTracker.calculateVelocity()
                                session.startFling(-velocity.x, -velocity.y)
                            } else if (!movedBeyondSlop && gestureStart != null && !suppressTap) {
                                val hit = locateTap(
                                    lines,
                                    session.rowCount(),
                                    released.position.x + session.scrollX(),
                                    released.position.y + session.scrollY(),
                                    lineHeightPxState.value,
                                    topMarginPx,
                                    leftMarginPx,
                                )
                                session.setCursor(CursorPosition(hit.row, hit.column))
                                focusRequester.requestFocus()
                                // A back press hid the keyboard; focus alone
                                // won't relaunch it after keyboardController.hide().
                                // Delay the re-show one frame so a pending hide
                                // finishes first (composition scope hosts it;
                                // the gesture event scope is restricted).
                                interactionScope.launch {
                                    withFrameMillis { }
                                    keyboardController?.show()
                                }
                                scrollTick++
                            }
                            gestureStart = null
                            movedBeyondSlop = false
                            panning = false
                        }
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            translate(left = -scrollOffset.x, top = -scrollOffset.y) {
                for (index in rebuilt.drawLayouts.indices) {
                    drawText(
                        textLayoutResult = rebuilt.drawLayouts[index],
                        color = contentColor,
                        topLeft = Offset(leftMarginPx, rebuilt.drawTops[index]),
                    )
                }
                if (blinkVisible) {
                    drawRect(
                        color = caretColor,
                        topLeft = Offset(animatedCaretX, caretContent.y),
                        size = Size(cursorWidthPx, lineHeightPx),
                    )
                }
            }
        }

        // Invisible IME pipe: one 1 dp field whose text is always snap-synced
        // to the engine buffer (invariant 3). Programmatic writes do not fire
        // onValueChange, so there are no feedback loops.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(1.dp),
        ) {
            BasicTextField(
                value = imeField,
                onValueChange = { newValue ->
                    if (applyImeEdit(session, newValue.text)) {
                        contentTick++
                    }
                    syncImeField()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .onFocusChanged { editing = it.isFocused },
                textStyle = TextStyle(color = Color.Transparent, fontSize = 16.sp),
                cursorBrush = SolidColor(Color.Transparent),
            )
        }
    }

    BackHandler {
        if (editing) {
            // First back: drop the keyboard and the field's focus; the next
            // back falls through to the host's default (exit).
            focusManager.clearFocus()
            keyboardController?.hide()
        } else {
            backDispatcher?.onBackPressed()
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
}

/**
 * Applies a system-keyboard text delta on top of the engine buffer.
 *
 * The IME hands us the full new string; we diff it against the authoritative
 * buffer to recover the edit as core operations (insertChar / backspace /
 * replaceContent). The diff walks Unicode code points so surrogate pairs and
 * combining sequences stay intact; edits that cross a line boundary fall back
 * to a wholesale replaceContent.
 *
 * This is the sample-level pipe (single keystroke fidelity until the engine
 * exposes a proper InputConnection - see PROGRESS.md).
 */
private fun applyImeEdit(session: EditorSession, newText: String): Boolean {
    val oldText = session.bufferText()
    if (newText == oldText) return false

    val prefix = commonCodePointPrefix(oldText, newText)
    val totalOld = oldText.codePointCount(0, oldText.length)
    val totalNew = newText.codePointCount(0, newText.length)
    val suffix = commonCodePointSuffix(oldText, newText, prefix)
    val prefixBytes = utf8Length(codePointSlice(oldText, 0, prefix))
    val removed = codePointSlice(oldText, prefix, totalOld - prefix - suffix)
    val inserted = codePointSlice(newText, prefix, totalNew - prefix - suffix)
    val removedBytes = utf8Length(removed)

    if (removed.contains('\n') || inserted.contains('\n')) {
        // Multi-line edit: rebuild the buffer and park the caret at the end
        // of the surviving prefix (its row/col are re-derived from the result).
        session.replaceContent(newText)
        val (row, col) = rowColAtByteOffset(newText, prefixBytes)
        session.setCursor(CursorPosition(row.toULong(), col.toULong()))
        return true
    }

    if (removed.isNotEmpty()) {
        val (delRow, delCol) = rowColAtByteOffset(oldText, prefixBytes + removedBytes)
        session.setCursor(CursorPosition(delRow.toULong(), delCol.toULong()))
        repeat(removed.codePointCount(0, removed.length)) { session.backspace() }
    }

    if (inserted.isNotEmpty()) {
        val (insRow, insCol) = rowColAtByteOffset(newText, prefixBytes)
        session.setCursor(CursorPosition(insRow.toULong(), insCol.toULong()))
        var insertedBytes = 0
        var index = 0
        while (index < inserted.length) {
            val codePoint = inserted.codePointAt(index)
            index += Character.charCount(codePoint)
            val char = String(Character.toChars(codePoint))
            session.insertChar(char)
            insertedBytes += utf8Length(char)
        }
        session.setCursor(CursorPosition(insRow.toULong(), (insCol + insertedBytes).toULong()))
    }
    return true
}

/** Code points shared at the start of [a] and [b]. */
private fun commonCodePointPrefix(a: String, b: String): Int {
    var indexA = 0
    var indexB = 0
    var count = 0
    while (indexA < a.length && indexB < b.length) {
        val codePointA = a.codePointAt(indexA)
        val codePointB = b.codePointAt(indexB)
        if (codePointA != codePointB) break
        count++
        indexA += Character.charCount(codePointA)
        indexB += Character.charCount(codePointB)
    }
    return count
}

/** Code points shared at the end of [a] and [b], after [prefixCount] shared head code points. */
private fun commonCodePointSuffix(a: String, b: String, prefixCount: Int): Int {
    val maxA = a.codePointCount(0, a.length) - prefixCount
    val maxB = b.codePointCount(0, b.length) - prefixCount
    val max = minOf(maxA, maxB)
    var count = 0
    var endA = a.length
    var endB = b.length
    while (count < max) {
        val codePointA = codePointBefore(a, endA)
        val codePointB = codePointBefore(b, endB)
        if (codePointA != codePointB) break
        count++
        endA -= Character.charCount(codePointA)
        endB -= Character.charCount(codePointB)
    }
    return count
}

/** The code point ending just before character index [endCharIdx] in [s]. */
private fun codePointBefore(s: String, endCharIdx: Int): Int {
    val last = endCharIdx - 1
    return if (last >= 1 && s[last].isLowSurrogate() && s[last - 1].isHighSurrogate()) {
        Character.toCodePoint(s[last - 1], s[last])
    } else {
        s[last].code
    }
}

/** [countCp] code points of [s] starting at code point [startCp], as a string. */
private fun codePointSlice(s: String, startCp: Int, countCp: Int): String {
    if (countCp <= 0) return ""
    var startChar = 0
    repeat(startCp) { startChar += Character.charCount(s.codePointAt(startChar)) }
    var endChar = startChar
    repeat(countCp) { endChar += Character.charCount(s.codePointAt(endChar)) }
    return s.substring(startChar, endChar)
}

/** The (row, column) of byte offset [byteOffset] in [text], where rows are split on '\n'. */
private fun rowColAtByteOffset(text: String, byteOffset: Int): Pair<Int, Int> {
    var row = 0
    var rowStartBytes = 0
    var bytes = 0
    var index = 0
    while (bytes < byteOffset && index < text.length) {
        val codePoint = text.codePointAt(index)
        val length = utf8LengthOfCodePoint(codePoint)
        if (bytes + length > byteOffset) break
        bytes += length
        index += Character.charCount(codePoint)
        if (codePoint == '\n'.code) {
            row++
            rowStartBytes = bytes
        }
    }
    return row to (byteOffset - rowStartBytes)
}

/** Byte offset of the cursor (sum of row byte-lengths before it, plus its column). */
private fun absoluteByteOffsetOfCursor(session: EditorSession): Long {
    val cursor = session.cursor()
    var bytes = cursor.column.toLong()
    for (row in 0 until cursor.row.toInt()) {
        bytes += utf8Length(session.lineText(row.toULong())).toLong() + 1L
    }
    return bytes
}

/** UTF-16 character index of byte offset [byteOffset] in [buffer]. */
private fun utf16IndexAtByteOffset(buffer: String, byteOffset: Long): Int {
    var bytes = 0L
    var charIndex = 0
    while (charIndex < buffer.length && bytes < byteOffset) {
        val codePoint = buffer.codePointAt(charIndex)
        val length = utf8LengthOfCodePoint(codePoint)
        if (bytes + length > byteOffset) break
        bytes += length
        charIndex += Character.charCount(codePoint)
    }
    return charIndex
}

private fun utf8LengthOfCodePoint(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

private data class RebuiltEditorLayout(
    val visualLines: List<VisualLine>,
    val drawLayouts: List<TextLayoutResult>,
    val drawTops: List<Float>,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
)

/**
 * Measures every logical row once and uses the engine's wrap to produce the
 * visual lines the editor draws and hit-tests against: Rust decides the
 * break points, Compose supplies the per-scalar widths and the text layout
 * (PORTING_NOTES rows 16-18 split). Each [VisualLine] mirrors one wrapped
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
): RebuiltEditorLayout {
    val rowCount = session.rowCount().toInt()
    val visualLines = mutableListOf<VisualLine>()
    val drawLayouts = mutableListOf<TextLayoutResult>()
    val drawTops = mutableListOf<Float>()
    var contentHeightPx = topMarginPx
    for (row in 0 until rowCount) {
        val text = session.lineText(row.toULong())
        val scalarCount = Character.codePointCount(text, 0, text.length)
        val rowWidths = List(scalarCount) { charWidthPx }
        val wrapped = session.wrappedLines(
            row.toULong(),
            rowWidths,
            wrapWidthPx.toUInt(),
            true,
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
    // Wordwrap locks horizontal scroll to the wrap width, like the old engine.
    val contentWidthPx = leftMarginPx + rightPadPx + wrapWidthPx
    return RebuiltEditorLayout(
        visualLines = visualLines,
        drawLayouts = drawLayouts,
        drawTops = drawTops,
        contentWidthPx = contentWidthPx,
        contentHeightPx = contentHeightPx,
    )
}