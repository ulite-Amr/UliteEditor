package com.uliteeditor.editor.layout

import androidx.compose.ui.text.style.ResolvedTextDirection
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
}
