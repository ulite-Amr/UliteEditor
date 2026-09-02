package com.uliteeditor.editor.layout

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [caretXIn] against a *real* [androidx.compose.ui.text.TextLayoutResult]
 * produced by the Compose [TextMeasurer] on the JVM (Robolectric). The pure-JVM
 * tests cover the paragraph-direction helpers; this file closes the gap on the
 * actual pixel formula — [caretXIn] now returns the platform rect's left edge,
 * with no mirror and no trailing-blank rebuild.
 *
 * The trailing-blank stepping tests (`rtlTrailingSpaceStepsByExactlyOneCharWidth`,
 * `rtlTrailingSpaceRebuildAnchorsAtPlatformRectLeft`, and their siblings) are the
 * regression oracle for the rebuild-removal decision: the platform flattens a
 * trailing neutral run (UBA rule L1), so if [caretXIn] can no longer step a caret
 * through end-of-line blanks by exactly one char width, those tests fail and the
 * rebuild must be restored.
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

        val xStrong = caretXIn(layout, 4, 0f, style, tm)
        val xSpace = caretXIn(layout, 5, 0f, style, tm)
        val xEof = caretXIn(layout, 6, 0f, style, tm)

        assertNotEquals("caret must leave the strong char", xStrong, xSpace)
        assertNotEquals("caret must advance onto the trailing space", xSpace, xEof)
        // LTR: the caret advances right (positive). Assert the SIGN, not abs(),
        // so a mirror/advance sign flip in caretXIn fails the test.
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

        val xStrong = caretXIn(layout, 4, 0f, style, tm)
        val xSpace = caretXIn(layout, 5, 0f, style, tm)
        val xEof = caretXIn(layout, 6, 0f, style, tm)

        // RTL: the caret moves LEFT (negative) as it steps through the trailing
        // blank. Asserting the SIGN (not abs) is what pins RTL direction: a
        // caretXIn that ever placed RTL carets on the wrong side (e.g. a
        // resurrected mirror) would flip the sign and fail the test.
        assertTrue(
            "RTL caret must move left through the trailing blank: " +
                "xStrong=$xStrong xSpace=$xSpace xEof=$xEof",
            xSpace < xStrong && xEof < xSpace,
        )
        assertEquals(-'ا'.advance(tm, style), xSpace - xStrong, STEP_EPS)
        assertEquals(-' '.advance(tm, style), xEof - xSpace, STEP_EPS)
    }

    @Test
    fun multiBlankTrailingBackspaceStepsOneBlankAtATime() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "hello   "
        val layout = tm.measure(text, style)
        val xs = (4..8).map { caretXIn(layout, it, 0f, style, tm) }

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

        val onSpace = caretXIn(layout, 6, 0f, style, tm)
        val onEof = caretXIn(layout, 7, 0f, style, tm)
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

        val onPair = caretXIn(layout, 2, 0f, style, tm)
        val onSpace = caretXIn(layout, 3, 0f, style, tm)
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

        val caret2 = caretXIn(layout, 2, 0f, style, tm)
        assertEquals(layout.getCursorRect(2).left, caret2, STEP_EPS)
    }

    @Test
    fun leadingBlankRunKeepsPlatformPlacement() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "  x"
        val layout = tm.measure(text, style)

        val caret1 = caretXIn(layout, 1, 0f, style, tm)
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
        // RTL: moves left (negative) on the wrapped line.
        val len = text.length
        val xBefore = caretXIn(layout, len - 1, 0f, style, tm)
        val xEof = caretXIn(layout, len, 0f, style, tm)
        assertTrue("wrapped trailing caret must move left", xEof < xBefore)
        assertEquals(-' '.advance(tm, style), xEof - xBefore, WRAP_EPS)
    }

    @Test
    fun emptyTextReturnsLeftMargin() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val layout = tm.measure("", TextStyle.Default)
        assertEquals(7f, caretXIn(layout, 0, 7f, TextStyle.Default, tm), STEP_EPS)
    }

    @Test
    fun caretClampedToTextLength() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "hello "
        val layout = tm.measure(text, style)

        // A caret beyond end-of-text must match the caret exactly at end-of-text.
        val atEnd = caretXIn(layout, 6, 0f, style, tm)
        val beyondEnd = caretXIn(layout, 999, 0f, style, tm)
        assertEquals(atEnd, beyondEnd, STEP_EPS)
    }

    // The maintainer's core complaint about the original suite: it asserted only
    // *deltas* (step widths / signs), so an RTL mirror bug — which shifts every
    // caret x by the *same constant*, `layoutWidth - rect.right - rect.left`,
    // cancelling out of any difference or sign — could never be caught. These
    // tests pin the carets' *absolute* placement against the platform rect,
    // which is what the mirror corrupted.

    @Test
    fun rtlRunCaretUsesPlatformRectLeftNotMirror() {
        // A paragraph that BEGINS with Latin (base LTR) then gains an embedded
        // Arabic run. This is a reported-bug shape: the old mirror pushed a
        // caret inside the Arabic run to the far side of the box.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "abcمرحبا"
        val layout = tm.measure(text, style)

        // Caret on the Arabic 'م' (logical index 3), an RTL strong char.
        val caretX = caretXIn(layout, 3, 0f, style, tm)
        assertEquals(
            "RTL caret must sit at the platform rect's VISUAL left edge, " +
                "not mirrored against layout.size.width",
            layout.getCursorRect(3).left,
            caretX,
            STEP_EPS,
        )
    }

    @Test
    fun endOfTextTrailingArabicStaysOnTheTypedSide() {
        // The reported symptom: caret "flies to the far side" when typing into
        // an LTR paragraph that has grown a trailing Arabic run. In an LTR
        // paragraph the trailing RTL run lays out with its logical-last char
        // leftmost, so the caret after end-of-text sits visually just past the
        // LTR prefix ("abc") — never out at the far right of the run.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "abcمرحبا"
        val layout = tm.measure(text, style)

        val caretEnd = caretXIn(layout, text.length, 0f, style, tm)

        // Font-independent, non-circular oracle: the end-of-text caret must sit
        // at the measured width of the LTR prefix — the run's left edge. The old
        // mirror thrust it out to ~layout.size.width (the far right).
        val prefixWidth = tm.measure("abc", style).size.width.toFloat()
        assertEquals(
            "end-of-text caret in an LTR paragraph must sit just past the " +
                "Latin prefix, not mirrored to the far side",
            prefixWidth,
            caretEnd,
            ABS_EPS,
        )
        // And it must stay clear of the right half entirely (tolerant guard).
        assertTrue(
            "end-of-text caret must not be thrust into the right half " +
                "(caretEnd=$caretEnd, half=${layout.size.width / 2})",
            caretEnd < layout.size.width / 2,
        )
    }

    @Test
    fun rtlTrailingSpaceRebuildAnchorsAtPlatformRectLeft() {
        // The reported double-mirror: on a trailing blank after an Arabic run,
        // the old rebuild applied `layoutWidth - anchorRect.right` on TOP of the
        // already-visual anchor rect. Absolute check: the caret at the first
        // position onto the trailing run crosses the anchor char `ا`, so the
        // rebuilt x must sit exactly `anchorLeft - width(ا)` — a small value
        // just left of the anchor, nowhere near the far-right side a mirror
        // would thrust it to.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "مرحبا "
        val layout = tm.measure(text, style)

        val anchorRectLeft = layout.getCursorRect(4).left
        val alefAdvance = 'ا'.advance(tm, style)
        val onSpace = caretXIn(layout, 5, 0f, style, tm)
        assertEquals(
            "first trailing-space caret must be anchorLeft - one alef width " +
                "(no mirror offset)",
            anchorRectLeft - alefAdvance,
            onSpace,
            STEP_EPS,
        )
    }

    private companion object {
        const val STEP_EPS = 0.5f
        // Wrapped lines round differently; be a touch more lenient there.
        const val WRAP_EPS = 2.0f
        // Absolute cross-measure comparisons (an isolated run measured on its
        // own vs the same glyphs embedded) can differ slightly; be lenient.
        const val ABS_EPS = 2.0f
    }
}
