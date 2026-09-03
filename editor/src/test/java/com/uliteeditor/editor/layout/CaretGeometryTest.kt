package com.uliteeditor.editor.layout

import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Test

class CaretGeometryTest {
    @Test
    fun emptyTextIsLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection(""),
        )
    }

    @Test
    fun leadingArabicRunIsRtl() {
        assertEquals(
            ResolvedTextDirection.Rtl,
            paragraphBaseDirection("مرحبا"),
        )
    }

    @Test
    fun leadingLatinIsLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("hello"),
        )
    }

    @Test
    fun leadingLtrEmbeddedArabicStaysLtr() {
        // The paragraph's base direction is decided by its first strong char,
        // so a line that opens Latin and later gains Arabic is still LTR.
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("hello مرحبا"),
        )
    }

    @Test
    fun leadingRtlEmbeddedLatinStaysRtl() {
        assertEquals(
            ResolvedTextDirection.Rtl,
            paragraphBaseDirection("مرحبا hello"),
        )
    }

    @Test
    fun leadingSpacesThenArabicIsRtl() {
        // Leading neutral whitespace is skipped; the first strong char rules.
        assertEquals(
            ResolvedTextDirection.Rtl,
            paragraphBaseDirection("  مرحبا"),
        )
    }

    @Test
    fun leadingSpacesThenLatinIsLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("  hello"),
        )
    }

    @Test
    fun leadingPunctuationThenArabicIsRtl() {
        // Punctuation and digits are bidi-neutral for the scan.
        assertEquals(
            ResolvedTextDirection.Rtl,
            paragraphBaseDirection("مرحبا! مرحبا"),
        )
        assertEquals(
            ResolvedTextDirection.Rtl,
            paragraphBaseDirection("?مرحبا"),
        )
    }

    @Test
    fun leadingPunctuationThenLatinIsLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("!hello"),
        )
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("123hello"),
        )
    }

    @Test
    fun whollyWhitespaceIsLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("   "),
        )
    }

    @Test
    fun whollyPunctuationIsLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("!!!"),
        )
    }

    @Test
    fun leadingHebrewIsRtl() {
        assertEquals(
            ResolvedTextDirection.Rtl,
            paragraphBaseDirection("\u05E9\u05DC\u05D5\u05DD"),
        )
    }

    @Test
    fun surrogatePairCountsAsOneStrongFirstChar() {
        // A supplementary emoji (LTR) before an Arabic run keeps base LTR.
        assertEquals(
            ResolvedTextDirection.Ltr,
            paragraphBaseDirection("\uD83D\uDE00 مرحبا"),
        )
    }

    @Test
    fun emptyRowWithArabicComposingSpanAlignsRight() {
        // While the IME composes an Arabic word the engine row at the caret is
        // empty (the composed text is held out of the engine), so the cached
        // row alignment alone would lay the preview out left-aligned and the
        // caret would cling to the first char until commit. The composing
        // alignment is derived from the row + live span so an all-Arabic span
        // over an empty row reads RTL and is right-aligned.
        assertEquals(TextAlign.Right, composingRowAlign("", "اهلا"))
    }

    @Test
    fun ltrPrefixKeepsComposingRowLeftAligned() {
        // First-strong wins: a committed LTR prefix keeps the composing row
        // left-aligned even when the live span is Arabic.
        assertEquals(TextAlign.Left, composingRowAlign("abc", "اهلا"))
    }

    @Test
    fun rtlPrefixKeepsComposingRowRightAligned() {
        // A committed Arabic prefix keeps the composing row right-aligned even
        // when the live span is Latin.
        assertEquals(TextAlign.Right, composingRowAlign("مرحبا", "hello"))
    }

    @Test
    fun noComposingSpanFallsBackToRowText() {
        // A session with no live span aligns solely from the committed row text.
        assertEquals(TextAlign.Left, composingRowAlign("abc", null))
        assertEquals(TextAlign.Right, composingRowAlign("مرحبا", null))
    }
}
