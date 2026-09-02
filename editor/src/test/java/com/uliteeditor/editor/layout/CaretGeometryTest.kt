package com.uliteeditor.editor.layout

import androidx.compose.ui.text.style.ResolvedTextDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaretGeometryTest {
    @Test
    fun emptyTextIsLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("", 0, null),
        )
    }

    @Test
    fun trailingSpaceAfterArabicInheritsRtlRun() {
        val text = "مرحبا "
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection(text, text.length, null),
        )
    }

    @Test
    fun trailingNbspAfterArabicInheritsRtlRun() {
        val text = "مرحبا\u00A0"
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection(text, text.length, null),
        )
    }

    @Test
    fun trailingSpaceAfterLatinStaysLtr() {
        val text = "hello "
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection(text, text.length, null),
        )
    }

    @Test
    fun leadingRtlLineHugsRightEdge() {
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا", 0, null),
        )
    }

    @Test
    fun leadingLtrLineHugsLeftEdge() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("hello", 0, null),
        )
    }

    @Test
    fun typedStrongCharAnchorsImmediately() {
        // Latin typed into an Arabic line flips the caret left immediately.
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("مرحباa", "مرحباa".length - 1, ResolvedTextDirection.Rtl),
        )
    }

    @Test
    fun ltrRtlBoundaryUsesInputLanguage() {
        val text = "ab مرحبا"
        val boundary = 2 // on the space between 'b' (LTR) and 'م' (RTL)
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection(text, boundary, ResolvedTextDirection.Rtl),
        )
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection(text, boundary, ResolvedTextDirection.Ltr),
        )
    }

    @Test
    fun ltrRtlBoundaryFallsBackToLeftWithoutInput() {
        val text = "ab مرحبا"
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection(text, 2, null),
        )
    }

    @Test
    fun rtlLtrBoundaryUsesInputLanguage() {
        val text = "مرحبا ab"
        val boundary = "مرحبا".length // on the space between 'ا' and 'a'
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection(text, boundary, ResolvedTextDirection.Ltr),
        )
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection(text, boundary, ResolvedTextDirection.Rtl),
        )
    }

    @Test
    fun interiorSpaceBetweenSameRunKeepsThatDirection() {
        // Space inside an Arabic line stays RTL; inside a Latin line stays LTR.
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا بيك", "مرحبا".length, null),
        )
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("hello world", "hello".length, ResolvedTextDirection.Rtl),
        )
    }

    @Test
    fun surrogatePairAnchorsAsSingleStrongChar() {
        // Trailing: the caret past the emoji walks back over the pair as one
        // LTR strong code point.
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("a\uD83D\uDE00", 3, ResolvedTextDirection.Rtl),
        )
        // Boundary on the space between an emoji (LTR pair) and an RTL run:
        // the left scan rewinds the low surrogate to the pair, the two sides
        // disagree, and input direction wins.
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("\uD83D\uDE00 مرحبا", 2, ResolvedTextDirection.Rtl),
        )
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("\uD83D\uDE00 مرحبا", 2, ResolvedTextDirection.Ltr),
        )
    }

    @Test
    fun isolatedLeadingSpacesFallBackToStrongChar() {
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("  مرحبا", "  ".length, null),
        )
    }

    @Test
    fun whollyWhitespaceLineStaysLtr() {
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("   ", 3, null),
        )
    }

    @Test
    fun trailingPunctuationAfterArabicInheritsRtlRun() {
        // '.', '!' and '؟' are bidi-neutral: the caret past them walks back to
        // the strong Arabic char, so the EOF caret hugs the RTL run's side.
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا.", 6, null),
        )
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا!", 6, null),
        )
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا؟", 6, null),
        )
    }

    @Test
    fun trailingDigitsAfterArabicInheritsRtlRun() {
        // Decimal digits are bidi-neutral (EN/AN), so a trailing number after
        // an Arabic run keeps the caret on the RTL side.
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا123", 8, null),
        )
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا١٢٣", 8, null),
        )
    }

    @Test
    fun isolatedPunctuationLineStaysLtr() {
        // No strong char on either side of an all-neutral line → LTR (blank
        // field match), unlike a single punctuation char next to Arabic.
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("!!!", 3, null),
        )
    }

    @Test
    fun punctuationInsideLatinLineStaysLtr() {
        // A caret on the comma is bracketed by LTR strong chars → LTR even
        // with an Arabic keyboard active.
        assertEquals(
            ResolvedTextDirection.Ltr,
            caretAnchorDirection("hello, world", 5, ResolvedTextDirection.Rtl),
        )
    }

    @Test
    fun digitInsideArabicSentenceStaysRtl() {
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا1بيك", 5, ResolvedTextDirection.Rtl),
        )
    }

    @Test
    fun caretOnLeadingNeutralOfRtlLineHugsRightEdge() {
        // Caret ON an interior leading space (rule 2, one-sided) hugs the RTL
        // start, distinct from the caret-on-first-strong-char case.
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("  مرحبا", 1, null),
        )
    }

    @Test
    fun trailingJoinerAfterArabicInheritsRtlRun() {
        // ZWJ is a bidi-format control: the scans step past it (the FORMAT
        // branch of isScanNeutralCodePoint) to the strong Arabic char.
        assertEquals(
            ResolvedTextDirection.Rtl,
            caretAnchorDirection("مرحبا\u200D", 6, null),
        )
    }

    // --- neutralRunAtCaret classification ---

    @Test
    fun trailingSpaceRunAtEndOfText() {
        assertEquals(5..5, neutralRunAtCaret("مرحبا ", 6))
    }

    @Test
    fun interiorSpaceRunExpandsBothWays() {
        assertEquals(1..3, neutralRunAtCaret("a   b", 2))
    }

    @Test
    fun nonNeutralPositionIsNull() {
        assertNull(neutralRunAtCaret("hello", 1))
    }

    @Test
    fun multipleTrailingSpacesFormOneRun() {
        assertEquals(5..7, neutralRunAtCaret("مرحبا   ", 8))
    }

    @Test
    fun nbspAndZwspCountAsNeutral() {
        assertEquals(5..6, neutralRunAtCaret("مرحبا\u00A0\u200B", 7))
    }

    // --- trailing-blank anchor (the caret-x rebuild target) ---

    @Test
    fun trailingSpaceAnchorsOnPrecedingStrongChar() {
        assertEquals(4, trailingNeutralAnchorBefore("مرحبا ", 6))
        assertEquals(4, trailingNeutralAnchorBefore("مرحبا ", 5))
        assertEquals(3, trailingNeutralAnchorBefore("hello ", 5))
    }

    @Test
    fun trailingMultiSpaceUnderscoreAnchorsOnStrongChar() {
        assertEquals(4, trailingNeutralAnchorBefore("مرحبا   ", 8))
        assertEquals(4, trailingNeutralAnchorBefore("مرحبا   ", 6))
    }

    @Test
    fun trailingBlankAfterPunctuationAnchorsOnPunctuation() {
        // After a trailing `!` the anchor block hugs the punctuation char,
        // whose caret spot the platform does not flatten.
        assertEquals(5, trailingNeutralAnchorBefore("مرحبا! ", 7))
        assertEquals(5, trailingNeutralAnchorBefore("hello. ", 7))
    }

    @Test
    fun trailingNbspAndZwspAnchor() {
        assertEquals(4, trailingNeutralAnchorBefore("مرحبا\u00A0\u200B", 7))
    }

    @Test
    fun midLineBlankRunHasNoTrailingAnchor() {
        assertNull(trailingNeutralAnchorBefore("مرحبا مرحبا", 6))
        assertNull(trailingNeutralAnchorBefore("hello world", 6))
        assertNull(trailingNeutralAnchorBefore("a   b", 2))
    }

    @Test
    fun nonBlankCaretHasNoTrailingAnchor() {
        assertNull(trailingNeutralAnchorBefore("مرحبا", 4))
        assertNull(trailingNeutralAnchorBefore("hello", 3))
    }

    @Test
    fun leadingOrWhollyBlankRunHasNoTrailingAnchor() {
        assertNull(trailingNeutralAnchorBefore("  مرحبا", 1))
        assertNull(trailingNeutralAnchorBefore("   ", 3))
        assertNull(trailingNeutralAnchorBefore("", 0))
    }
}