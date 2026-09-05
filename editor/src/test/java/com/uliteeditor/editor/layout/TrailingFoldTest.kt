package com.uliteeditor.editor.layout

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import com.uliteeditor.editor.bidi.TextIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uniffi.ulite_editor_core.CursorPosition

/**
 * Exercises the manual trailing-run fold (Bug B) end to end: [buildTrailingFold]
 * eligibility, the pure caret/hit-test geometry, and [steadyCaretSpot] landing on
 * a folded continuation line. Runs against a real [TextMeasurer] with Robolectric
 * so the space advance and line heights are actual glyph metrics, not guesses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrailingFoldTest {

    @get:Rule
    val compose = createComposeRule()

    private fun Char.advance(
        measurer: TextMeasurer,
        style: TextStyle,
    ): Float = measurer.measure(this.toString(), style).size.width.toFloat()

    @Test
    fun foldBreaksOverflowingRunOntoContinuationLines() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "مرحبا"
        val spaceW = ' '.advance(tm, style)
        val wrapWidth = tm.measure(word, style).size.width.toFloat() + 0.25f * spaceW
        val text = word + " ".repeat(4)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )
        val fold = checkNotNull(
            buildTrailingFold(text, ResolvedTextDirection.Rtl, layout, wrapWidth, tm, style)
        ) { "an overflowing RTL trailing run must fold" }

        // The word hogs nearly the whole box, so not even one space stays on the
        // base line, and the run spills onto at least one continuation line.
        assertEquals(0, fold.fitOnBase)
        assertTrue(fold.extraLines >= 1)

        // The first folded caret sits at the right-aligned continuation line's
        // rightmost space, one line below the platform's single base line.
        val caret1 = fold.foldLineAndXForCaret(1)!!
        assertEquals(1, caret1.line)
        assertEquals(wrapWidth - spaceW, caret1.x, 0.5f)
        assertEquals(fold.extraLines * layout.size.height.toFloat(), fold.extraHeightPx, 0f)
    }

    @Test
    fun foldCaretStepsWithinLineAndRollsAcrossLines() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "مرحبا"
        val spaceW = ' '.advance(tm, style)
        val wrapWidth = tm.measure(word, style).size.width.toFloat() + 0.25f * spaceW
        val text = word + " ".repeat(120)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )
        val fold = checkNotNull(
            buildTrailingFold(text, ResolvedTextDirection.Rtl, layout, wrapWidth, tm, style)
        ) { "a long overflowing run must fold" }

        // Carets step one blank width left within a continuation line.
        val n1 = fold.foldLineAndXForCaret(1)!!
        val n2 = fold.foldLineAndXForCaret(2)!!
        assertEquals(n1.line, n2.line)
        assertEquals(-spaceW, n2.x - n1.x, 0.5f)

        // The caret after `perFull` spare spaces rolls onto the next line's
        // rightmost space (a jump back to the line's start = its right edge in
        // RTL), so the same x-then-next-line pattern as a wrapping character.
        val first = fold.foldLineAndXForCaret(1)!!
        val across = fold.foldLineAndXForCaret(1 + fold.perFull)!!
        assertEquals(1, first.line)
        assertEquals(2, across.line)
        assertEquals(wrapWidth - spaceW, across.x, 0.5f)

        // The deepest caret lands on the last continuation line, inside the box.
        val last = fold.foldLineAndXForCaret(fold.runChars)!!
        assertEquals(fold.extraLines, last.line)
        assertTrue(last.x >= 0f && last.x < wrapWidth)
    }

    @Test
    fun caretOffsetAtRoundTripsFoldedPositions() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "مرحبا"
        val spaceW = ' '.advance(tm, style)
        val wrapWidth = tm.measure(word, style).size.width.toFloat() + 0.25f * spaceW
        val text = word + " ".repeat(60)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )
        val fold = checkNotNull(
            buildTrailingFold(text, ResolvedTextDirection.Rtl, layout, wrapWidth, tm, style)
        ) { "a long overflowing run must fold" }

        // Every caret the fold places must map back to its own run offset when a
        // tap lands exactly on it (the invariant a tester checks by tapping on a
        // blank continuation line).
        var checked = 0
        for (n in 1..fold.runChars) {
            val folded = fold.foldLineAndXForCaret(n) ?: continue
            val mapped = fold.caretOffsetAt(folded.x, folded.line * fold.lineHeightPx)
            assertEquals(n, mapped - fold.runStartUtf16)
            checked++
        }
        assertTrue("several folded carets must round-trip", checked >= fold.extraLines)
    }

    @Test
    fun foldDoesNotApplyWhenRunFitsBox() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "مرحبا"
        val spaceW = ' '.advance(tm, style)
        val wrapWidth = tm.measure(word, style).size.width.toFloat() + 10f * spaceW
        val text = word + " ".repeat(4)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )
        assertNull(
            "a fitting run must not fold",
            buildTrailingFold(text, ResolvedTextDirection.Rtl, layout, wrapWidth, tm, style),
        )
    }

    @Test
    fun foldDoesNotApplyToLtrRun() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default
        val text = "hello   "
        val wrapWidth = 500f
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )
        assertNull(
            "an LTR trailing run is out of scope",
            buildTrailingFold(text, ResolvedTextDirection.Ltr, layout, wrapWidth, tm, style),
        )
    }

    @Test
    fun foldDoesNotApplyWhenPrefixWrapsToMultipleLines() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val text = "مرحبا بالعالم   "
        // An unconstrained measure gives the row's intrinsic width; halving it
        // forces the interior space to break onto a second line under ANY font
        // metrics (the technique wrappedTrailingBlank… uses), so the platform
        // layout becomes the multi-line regime the fold must reject.
        val unconstrained = tm.measure(text, style)
        val wrapWidth = (unconstrained.size.width / 2).coerceAtLeast(1)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth, maxWidth = wrapWidth),
        )
        assertTrue("the prefix must span two platform lines", layout.lineCount > 1)
        assertNull(
            "a prefix that itself wraps is another regime",
            buildTrailingFold(text, ResolvedTextDirection.Rtl, layout, wrapWidth.toFloat(), tm, style),
        )
    }

    @Test
    fun foldDoesNotApplyWhenPrefixItselfOverflowsBox() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "بالعالم"
        val wrapWidth = tm.measure(word, style).size.width.toFloat() * 0.5f
        val text = word + "   "
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )
        assertEquals("an unbreakable token stays on one platform line", 1, layout.lineCount)
        assertNull(
            "an over-wide prefix is the unbreakable-word regime",
            buildTrailingFold(text, ResolvedTextDirection.Rtl, layout, wrapWidth, tm, style),
        )
    }

    @Test
    fun steadyCaretLandsOnFoldedContinuationLine() {
        lateinit var tm: TextMeasurer
        compose.setContent { tm = rememberTextMeasurer() }
        val left = 8f
        val style = TextStyle.Default.copy(textAlign = TextAlign.Right)
        val word = "مرحبا"
        val spaceW = ' '.advance(tm, style)
        val wrapWidth = tm.measure(word, style).size.width.toFloat() + 0.25f * spaceW
        val text = word + " ".repeat(10)
        val layout = tm.measure(
            text, style,
            softWrap = true,
            constraints = Constraints(minWidth = wrapWidth.toInt(), maxWidth = wrapWidth.toInt()),
        )
        val fold = checkNotNull(
            buildTrailingFold(text, ResolvedTextDirection.Rtl, layout, wrapWidth, tm, style)
        ) { "an overflowing RTL trailing run must fold" }
        val rebuilt = RebuiltEditorLayout(
            rowLayouts = listOf(layout),
            rowTops = listOf(0f),
            rowDirections = listOf(ResolvedTextDirection.Rtl),
            trailingFolds = listOf(fold),
            contentWidthPx = left + wrapWidth,
            contentHeightPx = layout.size.height + fold.extraHeightPx,
            visualLines = 1 + fold.extraLines,
        )

        val caretN = 5
        val expected = fold.foldLineAndXForCaret(caretN)!!
        val caretUtf16 = word.length + caretN
        val column = TextIndex.utf8Length(text.substring(0, caretUtf16)).toULong()
        val spot = steadyCaretSpot(rebuilt, CursorPosition(0uL, column), left, style, tm, wrapWidth)

        assertEquals(left + expected.x, spot.x, 2.0f)
        assertEquals(expected.line * fold.lineHeightPx, spot.y, 2.0f)
    }
}