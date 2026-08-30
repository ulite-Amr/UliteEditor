package com.uliteeditor.editor.view

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uniffi.ulite_editor_core.CursorPosition
import uniffi.ulite_editor_core.EditorSession

/**
 * Live editor state a host can render into its own UI (an info bar in the
 * sample app). The library only reports; it never displays this — apps that
 * consume the component decide what to do with it.
 */
data class EditorMetrics(
    val line: ULong,
    val column: ULong,
    /** Absolute cursor position in UTF-16 code units. */
    val charIndex: Long,
    val scrollX: Float,
    val scrollY: Float,
    val fontSizeSp: Float,
)

private const val FONT_SIZE_SP = 14
private const val LINE_HEIGHT_SP = 22
private const val TOP_MARGIN_DP = 12
private const val LEFT_MARGIN_DP = 12
private const val RIGHT_PAD_DP = 12
private const val CURSOR_WIDTH_DP = 2

/** `CursorManager.blinkRunnable` re-posted a blink every 500 ms (PORTING_NOTES row 20). */
private const val BLINK_PERIOD_MS = 500L

/** `CursorManager.resetBlink` held the caret solid for 1000 ms after any edit/move. */
private const val BLINK_RESET_MS = 1000L

/** `CursorManager.moveTo` tweened the caret in 120 ms. */
private const val CARET_MOVE_ANIMATION_MS = 120

/**
 * Alpha tinting the live composing preview on the canvas: primary color at
 * reduced opacity marks "held by the IME, not yet committed" (the composing
 * region of a Gboard/suggestion span), mirroring Android's underline.
 */
private const val COMPOSING_ALPHA = 0.75f

/**
 * sora-editor's pinch clamp: `EditorTouchEventHandler` keeps the text size
 * inside `scaleMinSize`..`scaleMaxSize` (8..26 sp). The base font is
 * `FONT_SIZE_SP`; scaling moves the font (a `setTextSize`-style mechanism)
 * and never the canvas transform.
 */
private const val MIN_FONT_SIZE_SP = 8f
private const val MAX_FONT_SIZE_SP = 26f

/**
 * Editor preferences apps can tune and hand to [EditorComponent]. The
 * component reads them live (composition-observed), so mutating a property
 * re-renders immediately — the host never rebuilds state lists the editor
 * owns; it just flips switches.
 */
class EditorSettings {
    /** When true, soft-wraps long rows at the component's width. */
    var wordWrapEnabled by mutableStateOf(true)
}

