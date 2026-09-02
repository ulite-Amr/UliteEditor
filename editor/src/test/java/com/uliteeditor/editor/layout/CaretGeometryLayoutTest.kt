package com.uliteeditor.editor.layout

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Constraints
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [caretXIn] against a *real* [androidx.compose.ui.text.TextLayoutResult]
 * produced by the Compose [TextMeasurer] on the JVM (Robolectric). The pure-JVM
 * tests cover only the classification helpers; this file closes the gap on the
 * actual pixel formula — the RTL/LTR mirror advance and the trailing-blank
 * rebuild — which PRs #38/#39 never asserted against an actual layout.
 *
 * sdk=[34] (Android 14) deliberately: it makes Robolectric run on JDK 17, the
 * same JDK the CI `build` job uses (SDK 37 would force JDK 21).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaretGeometryLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun Char.advance(
        measurer: TextMeasurer,
        style: TextStyle,
    ): Float = measurer.measure(this.toString(), style).size.width.toFloat()

    @Test
    fun ltrTrailingSpaceStepsByExactlyOneCharWidth() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "hello "
        val layout = tm.measure(text, style)

        val xStrong = caretXIn(layout, 4, 0f, style, tm, LTR)
        val xSpace = caretXIn(layout, 5, 0f, style, tm, LTR)
        val xEof = caretXIn(layout, 6, 0f, style, tm, LTR)

        assertNotEquals("caret must leave the strong char", xStrong, xSpace)
        assertNotEquals("caret must advance onto the trailing space", xSpace, xEof)
        assertEquals('o'.advance(tm, style), xSpace - xStrong, STEP_EPS)
        assertEquals(' '.advance(tm, style), xEof - xSpace, STEP_EPS)
    }

    @Test
    fun rtlTrailingSpaceStepsByExactlyOneCharWidth() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "مرحبا "
        val layout = tm.measure(text, style)

        val xStrong = caretXIn(layout, 4, 0f, style, tm, RTL)
        val xSpace = caretXIn(layout, 5, 0f, style, tm, RTL)
        val xEof = caretXIn(layout, 6, 0f, style, tm, RTL)

        assertNotEquals("caret must leave the strong char", xStrong, xSpace)
        assertNotEquals("caret must advance onto the trailing space", xSpace, xEof)
        assertEquals('ا'.advance(tm, style), abs(xSpace - xStrong), STEP_EPS)
        assertEquals(' '.advance(tm, style), abs(xEof - xSpace), STEP_EPS)
    }

    @Test
    fun multiBlankTrailingBackspaceStepsOneBlankAtATime() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "hello   "
        val layout = tm.measure(text, style)
        val xs = (4..8).map { caretXIn(layout, it, 0f, style, tm, LTR) }

        assertEquals('o'.advance(tm, style), xs[1] - xs[0], STEP_EPS)
        for (i in 1 until xs.lastIndex) {
            assertEquals(' '.advance(tm, style), xs[i + 1] - xs[i], STEP_EPS)
        }
    }

    @Test
    fun trailingBlankAfterPunctuationStepsByItsOwnWidth() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "hello. "
        val layout = tm.measure(text, style)

        val onSpace = caretXIn(layout, 6, 0f, style, tm, LTR)
        val onEof = caretXIn(layout, 7, 0f, style, tm, LTR)
        assertNotEquals("trailing blank after punctuation must still advance", onSpace, onEof)
        assertEquals(' '.advance(tm, style), onEof - onSpace, STEP_EPS)
    }

    @Test
    fun surrogatePairBeforeTrailingSpaceIsNotGlued() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "\uD83D\uDE00 "
        val layout = tm.measure(text, style)

        val onPair = caretXIn(layout, 2, 0f, style, tm, LTR)
        val onSpace = caretXIn(layout, 3, 0f, style, tm, LTR)
        assertNotEquals("pair caret and trailing-space caret must differ", onPair, onSpace)
        assertEquals(' '.advance(tm, style), onSpace - onPair, STEP_EPS)
    }

    @Test
    fun midLineBlankRunKeepsPlatformPlacement() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "a   b"
        val layout = tm.measure(text, style)

        val caret2 = caretXIn(layout, 2, 0f, style, tm, LTR)
        assertEquals(layout.getCursorRect(2).left, caret2, STEP_EPS)
    }

    @Test
    fun leadingBlankRunKeepsPlatformPlacement() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "  x"
        val layout = tm.measure(text, style)

        val caret1 = caretXIn(layout, 1, 0f, style, tm, LTR)
        assertEquals(layout.getCursorRect(1).left, caret1, STEP_EPS)
    }

    @Test
    fun wrappedTrailingBlankStillStepsOneBlankWidth() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "مرحبا بالعالم "
        val unconstrained = tm.measure(text, style)
        val wrapWidth = (unconstrained.size.width / 2).coerceAtLeast(1)
        val layout = tm.measure(text, style, softWrap = true, constraints = Constraints(maxWidth = wrapWidth))

        // On the final (wrapped) line the trailing blank is its own wrap unit;
        // the caret must still step one space-width at a time, not flatten.
        val len = text.length
        val xBefore = caretXIn(layout, len - 1, 0f, style, tm, RTL)
        val xEof = caretXIn(layout, len, 0f, style, tm, RTL)
        assertNotEquals("wrapped trailing caret must advance", xBefore, xEof)
        assertEquals(' '.advance(tm, style), abs(xEof - xBefore), WRAP_EPS)
    }

    private companion object {
        val LTR = ResolvedTextDirection.Ltr
        val RTL = ResolvedTextDirection.Rtl
        const val STEP_EPS = 0.5f
        // Wrapped lines round differently; be a touch more lenient there.
        const val WRAP_EPS = 2.0f
    }
}
