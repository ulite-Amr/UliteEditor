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
        // right of the wrapped line, and grows LEFT as text is appended. The
        // wrapped measure is now a fixed-width box (min == max == wrapWidth),
        // so the alignment is observable as an absolute position — NOT hidden
        // inside a content-sized box (the B1 bug this pins).
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val text = "اهلا بك"
        val wrapWidth = 200
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth, maxWidth = wrapWidth),
        )

        // RTL advance: the caret at logical index 0 is at or right of every
        // later index (the caret walks LEFT as the logical index grows).
        val xStart = caretXIn(layout, 0, 0f, style, tm)
        val xFirst = caretXIn(layout, 1, 0f, style, tm)
        val xSecond = caretXIn(layout, 2, 0f, style, tm)
        assertTrue(
            "wrap RTL caret must not move right as the word grows " +
                "(xStart=$xStart xFirst=$xFirst xSecond=$xSecond)",
            xStart >= xFirst && xFirst >= xSecond,
        )
        // The interior carets above (xStart/xFirst/xSecond) equal the content's
        // right-leaning spots; that absolute pin is what proves alignment is
        // visible. The end-of-text caret is different: once the line is typed,
        // the next RTL char inserts to the LEFT, so it must sit at the content's
        // LEFT edge (getLineLeft — which shrinks as the line grows), NOT stay
        // pinned at the box's right edge (the caret-x bug this fix resolves).
        assertTrue(
            "wrap RTL interior carets must lean on the box's right half " +
                "(xStart=$xStart wrapWidth=$wrapWidth)",
            xStart > wrapWidth * 0.5f,
        )
        val xEnd = caretXIn(layout, text.length, 0f, style, tm)
        assertEquals(
            "wrap RTL end caret must sit at the line's left (content) edge",
            layout.getLineLeft(0), xEnd, ABS_EPS,
        )
        // And it must never sit right of the first typed char's spot (the RTL
        // caret walks monotonically left as the logical index reaches the end).
        assertTrue(
            "wrap RTL end caret must sit at-left-of the interior spots " +
                "(xEnd=$xEnd xStart=$xStart)",
            xEnd <= xStart,
        )
    }

    @Test
    fun rtlEndOfTextCaretTracksLineLeft() {
        // The B3/B4 caret bug: at end-of-text on a right-aligned RTL paragraph
        // (no trailing blank, so no rebuild), the platform's primary horizontal
        // is pinned to the box's RIGHT edge (lineRight). The caret must instead
        // sit at the content's LEFT edge (lineLeft, which decreases as RTL text
        // grows) so typing Arabic moves the caret left. getHorizontalPosition is
        // a no-op here (same native call as getCursorRect on Android), so this
        // pins the getLineLeft-based correction in caretXIn.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val wrapWidth = 200
        val text = "اهلا"
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth, maxWidth = wrapWidth),
        )

        val lineLeft = layout.getLineLeft(0)
        val lineRight = layout.getLineRight(0)
        val xEnd = caretXIn(layout, text.length, 0f, style, tm)

        // The end-of-text caret must sit at the line's LEFT edge (content), not
        // be pinned at the box right edge — and it must be strictly left of it.
        assertEquals("RTL end caret must equal the line's left (content) edge",
            lineLeft, xEnd, ABS_EPS)
        assertTrue("RTL end caret must sit left of the box's right edge " +
            "(xEnd=$xEnd lineRight=$lineRight)", xEnd < lineRight)
    }

    @Test
    fun noWrapRtlRowGeometryPinsBoxAndEndCaret() {
        // No-wrap geometry that the deferred right-anchoring step (plan Phase 5)
        // will build on: pin that the no-wrap row's box is exactly the line's
        // intrinsic text width (never padded to a wrap width) — the stable
        // invariant row anchoring will position. See the note at the end for
        // why no caret-side assertion appears here.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val text = "اهلا"

        val layout = tm.measure(text, style, softWrap = false)
        val boxW = layout.size.width.toFloat()

        // No-wrap box must be exactly the line's intrinsic text width (never
        // padded to some wrap width): re-measure the same text in no-wrap and
        // assert the layout's box equals that intrinsic width.
        val intrinsicW =
            tm.measure(text, TextStyle.Default.copy(textAlign = TextAlign.Right), softWrap = false)
                .size.width
        assertEquals(
            "no-wrap box must equal the line's intrinsic text width " +
                "(box=$boxW intrinsic=$intrinsicW)",
            intrinsicW.toFloat(),
            boxW,
        )
        // No caret-side assertion: CI ground truth shows the current renderer
        // lays this RTL row out left-anchored (caretXIn(0)=0, caretXIn(len)=4)
        // — the B1/B3 symptom Phase 4/5 fixes — so asserting it here would lock
        // in the bug. The box pin above is the stable invariant Phase 5 anchors
        // against.
    }

    @Test
    fun wrapLtrCaretStaysLeftAligned() {
        // An LTR paragraph leans on the LEFT: the caret at the logical start is
        // near the left edge and walks RIGHT as text grows. Guards against any
        // accidental right-flip of LTR rows. Like the RTL case this uses a
        // fixed-width box so the alignment is an observable absolute position.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Left)
        val text = "hello world"
        val wrapWidth = 200
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth, maxWidth = wrapWidth),
        )

        // LTR advance: the caret moves RIGHT as the logical index grows (or
        // stays put when the headless font advances nothing).
        val xFirst = caretXIn(layout, 0, 0f, style, tm)
        val xSecond = caretXIn(layout, 1, 0f, style, tm)
        assertTrue(
            "wrap LTR caret must not move left as text grows " +
                "(xFirst=$xFirst xSecond=$xSecond)",
            xSecond >= xFirst,
        )
        // The start-of-text caret (leftmost in LTR, TextAlign.Left) must sit in the
        // box's LEFT half, clearly off the right edge / not mirrored right.
        assertTrue(
            "wrap LTR start caret must lean on the box's left half " +
                "(xFirst=$xFirst wrapWidth=$wrapWidth)",
            xFirst < wrapWidth * 0.5f,
        )
    }

    @Test
    fun wrapRtlTrailingBlankOverflowFoldsOntoWrappedLine() {
        // Bug B: an RTL trailing-blank run whose width overflows the wrap width
        // stays on ONE visual line, so its lineLeft drifts negative — past the
        // box's left edge — and the anchor-based rebuild reproduces the same
        // drift. In wrap mode carets past the overflow point must instead snap
        // to the line the run WOULD fold onto (right-aligned continuation),
        // `left + wrapW − (prefix % wrapW)`. This pins:
        //   1. a caret whose prefix fits the wrap width keeps the anchor-based
        //      rebuild — no fold below the boundary;
        //   2. carets past the boundary fold to the formula AND still step one
        //      blank width per space (the rebuild's sign/magnitude semantics);
        //   3. passing wrapWidthPx = null never folds, even at the same
        //      overflowed caret.
        // The rebuild's tail always includes the anchor char, so an absolute
        // fitting caret sits at `left + anchorRect.left − tailW(k)` (RTL walks
        // left). wrapW is set just past the word's own width so the boundary
        // falls between the junction caret (fits) and the first trailing-space
        // caret (overflows), with nothing sitting in float-equality range.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "مرحبا"
        val wordW = tm.measure(word, style).size.width.toFloat()
        val spaceW = ' '.advance(tm, style)
        val wrapWidth = wordW + 0.25f * spaceW
        val left = 8f
        val text = word + " ".repeat(4)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )

        val anchor = word.length - 1
        val anchorRectLeft = layout.getCursorRect(anchor).left
        val prefixW = { k: Int ->
            tm.measure(text.substring(0, word.length + k), style).size.width.toFloat()
        }
        val tailW = { k: Int ->
            tm.measure(text.substring(anchor, word.length + k), style).size.width.toFloat()
        }

        // Junction caret (first trailing-space position): the word alone fits,
        // so the rebuild is unchanged.
        val xJunction = caretXIn(layout, word.length, left, style, tm, wrapWidth)
        assertEquals("fitting caret must stay on the anchor-based rebuild",
            left + anchorRectLeft - tailW(0), xJunction, WRAP_EPS)

        // Every trailing-space caret here overflows (prefix > wrapW): each lands
        // on the folded line the run would keep, and consecutive carets still
        // step one blank width left.
        for (k in 1..4) {
            assertTrue("prefix at $k must overflow the wrap width", prefixW(k) > wrapWidth)
        }
        val xs = (1..4).map { k ->
            caretXIn(layout, word.length + k, left, style, tm, wrapWidth)
        }
        for (k in 1..4) {
            assertEquals(
                "overflow caret k=$k must fold onto the wrapped line",
                left + wrapWidth - (prefixW(k) % wrapWidth), xs[k - 1], WRAP_EPS,
            )
            assertTrue(
                "folded caret k=$k must stay inside the row (x=${xs[k - 1]})",
                xs[k - 1] in left until left + wrapWidth,
            )
        }
        for (k in 1..3) {
            assertEquals("folded carets must keep stepping one blank width",
                -spaceW, xs[k] - xs[k - 1], WRAP_EPS)
        }

        // No-wrap path is out of scope: without a wrap width the same overflowed
        // caret keeps the anchor-based rebuild (and its negative drift).
        val xNoWrap = caretXIn(layout, word.length + 4, left, style, tm, null)
        assertEquals("no-wrap must not fold",
            left + anchorRectLeft - tailW(4), xNoWrap, WRAP_EPS)
    }

    @Test
    fun wrapRtlTrailingBlankOverflowFoldStaysInsideRow() {
        // Bug B's visible symptom is the caret RUNNING OFF the reading zone: the
        // overflow fold must keep every trailing-blank caret inside the row
        // (in [left, left + wrapWidth)), no matter how long the run gets or
        // where it currently sits on its wrapped line. The lower bound is weak
        // (>= left): the exact-fill case (prefix % wrapW == 0) is a line start
        // and snaps to the left margin, which is inside the row, not past it.
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "اهلا"
        val wordW = tm.measure(word, style).size.width.toFloat()
        val spaceW = ' '.advance(tm, style)
        // Half a space of slack: the junction caret fits cleanly, every
        // trailing-space caret overflows (no caret sits in float-equality range).
        val wrapWidth = wordW + 0.5f * spaceW
        val left = 10f
        val text = word + " ".repeat(24)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )

        for (k in 0..24) {
            val caret = word.length + k
            val prefix = tm.measure(text.substring(0, caret), style).size.width.toFloat()
            val x = caretXIn(layout, caret, left, style, tm, wrapWidth)
            val folded = prefix > wrapWidth
            if (folded) {
                assertTrue(
                    "overflow caret k=$k must not run off the margin (x=$x)",
                    x >= left,
                )
                assertTrue(
                    "overflow caret k=$k must sit inside the row (x=$x left+wrap=${left + wrapWidth})",
                    x < left + wrapWidth,
                )
            } else {
                // The word alone fits: the junction caret keeps stepping its
                // anchor-char tail (see the fold test above).
                assertEquals(
                    "fitting caret k=$k must not fold",
                    left + layout.getCursorRect(word.length - 1).left -
                        tm.measure(text.substring(word.length - 1, caret), style).size.width.toFloat(),
                    x, WRAP_EPS,
                )
            }
        }
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
