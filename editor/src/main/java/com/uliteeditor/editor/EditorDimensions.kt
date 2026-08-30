package com.uliteeditor.editor

/**
 * The editor's shared dimension and timing constants. Kept together so the
 * draw, caret, gesture, and blink layers all agree on the same physical
 * numbers without each restating them.
 */
internal object EditorDimensions {
    const val FONT_SIZE_SP = 14
    const val LINE_HEIGHT_SP = 22
    const val TOP_MARGIN_DP = 12
    const val LEFT_MARGIN_DP = 12
    const val RIGHT_PAD_DP = 12
    const val CURSOR_WIDTH_DP = 2

    /** `CursorManager.blinkRunnable` re-posted a blink every 500 ms (PORTING_NOTES row 20). */
    const val BLINK_PERIOD_MS = 500L

    /** `CursorManager.resetBlink` held the caret solid for 1000 ms after any edit/move. */
    const val BLINK_RESET_MS = 1000L

    /** `CursorManager.moveTo` tweened the caret in 120 ms. */
    const val CARET_MOVE_ANIMATION_MS = 120

    /**
     * Alpha tinting the live composing preview on the canvas: primary color at
     * reduced opacity marks "held by the IME, not yet committed" (the composing
     * region of a Gboard/suggestion span), mirroring Android's underline.
     */
    const val COMPOSING_ALPHA = 0.75f

    /**
     * sora-editor's pinch clamp: `EditorTouchEventHandler` keeps the text size
     * inside `scaleMinSize`..`scaleMaxSize` (8..26 sp). The base font is
     * [FONT_SIZE_SP]; scaling moves the font (a `setTextSize`-style mechanism)
     * and never the canvas transform.
     */
    const val MIN_FONT_SIZE_SP = 8f
    const val MAX_FONT_SIZE_SP = 26f
}