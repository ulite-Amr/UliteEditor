package com.uliteeditor.editor.input

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ViewConfiguration
import com.uliteeditor.editor.EditorDimensions
import com.uliteeditor.editor.bidi.TextIndex
import com.uliteeditor.editor.layout.RebuiltEditorLayout
import uniffi.ulite_editor_core.EditorSession

/**
 * Everything the gesture loop needs, wired in by the composable at
 * composition time. State-reading fields are lambdas so the long-lived
 * pointer loop always consults the latest values even though the loop body
 * itself never restarts (it outlives recompositions).
 */
internal class EditorGestureConfig(
    val session: EditorSession,
    val viewConfiguration: ViewConfiguration,
    /** The latest rebuilt layout, for bidi-aware tap hit-testing. */
    val geometry: () -> RebuiltEditorLayout?,
    val leftMarginPx: Float,
    val isImeVisible: () -> Boolean,
    val isScaling: () -> Boolean,
    val setScaling: (Boolean) -> Unit,
    val fontSizeSp: () -> Float,
    val setFontSizeSp: (Float) -> Unit,
    val onScrollTick: () -> Unit,
    /** Moves the engine cursor to a tapped (row, byte column) and re-arms the blink. */
    val onTap: (row: ULong, column: ULong) -> Unit,
    val onFocusRequest: () -> Unit,
    /** Re-raise the keyboard after a back press hid it (focus alone won't relaunch it). */
    val onReShowKeyboard: () -> Unit,
) {
    /**
     * The editor's gesture loop: drag/fling pan, pinch-to-zoom (sora's
     * font-scaling semantics), and bidi-aware tap hit-testing.
     *
     * Scale events anchor around the fingers' current focal midpoint and use
     * an *incremental* factor (current span / previous span), exactly like
     * `ScaleGestureDetector.getScaleFactor()` — the old code divided by the
     * *initial* span every event, which ballooned the scale.
     *
     * Scroll input follows the finger: drag/fling deltas are negated before
     * reaching the core camera, matching Android/sora-editor conventions.
     * The tap hit-test maps through the exact layout on screen via
     * getOffsetForPosition (bidi-aware) and re-anchors the engine cursor.
     */
    suspend fun PointerInputScope.awaitGestures() {
        val velocityTracker = VelocityTracker()
        var gestureStart: Offset? = null
        var movedBeyondSlop = false
        var panning = false
        var pinchSpan = 0f
        // A finger already down when a pinch ends must not register as a
        // fresh tap on release (it was part of the scale); any genuinely new
        // finger-down clears this.
        var suppressTap = false
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                val active = event.changes.filter { it.pressed }
                if (active.isNotEmpty()) {
                    event.changes.forEach { it.consume() }
                }
                if (active.size >= 2 && !isScaling()) {
                    // A second finger starts the pinch: cancel any fling/pan
                    // and lock the initial span reference.
                    session.startFling(0f, 0f)
                    setScaling(true)
                    panning = false
                    gestureStart = null
                    movedBeyondSlop = false
                    pinchSpan = (active[0].position - active[1].position).getDistance()
                    continue
                }
                if (isScaling()) {
                    if (active.size >= 2) {
                        val first = active.take(2)
                        val focus = (first[0].position + first[1].position) / 2f
                        val newSpan = (first[0].position - first[1].position).getDistance()
                        val factor = if (pinchSpan > 0f) newSpan / pinchSpan else 1f
                        pinchSpan = newSpan
                        if (factor != 1f && factor.isFinite()) {
                            // Font-size grows/shrinks with the gesture,
                            // clamped to sora's [8sp, 26sp] input range.
                            val newSize = (fontSizeSp() * factor)
                                .coerceIn(
                                    EditorDimensions.MIN_FONT_SIZE_SP,
                                    EditorDimensions.MAX_FONT_SIZE_SP,
                                )
                            val effective = newSize / fontSizeSp()
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
                                setFontSizeSp(newSize)
                            }
                        }
                        onScrollTick()
                    } else {
                        // One finger lifted: the pinch ends. The
                        // composition-side ensure_visible effect keys on
                        // `scaling` and settles the caret now. A still-down
                        // finger is scale residue, not a tap.
                        setScaling(false)
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
                            // Content follows the finger: drag delta negated
                            // before it reaches the core camera.
                            session.scrollBy(-delta.x, -delta.y)
                            panning = true
                            onScrollTick()
                        }
                    }
                }
                val released = event.changes.firstOrNull { !it.pressed }
                if (released != null) {
                    if (panning) {
                        val velocity = velocityTracker.calculateVelocity()
                        session.startFling(-velocity.x, -velocity.y)
                    } else if (!movedBeyondSlop && gestureStart != null && !suppressTap) {
                        // Hit-test against the exact layout that is on screen:
                        // getOffsetForPosition is bidi-aware and maps a tap to
                        // the nearest UTF-16 offset, which we convert back to
                        // the engine's byte column.
                        val layout = geometry()
                        if (layout != null) {
                            val contentX = released.position.x + session.scrollX()
                            val contentY = released.position.y + session.scrollY()
                            // A tap above the first row's top (the margin)
                            // picks row 0 — the old engine's locate_tap also
                            // fell through to the first visual line there.
                            val row = layout.rowTops.indexOfLast { it <= contentY }
                                .coerceAtLeast(0)
                            val rowLayout = layout.rowLayouts[row]
                            val hitUtf16 = rowLayout.getOffsetForPosition(
                                Offset(contentX - leftMarginPx, contentY - layout.rowTops[row]),
                            ).coerceIn(0, rowLayout.layoutInput.text.text.length)
                            val hitColumn = TextIndex.utf8Length(
                                rowLayout.layoutInput.text.text.substring(0, hitUtf16),
                            )
                            onTap(row.toULong(), hitColumn.toULong())
                        }
                        onFocusRequest()
                        // Re-raise the keyboard after a back press hid it
                        // (focus alone won't relaunch it), but only when it is
                        // not already up: re-showing an open keyboard restarts
                        // the IME session and snaps a symbols/emojis layout
                        // back to letters.
                        if (!isImeVisible()) {
                            onReShowKeyboard()
                        }
                        onScrollTick()
                    }
                    gestureStart = null
                    movedBeyondSlop = false
                    panning = false
                }
            }
        }
    }
}