/**
 * The reusable editor composable: it owns a live [EditorSession] behind the
 * UniFFI bridge and renders it like a word processor, with Compose doing
 * the glyph-space geometry. Every row is measured once per rebuild by
 * [textMeasurer] with the engine's authoritative text; the caret, wrap,
 * and tap hit-testing all read that *same* layout, so what you see is
 * exactly what you touch — bidi, shaping, and real glyph widths included
 * (Rust owns the buffer, cursor, edits, and scroll camera).
 *
 * This is a library component, not a screen: it takes a plain [modifier]
 * so any host (activity, split pane, preview) can embed it. Input comes
 * from the *system* keyboard through an invisible [BasicTextField] bound to
 * the engine buffer — there is no built-in on-screen keyboard.
 *
 * Three invariants hold the component together:
 * - The caret is always *derived* from the same laid-out row layouts
 *   ([RebuiltEditorLayout]) that the text is drawn from (keyed `remember`),
 *   never computed from a stale layout inside an input handler — otherwise
 *   edits that reflow (especially wrap) leave the caret a row away from
 *   where text is inserted.
 * - Scroll input follows the finger: drag/fling deltas are negated before
 *   reaching the core camera, matching Android/sora-editor conventions.
 * - The engine buffer is the single source of truth; the IME field is just
 *   a pipe that gets a fresh authoritative TextFieldValue every edit
 *   (the real InputConnection-backed editor is a follow-up, see PROGRESS).
 *
 * [settings] is the host's view of editor preferences; pass an owned
 * instance to keep the toggles (word wrap) shared with the app's UI.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun EditorComponent(
    modifier: Modifier = Modifier,
    settings: EditorSettings? = null,
    onMetricsChange: ((EditorMetrics) -> Unit)? = null,
) {
    val session = remember { EditorSession() }
    val editorSettings = settings ?: remember { EditorSettings() }
    val textMeasurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    var contentTick by remember { mutableIntStateOf(0) }
    var scrollTick by remember { mutableIntStateOf(0) }
    var blinkVisible by remember { mutableStateOf(true) }
    var blinkJob by remember { mutableStateOf<Job?>(null) }
    var editorSize by remember { mutableStateOf(IntSize.Zero) }
    var editing by remember { mutableStateOf(false) }
    var scaling by remember { mutableStateOf(false) }
    var fontSizeSp by remember { mutableFloatStateOf(FONT_SIZE_SP.toFloat()) }

    var imeField by remember { mutableStateOf(TextFieldValue(session.bufferText())) }
    val interactionScope = rememberCoroutineScope()

    // Port of CursorManager.resetBlink: the caret stays solid while the user
    // is editing or has just moved it, and only starts blinking after
    // BLINK_RESET_MS of inactivity. Every applied edit (typing/deleting),
    // every caret move via tap, and focus-in re-arms it; focus-out hides the
    // caret instantly (same "visible && focused" gate as the old code).
    // Declared before syncImeField because that path re-arms it too.
    fun resetBlink() {
        blinkJob?.cancel()
        blinkJob = null
        blinkVisible = true
        blinkJob = interactionScope.launch {
            delay(BLINK_RESET_MS)
            while (true) {
                delay(BLINK_PERIOD_MS)
                blinkVisible = !blinkVisible
            }
        }
    }

    fun hideBlink() {
        blinkJob?.cancel()
        blinkJob = null
        blinkVisible = false
    }

    // The invisible pipe must not sit under whole-word composition: autocorrect
    // / suggestion IMEs hold a word in the composing span until a release, and
    // the engine only sees committed text. Ask for a no-suggestions plain-text
    // input by tagging the EditorInfo with TYPE_TEXT_FLAG_NO_SUGGESTIONS —
    // KeyboardOptions.autoCorrect is ignored by most IMEs for KeyboardType.Text.
    // The interceptor must be remember-stable: passing a fresh instance while a
    // session is live tears it down and restarts the keyboard every recomposition.
    val noSuggestionsInterceptor = remember {
        PlatformTextInputInterceptor { request, nextHandler ->
            val modifiedRequest = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val connection = request.createInputConnection(outAttributes)
                    outAttributes.inputType =
                        outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    return connection
                }
            }
            nextHandler.startInputMethod(modifiedRequest)
        }
    }

    fun syncImeField() {
        // While the IME is composing (suggestions / autocorrect / multi-tap),
        // its text lives only in the field, not in the engine buffer:
        // rewriting the field cancels composing and resets the keyboard — the
        // suggestion strip flickers and a symbols/emojis layout snaps back to
        // letters. Leave the field alone until the IME commits (onValueChange
        // where composition is null; the IME's composing text then lands as a
        // single edit in the engine).
        if (imeField.composition != null) return
        // The field can briefly hold text that never reached the engine (a
        // composing span the IME ended without a commit callback, or a race
        // between the final onValueChange and focus loss). Landing it before
        // overwriting makes text-loss impossible on every sync path; once
        // flushed, field and buffer are equal and this no-ops, so the extra
        // contentTick++ converges instead of looping.
        if (imeField.text != session.bufferText() && applyImeEdit(session, imeField.text)) {
            contentTick++
            resetBlink()
        }
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
        resetBlink()
    }

    // Every engine edit re-syncs the invisible field to the authoritative
    // buffer (invariant 3); the callback path already syncs immediately, so
    // this also covers edits that did not start at the keyboard.
    LaunchedEffect(contentTick) {
        if (contentTick > 0) syncImeField()
    }

    val density = LocalDensity.current
    // IME visibility, refreshed at composition so the (non-composable)
    // gesture handler can consult it: WindowInsets.ime itself is a
    // @Composable property and cannot be read inside pointerInput.
    val imeVisibleState = remember { mutableStateOf(true) }
    imeVisibleState.value = WindowInsets.ime.getBottom(density) > 0
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
    val viewportWidthPx = (editorSize.width.toFloat()).coerceAtLeast(0f)
    val viewportHeightPx = (editorSize.height.toFloat()).coerceAtLeast(0f)
    val wrapWidthPx = (viewportWidthPx - leftMarginPx - rightPadPx).coerceAtLeast(0f)
    val wrapEnabled = editorSettings.wordWrapEnabled

    val rebuilt = remember(session, textMeasurer, textStyle, contentTick, wrapWidthPx, wrapEnabled) {
        buildEditorLayout(
            session = session,
            textMeasurer = textMeasurer,
            textStyle = textStyle,
            topMarginPx = topMarginPx,
            leftMarginPx = leftMarginPx,
            rightPadPx = rightPadPx,
            wrapWidthPx = wrapWidthPx,
            wrapEnabled = wrapEnabled,
        )
    }

    // Keep the gesture loop alive across layout rebuilds: a pointerInput
    // keyed on the layout would cancel mid-pinch every time the zoom
    // re-measures it. The handler reads the *latest* rebuilt layout
    // through this state instead.
    val geometryState = remember { mutableStateOf<RebuiltEditorLayout?>(null) }
    geometryState.value = rebuilt

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
    val caretContent = remember(rebuilt, cursor, leftMarginPx) {
        val row = cursor.row.toInt().coerceIn(0, rebuilt.rowLayouts.lastIndex)
        val layout = rebuilt.rowLayouts[row]
        val utf16 = utf16IndexAtByteOffset(layout.layoutInput.text.text, cursor.column.toLong())
        // getCursorRect is bidi-aware and valid at offset == end-of-line
        // (unlike getBoundingBox), returning the caret spot inside this
        // exact row layout — which is also the one we draw.
        val caretRect = layout.getCursorRect(utf16)
        Offset(leftMarginPx + caretRect.left, rebuilt.rowTops[row] + caretRect.top)
    }

    // While the IME holds text in composition (autocorrect / suggestions /
    // multi-tap), the engine buffer stays unchanged until the span is
    // released — without help, the canvas draws nothing new and typing looks
    // dead. Re-render the caret's row with the composing text inserted at
    // the caret: every keystroke becomes visible immediately, in its real
    // position, tinted to mark it unreleased. The engine stays authoritative;
    // the preview vanishes as soon as the IME commits (its text then lands in
    // the buffer and the normal layout rebuild draws it solid).
    val composingColor = MaterialTheme.colorScheme.primary.copy(alpha = COMPOSING_ALPHA)
    val composingText = imeField.composition?.let { span ->
        val start = span.min.coerceIn(0, imeField.text.length)
        val end = span.max.coerceIn(start, imeField.text.length)
        if (start < end) {
            // A composition crossing a newline would re-flow the whole row
            // from a stale top; preview only up to the first break, the rest
            // commits normally on release.
            imeField.text.substring(start, end).substringBefore('\n').takeIf { it.isNotEmpty() }
        } else {
            null
        }
    }
    val caretRow = session.cursor().row.toInt()
    val caretUtf16 = utf16IndexAtByteOffset(
        session.lineText(caretRow.toULong()),
        session.cursor().column.toLong(),
    )
    val caretRowFirstTop = rebuilt.rowTops.getOrNull(caretRow) ?: caretContent.y
    val composingLayout = remember(
        composingText,
        composingColor,
        textStyle,
        wrapWidthPx,
        wrapEnabled,
        caretRow,
        caretUtf16,
        contentTick,
    ) {
        composingText?.let { composing ->
            val row = session.lineText(caretRow.toULong())
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
            if (wrapEnabled) {
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
    }
    val composingCaretContent = if (composingLayout != null && composingText != null) {
        val caretRect = composingLayout.getCursorRect(
            (caretUtf16 + composingText.length)
                .coerceIn(0, composingLayout.layoutInput.text.text.length),
        )
        Offset(leftMarginPx + caretRect.left, caretRowFirstTop + caretRect.top)
    } else {
        null
    }

    // ensure_visible must not fight the pinch's focus-anchored scroll while
    // a scale is in flight; it settles only when the gesture ends.
    LaunchedEffect(caretContent, composingCaretContent, lineHeightPx, viewportWidthPx, viewportHeightPx, scaling) {
        if (scaling) return@LaunchedEffect
        val anchor = composingCaretContent ?: caretContent
        if (session.ensureVisible(anchor.x, anchor.y, lineHeightPx, viewportWidthPx, viewportHeightPx)) {
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
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The visible area is inset inside a *full-bleed* background: window
        // (status/nav/keyboard) insets are consumed here so the theme color —
        // not the window's default — shows under the status bar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .onSizeChanged { editorSize = it }
                .pointerInput(session) {
                val velocityTracker = VelocityTracker()
                var gestureStart: Offset? = null
                var movedBeyondSlop = false
                var panning = false
                // Reference span for the *incremental* scale factor (sora's
                // ScaleGestureDetector semantics: current span / previous span).
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
                        if (active.size >= 2 && !scaling) {
                            // A second finger starts the pinch: cancel any
                            // fling/pan and lock the initial span reference.
                            session.startFling(0f, 0f)
                            scaling = true
                            panning = false
                            gestureStart = null
                            movedBeyondSlop = false
                            pinchSpan = (active[0].position - active[1].position).getDistance()
                            continue
                        }
                        if (scaling) {
                            if (active.size >= 2) {
                                val first = active.take(2)
                                // sora anchors each event around the *current*
                                // focal point (the two fingers' midpoint).
                                val focus = (first[0].position + first[1].position) / 2f
                                val newSpan = (first[0].position - first[1].position).getDistance()
                                // Incremental factor, exactly like
                                // ScaleGestureDetector.getScaleFactor():
                                // advance the reference span every event so the
                                // per-step rate never compounds (the old code
                                // divided by the *initial* span every time,
                                // which ballooned the scale).
                                val factor = if (pinchSpan > 0f) newSpan / pinchSpan else 1f
                                pinchSpan = newSpan
                                if (factor != 1f && factor.isFinite()) {
                                    // Font-size grows/shrinks with the gesture,
                                    // clamped to sora's [8sp, 26sp] input range.
                                    val newSize = (fontSizeSp * factor)
                                        .coerceIn(MIN_FONT_SIZE_SP, MAX_FONT_SIZE_SP)
                                    val effective = newSize / fontSizeSp
                                    if (effective != 1f) {
                                        // Keep the content under the focal point
                                        // pinned: newScroll = (oldScroll + focus) *
                                        // factor - focus (sora's onScale). The
                                        // centroid drifts with the fingers; no
                                        // separate pan is applied — the per-event
                                        // re-anchor already carries it.
                                        session.setScroll(
                                            (session.scrollX() + focus.x) * effective - focus.x,
                                            (session.scrollY() + focus.y) * effective - focus.y,
                                        )
                                        fontSizeSp = newSize
                                    }
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
                                // Hit-test against the exact layout that is on
                                // screen: getOffsetForPosition is bidi-aware and
                                // maps a tap to the nearest UTF-16 offset, which
                                // we convert back to the engine's byte column.
                                val layout = geometryState.value
                                if (layout != null) {
                                    val contentX = released.position.x + session.scrollX()
                                    val contentY = released.position.y + session.scrollY()
                                    // A tap above the first row's top (the margin) picks row 0 —
                                    // the old engine's locate_tap also fell through
                                    // to the first visual line there.
                                    val row = layout.rowTops.indexOfLast { it <= contentY }.coerceAtLeast(0)
                                    val hitTextRange = layout.rowLayouts[row].getOffsetForPosition(
                                        Offset(contentX - leftMarginPx, contentY - layout.rowTops[row]),
                                    )
                                    val rowText = layout.rowLayouts[row].layoutInput.text.text
                                    val hitUtf16 = hitTextRange.start.coerceIn(0, rowText.length)
                                    val hitColumn = utf8Length(rowText.substring(0, hitUtf16))
                                    session.setCursor(CursorPosition(row.toULong(), hitColumn.toULong()))
                                    resetBlink()
                                }
                                focusRequester.requestFocus()
                                // Re-raise the keyboard after a back press hid it
                                // (focus alone won't relaunch it), but only when
                                // it is not already up: re-showing an open
                                // keyboard restarts the IME session and snaps a
                                // symbols/emojis layout back to letters. Delay
                                // the re-show one frame so a pending hide
                                // finishes first (composition scope hosts it;
                                // the gesture event scope is restricted).
                                if (!imeVisibleState.value) {
                                    interactionScope.launch {
                                        withFrameMillis { }
                                        keyboardController?.show()
                                    }
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
                for (row in rebuilt.rowLayouts.indices) {
                    // While composing, the caret's row is redrawn from the
                    // merged layout below; skip its engine pieces so the
                    // inserted composing text is not double-rendered.
                    if (composingLayout != null && row == caretRow) {
                        continue
                    }
                    drawText(
                        textLayoutResult = rebuilt.rowLayouts[row],
                        color = contentColor,
                        topLeft = Offset(leftMarginPx, rebuilt.rowTops[row]),
                    )
                }
                composingLayout?.let { layout ->
                    drawText(
                        textLayoutResult = layout,
                        color = contentColor,
                        topLeft = Offset(leftMarginPx, caretRowFirstTop),
                    )
                }
                if (blinkVisible) {
                    val caretX = composingCaretContent?.x ?: animatedCaretX
                    val caretY = composingCaretContent?.y ?: caretContent.y
                    drawRect(
                        color = caretColor,
                        topLeft = Offset(caretX, caretY),
                        size = Size(cursorWidthPx, lineHeightPx),
                    )
                }
            }
        }

        // Invisible IME pipe: one 1 dp field whose text is always snap-synced
        // to the engine buffer (invariant 3). Programmatic writes do not fire
        // onValueChange, so there are no feedback loops. The input session of
        // every field below passes through the no-suggestions interceptor (see
        // `noSuggestionsInterceptor`), so the IME tags its own EditorInfo.
        InterceptPlatformTextInput(
            interceptor = noSuggestionsInterceptor,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(1.dp),
            ) {
                BasicTextField(
                    value = imeField,
                    onValueChange = { newValue ->
                        // Mirror the IME's authoritative view (text + composing
                        // range) exactly — writing composition = null here would
                        // force-commit the active composition and reset the
                        // keyboard (suggestion strip / layout). Real-time typing
                        // then comes from committing only what the IME released.
                        imeField = newValue
                        val composed = newValue.composition
                        val committedText = if (composed != null) {
                            // The engine sees everything outside the live composing
                            // span, so each new keystroke lands immediately; the
                            // composed segment commits as one edit the moment the
                            // IME releases it (composition == null).
                            newValue.text.removeRange(composed.min until composed.max)
                        } else {
                            newValue.text
                        }
                        if (applyImeEdit(session, committedText)) {
                            contentTick++
                            resetBlink()
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .onFocusChanged {
                            if (!it.isFocused && imeField.composition != null) {
                                // Clearing focus makes the platform cancel the
                                // active composition, which would silently drop
                                // the word mid-typing. Land it in the engine first.
                                if (applyImeEdit(session, imeField.text)) {
                                    contentTick++
                                    resetBlink()
                                }
                            }
                            // CursorManager.setFocused: the caret hides the
                            // instant focus leaves and settles solid on return.
                            if (it.isFocused) resetBlink() else hideBlink()
                            editing = it.isFocused
                        },
                    textStyle = TextStyle(color = Color.Transparent, fontSize = 16.sp),
                    cursorBrush = SolidColor(Color.Transparent),
                )
            }
        }
        }
    }

    BackHandler {
        if (editing) {
            // First back: drop the keyboard and the field's focus; the next
            // back falls through to the host's default (exit).
            keyboardController?.hide()
            focusManager.clearFocus()
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

    // Emit live metrics to the host once per frame, but only when a value
    // actually changed, so listeners can react without constant churn. The
    // effect keys on the session only: the host's callback lambda changes
    // identity on every host recomposition, and restarting the loop on that
    // would reset `last` each frame → an emit → a host recomposition → a
    // restart feedback loop. rememberUpdatedState reads the freshest lambda
    // inside the stable loop instead.
    val metricsListener = rememberUpdatedState(onMetricsChange)
    LaunchedEffect(session) {
        if (metricsListener.value == null) return@LaunchedEffect
        var last: EditorMetrics? = null
        while (true) {
            withFrameMillis {
                val cursor = session.cursor()
                val current = EditorMetrics(
                    line = cursor.row,
                    column = cursor.column,
                    charIndex = utf16IndexAtByteOffset(
                        session.bufferText(),
                        absoluteByteOffsetOfCursor(session),
                    ).toLong(),
                    scrollX = session.scrollX(),
                    scrollY = session.scrollY(),
                    fontSizeSp = fontSizeSp,
                )
                if (current != last) {
                    last = current
                    metricsListener.value?.invoke(current)
                }
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
    val rowLayouts: List<TextLayoutResult>,
    val rowTops: List<Float>,
    val contentWidthPx: Float,
    val contentHeightPx: Float,
)

/**
 * Measures the whole document once per rebuild and hands the editor it
 * draws from — one `TextLayoutResult` per logical row, laid out by real
 * glyph measurement (bidi, shaping, actual advances). This is the single
 * source of truth the caret, wrap, and tap hit-testing all read (mnemonic:
 * what Compose draws is what Compose hit-tests), replacing the old
 * two-model design where Rust broke lines from a uniform per-character
 * width guess (`charWidthPx` = one "M") — the cause of the mid-screen
 * wrap folds and the LTR-only caret.
 *
 * Row tops are the text-y of each row's layout in content space, so the
 * caret/gesture layers only ever add this layout's own offsets — never
 * recompute width math. Requests only change what this function re-runs:
 * an edit, a resize, a font change, or a settings toggle.
 */
private fun buildEditorLayout(
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
    var contentHeightPx = topMarginPx
    var maxLineWidthPx = 0f
    val wrapConstraints = Constraints(maxWidth = wrapWidthPx.toInt().coerceAtLeast(1))
    for (row in 0 until rowCount) {
        val text = session.lineText(row.toULong())
        val layout = if (wrapEnabled) {
            textMeasurer.measure(
                AnnotatedString(text),
                textStyle,
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
        contentHeightPx += layout.height.toFloat()
        if (!wrapEnabled) {
            maxLineWidthPx = maxOf(maxLineWidthPx, layout.size.width.toFloat())
        }
    }
    // Wrap locks horizontal scroll to the content width (like the old
    // engine); no-wrap widens the canvas to the longest row instead.
    val contentWidthPx = if (wrapEnabled) {
        leftMarginPx + rightPadPx + wrapWidthPx
    } else {
        leftMarginPx + rightPadPx + maxLineWidthPx
    }
    return RebuiltEditorLayout(
        rowLayouts = rowLayouts,
        rowTops = rowTops,
        contentWidthPx = contentWidthPx,
        contentHeightPx = contentHeightPx,
    )
}