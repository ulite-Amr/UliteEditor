package com.uliteeditor.editor.view

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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uliteeditor.editor.EditorDimensions
import com.uliteeditor.editor.bidi.TextIndex
import com.uliteeditor.editor.effects.runFlingLoop
import com.uliteeditor.editor.effects.runMetricsLoop
import com.uliteeditor.editor.ime.ImeHandle
import com.uliteeditor.editor.ime.editorIme
import com.uliteeditor.editor.input.EditorGestureConfig
import com.uliteeditor.editor.input.awaitGestures
import com.uliteeditor.editor.layout.CaretSpot
import com.uliteeditor.editor.layout.RebuiltEditorLayout
import com.uliteeditor.editor.layout.buildEditorLayout
import com.uliteeditor.editor.layout.caretTopIn
import com.uliteeditor.editor.layout.caretXIn
import com.uliteeditor.editor.layout.caretXInWithDiagnostics
import com.uliteeditor.editor.layout.composingRowAlign
import com.uliteeditor.editor.layout.measureComposingLayout
import com.uliteeditor.editor.layout.steadyCaretSpot
import com.uliteeditor.editor.metrics.EditorMetrics
import com.uliteeditor.editor.render.EditorDrawState
import com.uliteeditor.editor.render.drawEditorContent
import com.uliteeditor.editor.settings.EditorSettings
import kotlinx.coroutines.launch
import uniffi.ulite_editor_core.CursorPosition
import uniffi.ulite_editor_core.EditorSession

/**
 * The reusable editor composable: it owns a live [EditorSession] behind the
 * UniFFI bridge and renders it like a word processor, with Compose doing the
 * glyph-space geometry. The heavy lifting is split across the modules under
 * `com.uliteeditor.editor`: the layout builder measures the rows, the caret
 * geometry derives the caret from that same layout, the gesture layer
 * pans/zooms/tap-hit-tests, the drawer paints, and the effects drive the
 * fling and metrics loops. This file is the wiring — state, effects, and the
 * small UI surfaces (canvas + invisible IME pipe).
 *
 * This is a library component, not a screen: it takes a plain [modifier] so
 * any host (activity, split pane, preview) can embed it. Input comes from
 * the *system* keyboard through a Compose-native text input session
 * (`editorIme` — a focusable modifier wrapping a custom `InputConnection`
 * with no hidden View/EditText underneath — see the `ime` package), so there
 * is no built-in on-screen keyboard.
 *
 * Three invariants hold the component together:
 * - The caret is always *derived* from the same laid-out row layouts
 *   ([RebuiltEditorLayout]) that the text is drawn from (keyed `remember`),
 *   never computed from a stale layout inside an input handler — otherwise
 *   edits that reflow (especially wrap) leave the caret a row away from
 *   where text is inserted.
 * - Scroll input follows the finger: drag/fling deltas are negated before
 *   reaching the core camera, matching Android/sora-editor conventions.
 * - The engine buffer is the single source of truth; the input connection is
 *   a thin pipe that applies committed edits to the engine and reports live
 *   composing text back for the on-canvas preview (see PROGRESS).
 *
 * [settings] is the host's view of editor preferences; pass an owned
 * instance to keep the toggles (word wrap) shared with the app's UI.
 */

/**
 * One snapshot of the caret row's laid-out geometry from the previous camera
 * pass, so the per-space-keystroke log (LOG POINT 2, Bug B) can diff
 * before-indices against the current rebuild. Only compared when the logical
 * row matches the current caret row; fields are null when the row had no
 * layout (e.g. first pass).
 */
