package com.uliteeditor.editor.bidi

import org.junit.Assert.assertEquals
import org.junit.Test

class TextIndexTest {
    @Test
    fun commonPrefixWalksCodePointsNotChars() {
        assertEquals(1, TextIndex.commonCodePointPrefix("م", "ملف"))
        assertEquals(1, TextIndex.commonCodePointPrefix("😀", "😀x"))
        assertEquals(2, TextIndex.commonCodePointPrefix("abمرحبا", "abشكرا"))
        assertEquals(0, TextIndex.commonCodePointPrefix("", "a"))
    }

    @Test
    fun commonSuffixWalksCodePointsNotChars() {
        assertEquals(1, TextIndex.commonCodePointSuffix("ملف", "شرف", 0))
        assertEquals(1, TextIndex.commonCodePointSuffix("a😀", "b😀", 0))
        assertEquals(4, TextIndex.commonCodePointSuffix("hello", "42hello", 0))
        assertEquals(0, TextIndex.commonCodePointSuffix("abc", "abd", 2))
    }

    @Test
    fun codePointSliceKeepsSurrogatePairsIntact() {
        assertEquals("a😀b", TextIndex.codePointSlice("xa😀by", 1, 3))
        assertEquals("", TextIndex.codePointSlice("abc", 2, 0))
    }

    @Test
    fun utf16IndexMapsAcrossUtf8() {
        // Arabic letters are 2 UTF-8 bytes each, so the byte index is 2x the
        // UTF-16 index for them — the exact shape the caret conversion uses.
        val text = "abمرحبا"
        assertEquals(2, TextIndex.utf16IndexAtByteOffset(text, 2L))
        // Byte offset 4 = "ab" + "م" -> UTF-16 index 3.
        assertEquals(3, TextIndex.utf16IndexAtByteOffset(text, 4L))
        // Offsets past the end clamp to the last character index.
        assertEquals(text.length, TextIndex.utf16IndexAtByteOffset(text, 100L))
    }

    @Test
    fun rowColumnDerivedFromByteOffsetAcrossNewlines() {
        assertEquals(0 to 2, TextIndex.rowColAtByteOffset("ab\ncd", 2))
        assertEquals(1 to 1, TextIndex.rowColAtByteOffset("ab\ncd", 4))
        assertEquals(1 to 2, TextIndex.rowColAtByteOffset("ab\ncd", 5))
        assertEquals(0 to 3, TextIndex.rowColAtByteOffset("مف\n", 4))
    }

    @Test
    fun utf8LengthCountsMultiByteSequences() {
        assertEquals(0, TextIndex.utf8Length(""))
        assertEquals(3, TextIndex.utf8Length("abc"))
        assertEquals(6, TextIndex.utf8Length("مرحبا".substring(0, 3)))
        assertEquals(4, TextIndex.utf8Length("\uD83D\uDE00"))
    }
}