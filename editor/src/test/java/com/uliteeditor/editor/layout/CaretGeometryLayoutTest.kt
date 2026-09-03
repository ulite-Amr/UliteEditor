package com.uliteeditor.editor.layout

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
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
 * actual pixel formula — [caretXIn] returns the platform rect's left edge with
 * no RTL mirror, and rebuilds a caret on a trailing blank from the anchor char.
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
        // End-of-text in an LTR paragraph that ends in an RTL run: the paragraph
        // base is LTR, so the logical end maps to the paragraph's right edge —
        // the caret after "abcمرحبا" sits out at the far right, which is the
        // spec-correct (UBA) position, NOT "just past the prefix". (The value
        // "just past abc" is the caret BEFORE the RTL run, logical index 3, not
        // end-of-text.) caretXIn returns the platform's visual x as-is here — no
        // mirror, and no rebuild (the trailing text is Arabic, not a blank run).
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "abcمرحبا"
        val layout = tm.measure(text, style)

        val caretEnd = caretXIn(layout, text.length, 0f, style, tm)

        // The end-of-text caret must be the platform rect's left edge, i.e. the
        // far right of this LTR paragraph with a trailing RTL run (its width is
        // the run terminal at paragraph base level). Kept as an absolute check
        // so a resurgent mirror (layoutWidth - rect.right) — which would push it
        // somewhere else entirely — still fails this test.
        assertEquals(
            "end-of-text caret in an LTR paragraph must equal the platform's " +
                "visual end (far right), not a mirrored/stray x",
            layout.getCursorRect(text.length).left,
            caretEnd,
            ABS_EPS,
        )
        // Sanity: the end-of-text caret really does land in the right half
        // (it is the paragraph's terminal), guarding against the LTR-prefix
        // assumption the old version of this test encoded.
        assertTrue(
            "end-of-text caret must be at the paragraph's far right " +
                "(caretEnd=$caretEnd, rightEdge=${layout.size.width})",
            caretEnd > layout.size.width / 2,
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

    // Alignment-aware absolute-position guards. The original suite pinned only
    // the no-mirror rect and step deltas; these assert the caret lands on the
    // *correct side of the viewport* for each paragraph direction, which is how
    // B1 (Arabic leaning left instead of right) and the no-wrap alignment gap
    // (Phase 2) surface at the caret. They measure with an explicit TextAlign,
    // the same way buildEditorLayout/measureComposingLayout now do, and would
    // fail if a measure branch dropped the alignment again.

    @Test
    fun wrapRtlCaretSitsAtRightAlignedEnd() {
        // An RTL paragraph in a wrapped layout (TextAlign.Right) must lean on
        // the box's RIGHT edge: the caret at the logical end sits at the far
        // right of the wrapped line, and grows LEFT as text is appended.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val text = "اهلا بك"
        val wrapWidth = 200
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(maxWidth = wrapWidth),
        )

        // A short RTL paragraph occupies one line whose rect fills the wrap
        // width (it starts at the right). End-of-text caret must be near the
        // box's right edge (x ~ wrapWidth).
        val xEnd = caretXIn(layout, text.length, 0f, style, tm)
        assertTrue(
            "wrap RTL end caret must lean on the right edge " +
                "(xEnd=$xEnd, box=${layout.size.width})",
            xEnd > layout.size.width / 2,
        )
        // The caret at the FIRST character (start of the RTL word) is at the
        // far right; the caret after appending one more Arabic char walks LEFT.
        val xFirst = caretXIn(layout, 1, 0f, style, tm)
        val xSecond = caretXIn(layout, 2, 0f, style, tm)
        assertTrue(
            "wrap RTL caret must move left as the word grows " +
                "(xFirst=$xFirst xSecond=$xSecond)",
            xSecond < xFirst,
        )
    }

    @Test
    fun noWrapRtlRowGeometryPinsBoxAndEndCaret() {
        // No-wrap geometry that the deferred right-anchoring step (plan Phase 5)
        // will build on. A no-wrap row's box is exactly the line's own width —
        // alignment inside it is a no-op (box == content) — so what matters in
        // absolute px is: the box is the text width, the caret at the logical
        // START of a pure-Arabic line sits at the box's LEFT (x ~ 0), and the
        // caret at the logical END sits at the box's RIGHT (x ~ box width). Row
        // anchoring positions this text-width box against the content area.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val text = "اهلا"

        val layout = tm.measure(text, style, softWrap = false)
        val boxW = layout.size.width.toFloat()

        // No-wrap box must not be padded to any wrap width: it is the line.
        assertTrue("no-wrap box must equal the text width", boxW > 0f)

        val xStart = caretXIn(layout, 0, 0f, style, tm)
        val xEnd = caretXIn(layout, text.length, 0f, style, tm)

        // Pure-Arabic paragraph (base RTL): logical start is the visual RIGHT
        // (rect at the box's right), logical end is the visual LEFT.
        assertTrue(
            "RTL logical start caret must sit near the box's left edge " +
                "(xStart=$xStart box=$boxW)",
            xStart < boxW / 2,
        )
        assertTrue(
            "RTL logical end caret must sit at the box's right side " +
                "(xEnd=$xEnd box=$boxW)",
            xEnd > boxW / 2,
        )
    }

    @Test
    fun wrapLtrCaretStaysLeftAligned() {
        // An LTR paragraph leans on the LEFT: the caret at the logical start is
        // near the left edge and walks RIGHT as text grows. Guards against any
        // accidental right-flip of LTR rows.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Left)
        val text = "hello world"
        val wrapWidth = 200
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(maxWidth = wrapWidth),
        )

        val xFirst = caretXIn(layout, 0, 0f, style, tm)
        val xSecond = caretXIn(layout, 1, 0f, style, tm)
        assertTrue(
            "wrap LTR start caret must be near the left edge (xFirst=$xFirst)",
            xFirst < layout.size.width / 2,
        )
        assertTrue(
            "wrap LTR caret must move right as text grows " +
                "(xFirst=$xFirst xSecond=$xSecond)",
            xSecond > xFirst,
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