internal data class RtlWrapBefore(
    val row: Int,
    val caretLineCount: Int?,
    val lineLeft: Float?,
    val lineRight: Float?,
)
@Composable
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTextApi::class)
fun EditorComponent(
    modifier: Modifier = Modifier,
    settings: EditorSettings? = null,
    onMetricsChange: ((EditorMetrics) -> Unit)? = null,
    onLog: ((String) -> Unit)? = null,
) {
    val session = remember { EditorSession() }
    val editorSettings = settings ?: remember { EditorSettings() }
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher

    var contentTick by remember { mutableIntStateOf(0) }
    var scrollTick by remember { mutableIntStateOf(0) }
    var editorSize by remember { mutableStateOf(IntSize.Zero) }
    var editing by remember { mutableStateOf(false) }
    var scaling by remember { mutableStateOf(false) }
    var fontSizeSp by remember { mutableFloatStateOf(EditorDimensions.FONT_SIZE_SP.toFloat()) }

    val interactionScope = rememberCoroutineScope()
    val blink = remember { CaretBlink(interactionScope) }

    // IME pipe state (see the `ime` package): `composingState` is the live
    // composing text the connection reports for the on-canvas preview (null
    // when nothing is composed), and `imeSelectionTick` is bumped on
    // caret-only IME moves. `session.cursor()` returns equal-by-value objects,
    // so a caret move without a text change must have a tick of its own to
    // recompose the caret key (see steadyCaret below).
    var composingState by remember { mutableStateOf<String?>(null) }
    var imeSelectionTick by remember { mutableIntStateOf(0) }
    val imeHandle = remember { ImeHandle() }

    val onImeComposingChanged: (String?) -> Unit = { composingState = it }
    val onImeEdited: () -> Unit = {
        contentTick++
        blink.reset()
    }
    val onImeCaretMoved: () -> Unit = { imeSelectionTick++ }
    val onImeFocusChanged: (Boolean) -> Unit = { focused ->
        // CursorManager.setFocused: the caret hides the instant focus leaves
        // and settles solid on return; a leftover composing preview clears.
        if (focused) {
            blink.reset()
        } else {
            blink.hide()
            composingState = null
        }
        editing = focused
    }

    LaunchedEffect(session) {
        focusRequester.requestFocus()
        blink.reset()
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
    // Pinch zoom scales the font (sora-editor's mechanism: setTextSize, keep
    // the paint/canvas un-transformed); line height follows the font ratio,
    // while margins and the caret stay physical-pixel fixed.
    val lineHeightSp = fontSizeSp * (EditorDimensions.LINE_HEIGHT_SP.toFloat() / EditorDimensions.FONT_SIZE_SP.toFloat())
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
        // Animated glyph placement keyframes the x of each glyph between
        // layouts, so a drag that re-Wraps a line under the caret slides the
        // rows instead of letting them snap (the on-device "shimmer"/jitter
        // while scrolling). Line metrics still advance identically, so the
        // caret geometry and hit-testing below are unaffected.
        textMotion = TextMotion.Animated,
    )
    val lineHeightPx = with(density) { lineHeightSp.sp.toPx() }
    val topMarginPx = with(density) { EditorDimensions.TOP_MARGIN_DP.dp.toPx() }
    val leftMarginPx = with(density) { EditorDimensions.LEFT_MARGIN_DP.dp.toPx() }
    val rightPadPx = with(density) { EditorDimensions.RIGHT_PAD_DP.dp.toPx() }
    val cursorWidthPx = with(density) { EditorDimensions.CURSOR_WIDTH_DP.dp.toPx() }
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

    // The caret is derived from this exact layout version (invariant 1):
    // same visual lines that draw the text, same line-height/margin px
    // that positioned them.
    val cursor = session.cursor()
    // Taps move the caret without touching the buffer; the IME connection's
    // mirror must learn the new selection or the next keystroke edits at the
    // stale spot (the "caret jumps back to where it last edited" symptom).
    LaunchedEffect(cursor) {
        imeHandle.syncSelectionFromEngine()
    }
    // `imeSelectionTick` is redundant for text edits (contentTick already
    // moved) but required for caret-only moves: `cursor` is equal-by-value,
    // so without the tick the steadyCaret `remember` would not recompose.
    val steadyCaret = remember(
        rebuilt,
        cursor,
        imeSelectionTick,
        leftMarginPx,
        textStyle,
        textMeasurer,
    ) {
        steadyCaretSpot(rebuilt, cursor, leftMarginPx, textStyle, textMeasurer)
    }

    // While the IME holds text in composition (autocorrect / suggestions /
    // multi-tap), re-render the caret's row with the composing text inserted
    // at the caret, tinted to mark it unreleased. The connection reports the
    // composing text (null when nothing is composed) via [onImeComposingChanged].
    val composingColor = MaterialTheme.colorScheme.primary.copy(alpha = EditorDimensions.COMPOSING_ALPHA)
    val composingText = composingState
    val caretRow = session.cursor().row.toInt()
    val caretRowText = session.lineText(caretRow.toULong())
    val caretRowUtf16 = TextIndex.utf16IndexAtByteOffset(caretRowText, session.cursor().column.toLong())
    val caretRowFirstTop = rebuilt.rowTops.getOrNull(caretRow) ?: steadyCaret.y
    // The composing preview overlays the caret's own row, so it must share the
    // row's paragraph alignment or the inserted text would visually jump sides
    // on an RTL row while composing. The engine row at the caret is EMPTY while
    // a span is composed (the IME holds the text out of the engine), so its
    // cached rowDirections read LTR — the preview's direction must come from the
    // text AS COMPOSED (committed prefix + live span) or an Arabic composing
    // caret is laid out left-aligned and never tracks the committed right-
    // aligned word (it clings to the first character until commit).
    val caretRowTextAlign = composingRowAlign(caretRowText, composingText)
    // Diagnostic logging for on-device RTL debugging (no adb): emits one line
    // per edit with the caret's resolved geometry and the row's alignment, so
    // a shared log ties the caret/alignment symptom to exact values. No-op
    // unless the host passes [onLog]. Keyed on [contentTick] only (not on
    // onLog — an unstable inline lambda — otherwise it restarts on every
    // recomposition and floods the log with frames, not just edits).
    LaunchedEffect(contentTick) {
        if (onLog != null) {
            val align = when (caretRowTextAlign) {
                TextAlign.Right -> "R"
                TextAlign.Left -> "L"
                else -> "?"
            }
            val rowLayout = rebuilt.rowLayouts.getOrNull(caretRow)
            val lineLeft = rowLayout?.getLineLeft(0)
            val lineRight = rowLayout?.getLineRight(0)
            val (diagX, diagnostics) = if (rowLayout != null) {
                caretXInWithDiagnostics(
                    rowLayout,
                    caretRowUtf16,
                    leftMarginPx,
                    textStyle,
                    textMeasurer,
                )
            } else {
                null to null
            }
            onLog(
                "edit caretRow=$caretRow utf16=$caretRowUtf16 " +
                    "x=${steadyCaret.x} y=${steadyCaret.y} " +
                    "diagX=$diagX " +
                    "align=$align wrap=$wrapEnabled " +
                    "scrollX=${session.scrollX()} scrollY=${session.scrollY()} " +
                    "lineLeft=$lineLeft lineRight=$lineRight " +
                    "anchorLeft=${diagnostics?.anchorRectLeft} " +
                    "rowText=\"${caretRowText.take(40)}\"",
            )
        }
    }
    val composingLayout = remember(
        composingText,
        composingColor,
        textStyle,
        wrapWidthPx,
        wrapEnabled,
        caretRow,
        caretRowUtf16,
        caretRowTextAlign,
        contentTick,
    ) {
        composingText?.let { composing ->
            measureComposingLayout(
                row = caretRowText,
                composing = composing,
                caretUtf16 = caretRowUtf16,
                composingColor = composingColor,
                textStyle = textStyle,
                wrapWidthPx = wrapWidthPx,
                wrapEnabled = wrapEnabled,
                textAlign = caretRowTextAlign,
                textMeasurer = textMeasurer,
            )
        }
    }
    val composingCaretOffset = if (composingLayout != null && composingText != null) {
        val composingEndUtf16 = (caretRowUtf16 + composingText.length)
            .coerceIn(0, composingLayout.layoutInput.text.text.length)
        CaretSpot(
            x = caretXIn(
                composingLayout,
                composingEndUtf16,
                leftMarginPx,
                textStyle,
                textMeasurer,
            ),
            y = caretRowFirstTop + caretTopIn(composingLayout, composingEndUtf16),
        )
    } else {
        null
    }

    // Camera corrections run during the same composition that reads them,
    // keyed on everything they depend on (layout, caret, viewport, zoom).
    // Doing this in composition — not in a LaunchedEffect — means the frame
    // that draws the new layout already carries the corrected camera; the
    // old effects corrected a frame late, leaving one stale-scroll frame of
    // mis-rendered content (the flicker "the view jumps around while
    // typing"). The caret-follow call on typed edits (`contentTick` moved)
    // pins the caret row so the view slides smoothly one line per keystroke;
    // the plain margin behavior handles taps and resize.
    //
    // updateBounds also runs while scaling: the pinch's focus-anchored
    // setScroll needs current bounds even mid-gesture. The follow/ensure
    // passes settle only when the gesture ends (`scaling` key, same as the
    // old effect), because they must not fight the finger.
    //
    // scrollTick is bumped here rather than keyed to avoid a camera↔content
    // feedback loop: the block re-runs on a layout/caret change, moves the
    // camera, and the tick only triggers a redraw. This only works because
    // `scrollOffset` is remembered BELOW this block on (scrollTick +
    // contentTick): the canvas frame of this recomposition already reads the
    // corrected camera — do not move it above.
    val lastEditTick = remember { intArrayOf(contentTick) }
    // LOG POINT 2 (Bug B): the previous observation of the caret row's laid-out
    // geometry, so the per-space-keystroke log can diff lineLeft/lineRight and
    // the caret line's line count "BEFORE this keystroke" against the current
    // rebuild. Stored once per camera pass; only meaningful when [row] matches.
    val rtlWrapLast = remember { mutableStateOf<RtlWrapBefore?>(null) }
    val caretAnchor = composingCaretOffset ?: steadyCaret
    remember(
        contentTick,
        caretAnchor.x,
        caretAnchor.y,
        lineHeightPx,
        viewportWidthPx,
        viewportHeightPx,
        rebuilt.contentWidthPx,
        rebuilt.contentHeightPx,
        scaling,
    ) {
        // `updateBounds` re-clamps into the new bounds (e.g. the keyboard
        // closing grows the viewport and shrinks max_scroll_y) and reports
        // nothing, so a moved-from-clamp camera has to be detected here —
        // otherwise the keyed scrollOffset below would go on drawing the
        // stale pre-clamp offset until the next edit.
        val scrollXBefore = session.scrollX()
        val scrollYBefore = session.scrollY()
        session.updateBounds(
            rebuilt.contentWidthPx,
            rebuilt.contentHeightPx,
            viewportWidthPx,
            viewportHeightPx,
        )
        val boundsMoved =
            session.scrollX() != scrollXBefore || session.scrollY() != scrollYBefore
        // The code path that actually moved the camera this pass: the typed-
        // edit follow, the plain margin ensure, or a re-clamp by updateBounds.
        // updateBounds always runs, so its clamp only counts as the mover when
        // neither follow nor ensure reported a move.
        var pathMoved = false
        var movedBy = "updateBounds-clamp"
        if (!scaling) {
            val didMove = if (contentTick != lastEditTick[0]) {
                lastEditTick[0] = contentTick
                movedBy = "followCaretAfterEdit"
                session.followCaretAfterEdit(
                    caretAnchor.x,
                    caretAnchor.y,
                    lineHeightPx,
                    viewportWidthPx,
                    viewportHeightPx,
                )
            } else {
                movedBy = "ensureVisible"
                session.ensureVisible(
                    caretAnchor.x,
                    caretAnchor.y,
                    lineHeightPx,
                    viewportWidthPx,
                    viewportHeightPx,
                )
            }
            if (didMove) pathMoved = true
            if (didMove || boundsMoved) scrollTick++
        } else if (boundsMoved) {
            pathMoved = true
        }
        val scrollXAfter = session.scrollX()
        val scrollXMoved = scrollXAfter != scrollXBefore
        // LOG POINT 2 (Bug B): one dense line per keystroke that leaves the
        // caret row ending in space(s) — and any subsequent wrap-check pass on
        // such a row — with the full before/after state needed to tell whether
        // a visual "line shift" comes from (a) scrollX being adjusted wrongly,
        // (b) lineLeft/lineRight being recalculated wrong, or (c) the RTL
        // anchor/origin x drifting. Emitting the whole set together (never one
        // value in isolation) lets the diffs distinguish the three cases just
        // by reading the log. No-op unless the host wired [onLog].
        if (onLog != null) {
            val rowText = caretRowText
            val trailingSpaces = rowText.length - rowText.trimEnd(' ').length
            val trailingRun = rowText.takeLast(trailingSpaces)
            val caretRowLayout = rebuilt.rowLayouts.getOrNull(caretRow)
            val trailingSpacesW = if (trailingSpaces > 0) {
                textMeasurer.measure(
                    AnnotatedString(trailingRun),
                    textStyle,
                ).size.width.toFloat()
            } else {
                0f
            }
            val rowW = caretRowLayout?.size?.width?.toFloat() ?: 0f
            val caretLine = caretRowLayout?.getLineForOffset(caretRowUtf16) ?: 0
            val lineLeftAfter = caretRowLayout?.getLineLeft(caretLine)
            val lineRightAfter = caretRowLayout?.getLineRight(caretLine)
            val anchorX = caretRowLayout?.let { leftMarginPx + it.getLineLeft(caretLine) }
            val prev = rtlWrapLast.value
            val wrapTriggered = prev?.let { p ->
                p.row == caretRow && p.caretLineCount != null && caretRowLayout != null &&
                    p.caretLineCount != caretRowLayout.lineCount
            } ?: false
            val prevLineLeft = prev?.takeIf { it.row == caretRow }?.lineLeft
            val prevLineRight = prev?.takeIf { it.row == caretRow }?.lineRight
            if (trailingSpaces > 0) {
                onLog(
                    "rtlwrap trailingSpaces=$trailingSpaces " +
                        "trailingSpacesW=$trailingSpacesW rowW=$rowW " +
                        "wrapW=$wrapWidthPx " +
                        "lineLeftBefore=$prevLineLeft lineRightBefore=$prevLineRight " +
                        "lineLeftAfter=$lineLeftAfter lineRightAfter=$lineRightAfter " +
                        "scrollXBefore=$scrollXBefore scrollXAfter=$scrollXAfter " +
                        "anchorX=${anchorX ?: "null"} " +
                        "wrapTriggered=$wrapTriggered " +
                        "path=${when {
                            !scrollXMoved -> "no-scroll-move"
                            pathMoved -> movedBy
                            else -> "gesture-or-other"
                        }} " +
                        "rowCount=${rebuilt.rowLayouts.size}",
                )
            }
            rtlWrapLast.value = RtlWrapBefore(
                row = caretRow,
                caretLineCount = caretRowLayout?.lineCount,
                lineLeft = lineLeftAfter,
                lineRight = lineRightAfter,
            )
        }
        // The keyed body's only job is running the correction pass; lint
        // forbids remember returning Unit, and no state belongs here.
        true
    }

    // Read at composition so scroll frames (which only bump scrollTick)
    // actually repaint the canvas: the canvas lambda is rebuilt whenever
    // the keyed state changes, and reads session.scrollX/Y fresh on rebuild.
    val scrollOffset = remember(scrollTick + contentTick) {
        Offset(session.scrollX(), session.scrollY())
    }

    // The caret rendered while the IME is composing comes from
    // `composingCaretOffset` (instant, no tween — see below). The instant a
    // composing run commits (Space/punctuation after an autocorrected word,
    // most commonly), rendering falls over to this animated value instead,
    // starting a glide toward the new `steadyCaret.x` from whatever it last
    // held — for a single space-width step that glide reads as "the caret
    // didn't move" (and the reverse switch, on Backspace re-entering a
    // composing run, reads as a snap). Force a snap across exactly that
    // composing → committed edge, same as the pinch does, so the visible
    // caret never has to animate across a pipeline switch, only within one.
    var wasComposing by remember { mutableStateOf(false) }
    val justCommitted = wasComposing && composingText == null
    wasComposing = composingText != null

    // The caret tween snaps instantly during a pinch (sora skips caret
    // animation while the size is changing) and resumes after.
    val animatedCaretX by animateFloatAsState(
        targetValue = steadyCaret.x,
        animationSpec = if (scaling || justCommitted) tween(0) else tween(EditorDimensions.CARET_MOVE_ANIMATION_MS),
        label = "caretX",
    )

    val editorGestureConfig = EditorGestureConfig(
        session = session,
        viewConfiguration = viewConfiguration,
        geometry = { geometryState.value },
        leftMarginPx = leftMarginPx,
        isImeVisible = { imeVisibleState.value },
        isScaling = { scaling },
        setScaling = { scaling = it },
        fontSizeSp = { fontSizeSp },
        setFontSizeSp = { fontSizeSp = it },
        onScrollTick = { scrollTick++ },
        onTap = { row, column ->
            session.setCursor(CursorPosition(row, column))
            imeHandle.syncSelectionFromEngine()
            blink.reset()
        },
        onFocusRequest = { focusRequester.requestFocus() },
        onReShowKeyboard = {
            interactionScope.launch {
                withFrameMillis { }
                keyboardController?.show()
            }
        },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The visible area is inset inside a *full-bleed* background: window
        // (status/nav/keyboard) insets are consumed here so the theme color —
        // not the window's default — shows under the status bar. The clip
        // keeps drawn content (rows that scroll under the app bar) inside
        // this inset bounds — the canvas is inset, but the old scrolled rows
        // could paint clear of the safe area and over the TopAppBar.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .clipToBounds()
                .onSizeChanged { editorSize = it }
                .pointerInput(session) {
                    awaitGestures(editorGestureConfig)
                }
                // The Compose-native IME surface: a focusable node that opens
                // a text input session with a custom InputConnection (no hidden
                // View/EditText — see the `ime` package). `editing` is set from
                // the node's own focus events.
                //
                // Ordering matters: `editorIme` must sit ABOVE `focusTarget` so
                // its FocusEventModifierNode can observe the focus target in its
                // subtree — placed after it, the node never sees focus and the
                // session (and the soft keyboard) never opens.
                .focusRequester(focusRequester)
                .editorIme(
                    session = session,
                    handle = imeHandle,
                    onComposingChanged = onImeComposingChanged,
                    onEdited = onImeEdited,
                    onImeCaretMoved = onImeCaretMoved,
                    onFocusChanged = onImeFocusChanged,
                    onLog = onLog,
                )
                .focusTarget(),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawEditorContent(
                    EditorDrawState(
                        scrollOffset = scrollOffset,
                        rebuilt = rebuilt,
                        composingLayout = composingLayout,
                        caretRow = caretRow,
                        caretRowFirstTop = caretRowFirstTop,
                        contentColor = contentColor,
                        caretColor = caretColor,
                        blinkVisible = blink.visible,
                        caretX = composingCaretOffset?.x ?: animatedCaretX,
                        caretY = composingCaretOffset?.y ?: steadyCaret.y,
                        caretWidthPx = cursorWidthPx,
                        caretHeightPx = lineHeightPx,
                        leftMarginPx = leftMarginPx,
                    ),
                )
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
        runFlingLoop(session) { scrollTick++ }
    }

    // Emit live metrics to the host once per frame, but only when a value
    // actually changed. The effect keys on the session only: the host's
    // callback lambda changes identity on every host recomposition, and
    // restarting the loop on that would reset `last` each frame → an emit →
    // a host recomposition → a restart feedback loop. rememberUpdatedState
    // reads the freshest lambda inside the stable loop instead (the loop
    // itself reads it through the [runMetricsLoop] listener lambda).
    val metricsListener = rememberUpdatedState(onMetricsChange)
    LaunchedEffect(session) {
        runMetricsLoop(session, { fontSizeSp }) { metricsListener.value }
    }
}