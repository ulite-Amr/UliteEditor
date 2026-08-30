package com.uliteeditor.editor.bidi

import uniffi.ulite_editor_core.EditorSession

/**
 * Pure text/byte index math shared by the IME pipe, the caret, and tap
 * hit-testing. All offsets stay byte-based at the engine boundary and
 * UTF-16-based at the Compose/IME boundary; these helpers convert between
 * the two without ever guessing glyph geometry. The code-point walking keeps
 * surrogate pairs and combining sequences intact.
 */
internal object TextIndex {
    /** Code points shared at the start of [a] and [b]. */
    fun commonCodePointPrefix(a: String, b: String): Int {
        var indexA = 0
        var indexB = 0
        var count = 0
        while (indexA < a.length && indexB < b.length) {
            val codePointA = a.codePointAt(indexA)
            val codePointB = b.codePointAt(indexB)
            if (codePointA != codePointB) break
            count++
            indexA += Character.charCount(codePointA)
            indexB += Character.charCount(codePointB)
        }
        return count
    }

    /** Code points shared at the end of [a] and [b], after [prefixCount] shared head code points. */
    fun commonCodePointSuffix(a: String, b: String, prefixCount: Int): Int {
        val maxA = a.codePointCount(0, a.length) - prefixCount
        val maxB = b.codePointCount(0, b.length) - prefixCount
        val max = minOf(maxA, maxB)
        var count = 0
        var endA = a.length
        var endB = b.length
        while (count < max) {
            val codePointA = codePointBefore(a, endA)
            val codePointB = codePointBefore(b, endB)
            if (codePointA != codePointB) break
            count++
            endA -= Character.charCount(codePointA)
            endB -= Character.charCount(codePointB)
        }
        return count
    }

    /** The code point ending just before character index [endCharIdx] in [s]. */
    private fun codePointBefore(s: String, endCharIdx: Int): Int {
        val last = endCharIdx - 1
        return if (last >= 1 && s[last].isLowSurrogate() && s[last - 1].isHighSurrogate()) {
            Character.toCodePoint(s[last - 1], s[last])
        } else {
            s[last].code
        }
    }

    /** [countCp] code points of [s] starting at code point [startCp], as a string. */
    fun codePointSlice(s: String, startCp: Int, countCp: Int): String {
        if (countCp <= 0) return ""
        var startChar = 0
        repeat(startCp) { startChar += Character.charCount(s.codePointAt(startChar)) }
        var endChar = startChar
        repeat(countCp) { endChar += Character.charCount(s.codePointAt(endChar)) }
        return s.substring(startChar, endChar)
    }

    /** The (row, column) of byte offset [byteOffset] in [text], where rows are split on '\n'. */
    fun rowColAtByteOffset(text: String, byteOffset: Int): Pair<Int, Int> {
        var row = 0
        var rowStartBytes = 0
        var bytes = 0
        var index = 0
        while (bytes < byteOffset && index < text.length) {
            val codePoint = text.codePointAt(index)
            val length = utf8LengthOfCodePoint(codePoint)
            if (bytes + length > byteOffset) break
            bytes += length
            index += Character.charCount(codePoint)
            if (codePoint == '\n'.code) {
                row++
                rowStartBytes = bytes
            }
        }
        return row to (byteOffset - rowStartBytes)
    }

    /** Byte offset of the cursor (sum of row byte-lengths before it, plus its column). */
    fun absoluteByteOffsetOfCursor(session: EditorSession): Long {
        val cursor = session.cursor()
        var bytes = cursor.column.toLong()
        for (row in 0 until cursor.row.toInt()) {
            bytes += utf8Length(session.lineText(row.toULong())).toLong() + 1L
        }
        return bytes
    }

    /** UTF-16 character index of byte offset [byteOffset] in [buffer]. */
    fun utf16IndexAtByteOffset(buffer: String, byteOffset: Long): Int {
        var bytes = 0L
        var charIndex = 0
        while (charIndex < buffer.length && bytes < byteOffset) {
            val codePoint = buffer.codePointAt(charIndex)
            val length = utf8LengthOfCodePoint(codePoint)
            if (bytes + length > byteOffset) break
            bytes += length
            charIndex += Character.charCount(codePoint)
        }
        return charIndex
    }

    private fun utf8LengthOfCodePoint(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }

    /** Byte length of [s] in UTF-8 — the engine buffer is UTF-8. */
    fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size
}