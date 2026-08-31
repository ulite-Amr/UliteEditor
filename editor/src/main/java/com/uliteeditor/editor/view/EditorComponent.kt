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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uliteeditor.editor.EditorDimensions
import com.uliteeditor.editor.bidi.TextIndex
import com.uliteeditor.editor.effects.runFlingLoop
import com.uliteeditor.editor.effects.runMetricsLoop
import com.uliteeditor.editor.ime.applyImeEdit
import com.uliteeditor.editor.ime.createNoSuggestionsInterceptor
import com.uliteeditor.editor.input.EditorGestureConfig
import com.uliteeditor.editor.input.awaitGestures
import com.uliteeditor.editor.layout.CaretSpot
import com.uliteeditor.editor.layout.RebuiltEditorLayout
import com.uliteeditor.editor.layout.buildEditorLayout
import com.uliteeditor.editor.layout.caretTopIn
import com.uliteeditor.editor.layout.caretXIn
import com.uliteeditor.editor.layout.composingTextOf
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
 * the *system* keyboard through an invisible [BasicTextField] bound to the
 * engine buffer — there is no built-in on-screen keyboard.
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
@OptIn(ExperimentalComposeUiApi::class, ExperimentalTextApi::class)
fun EditorComponent(
    modifier: Modifier = Modifier,
    settings: EditorSettings? = null,
    onMetricsChange: ((EditorMetrics) -> Unit)? = null,
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

    var imeField by remember { mutableStateOf(TextFieldValue(session.bufferText())) }
    val interactionScope = rememberCoroutineScope()
    val blink = remember { CaretBlink(interactionScope) }

    // The invisible pipe must not sit under whole-word composition: autocorrect
    // / suggestion IMEs hold a word in the composing span until a release, and
    // the engine only sees committed text. The interceptor is remember-stable:
    // passing a fresh instance while a session is live tears it down and
    // restarts the keyboard every recomposition.
    val noSuggestionsInterceptor = remember { createNoSuggestionsInterceptor() }

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
            blink.reset()
        }
        val current = session.bufferText()
        val selection = TextRange(
            TextIndex.utf16IndexAtByteOffset(
                current,
                TextIndex.absoluteByteOffsetOfCursor(session),
            ),
        )
        val next = TextFieldValue(current, selection = selection)
        // Skip identical rewrites: setting the value again resets the IME's
        // composing/suggestion state, which reads as flicker while typing.
        if (imeField != next) imeField = next
    }

    LaunchedEffect(session) {
        focusRequester.requestFocus()
        blink.reset()
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
    // Taps move the caret without touching the buffer; the invisible IME
    // field must learn the new selection or the next keystroke edits at the
    // stale spot (the "caret jumps back to where it last edited" symptom).
    // The no-op skip in syncImeField keeps an already-correct field from
    // being rewritten mid-edit.
    LaunchedEffect(cursor) {
        syncImeField()
    }
    val steadyCaret = remember(rebuilt, cursor, leftMarginPx) {
        steadyCaretSpot(rebuilt, cursor, leftMarginPx)
    }

    // While the IME holds text in composition (autocorrect / suggestions /
    // multi-tap), re-render the caret's row with the composing text inserted
    // at the caret, tinted to mark it unreleased (see composingTextOf).
    val composingColor = MaterialTheme.colorScheme.primary.copy(alpha = EditorDimensions.COMPOSING_ALPHA)
    val composingText = remember(imeField) { composingTextOf(imeField) }
    val caretRow = session.cursor().row.toInt()
    val caretRowText = session.lineText(caretRow.toULong())
    val caretRowUtf16 = TextIndex.utf16IndexAtByteOffset(caretRowText, session.cursor().column.toLong())
    val caretRowFirstTop = rebuilt.rowTops.getOrNull(caretRow) ?: steadyCaret.y
    val composingLayout = remember(
        composingText,
        composingColor,
        textStyle,
        wrapWidthPx,
        wrapEnabled,
        caretRow,
        caretRowUtf16,
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
                textMeasurer = textMeasurer,
            )
        }
    }
    val composingCaretOffset = if (composingLayout != null && composingText != null) {
        val composingEndUtf16 = (caretRowUtf16 + composingText.length)
            .coerceIn(0, composingLayout.layoutInput.text.text.length)
        CaretSpot(
            x = caretXIn(composingLayout, composingEndUtf16, leftMarginPx),
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
    // camera, and the tick only triggers a redraw.
    val lastEditTick = remember { intArrayOf(contentTick) }
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
        session.updateBounds(
            rebuilt.contentWidthPx,
            rebuilt.contentHeightPx,
            viewportWidthPx,
            viewportHeightPx,
        )
        if (!scaling) {
            val didMove = if (contentTick != lastEditTick[0]) {
                lastEditTick[0] = contentTick
                session.followCaretAfterEdit(
                    caretAnchor.x,
                    caretAnchor.y,
                    lineHeightPx,
                    viewportWidthPx,
                    viewportHeightPx,
                )
            } else {
                session.ensureVisible(
                    caretAnchor.x,
                    caretAnchor.y,
                    lineHeightPx,
                    viewportWidthPx,
                    viewportHeightPx,
                )
            }
            if (didMove) scrollTick++
        }
    }

    // Read at composition so scroll frames (which only bump scrollTick)
    // actually repaint the canvas: the canvas lambda is rebuilt whenever
    // the keyed state changes, and reads session.scrollX/Y fresh on rebuild.
    val scrollOffset = remember(scrollTick + contentTick) {
        Offset(session.scrollX(), session.scrollY())
    }

    // The caret tween snaps instantly during a pinch (sora skips caret
    // animation while the size is changing) and resumes after.
    val animatedCaretX by animateFloatAsState(
        targetValue = steadyCaret.x,
        animationSpec = if (scaling) tween(0) else tween(EditorDimensions.CARET_MOVE_ANIMATION_MS),
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
                },
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

            // Invisible IME pipe: one 1 dp field whose text is always
            // snap-synced to the engine buffer (invariant 3). Programmatic
            // writes do not fire onValueChange, so there are no feedback
            // loops. The input session below passes through the
            // no-suggestions interceptor (see `noSuggestionsInterceptor`).
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
                            // Mirror the IME's authoritative view (text +
                            // composing range) exactly — writing
                            // composition = null here would force-commit the
                            // active composition and reset the keyboard
                            // (suggestion strip / layout). Real-time typing
                            // then comes from committing only what the IME
                            // released.
                            imeField = newValue
                            val composed = newValue.composition
                            val committedText = if (composed != null) {
                                // The engine sees everything outside the live
                                // composing span, so each new keystroke lands
                                // immediately; the composed segment commits
                                // as one edit the moment the IME releases it
                                // (composition == null).
                                newValue.text.removeRange(composed.min until composed.max)
                            } else {
                                newValue.text
                            }
                            if (applyImeEdit(session, committedText)) {
                                contentTick++
                                blink.reset()
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .onFocusChanged {
                                if (!it.isFocused && imeField.composition != null) {
                                    // Clearing focus makes the platform cancel
                                    // the active composition, which would
                                    // silently drop the word mid-typing. Land
                                    // it in the engine first.
                                    if (applyImeEdit(session, imeField.text)) {
                                        contentTick++
                                        blink.reset()
                                    }
                                }
                                // CursorManager.setFocused: the caret hides the
                                // instant focus leaves and settles solid on
                                // return.
                                if (it.isFocused) blink.reset() else blink.hide()
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