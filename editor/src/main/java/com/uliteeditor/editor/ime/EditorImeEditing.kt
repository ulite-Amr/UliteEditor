package com.uliteeditor.editor.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import com.uliteeditor.editor.bidi.TextIndex
import uniffi.ulite_editor_core.CursorPosition
import uniffi.ulite_editor_core.EditorSession

/**
 * A system-keyboard text delta applied on top of the engine buffer.
 *
 * The IME hands us the full new string; we diff it against the authoritative
 * buffer to recover the edit as core operations (insertChar / backspace /
 * replaceContent). The diff walks Unicode code points so surrogate pairs and
 * combining sequences stay intact; edits that cross a line boundary fall back
 * to a wholesale replaceContent.
 *
 * This is the sample-level pipe (single keystroke fidelity until the engine
 * exposes a proper InputConnection — see PROGRESS.md).
 */
internal fun applyImeEdit(session: EditorSession, newText: String): Boolean {
    val oldText = session.bufferText()
    if (newText == oldText) return false

    val prefix = TextIndex.commonCodePointPrefix(oldText, newText)
    val totalOld = oldText.codePointCount(0, oldText.length)
    val totalNew = newText.codePointCount(0, newText.length)
    val suffix = TextIndex.commonCodePointSuffix(oldText, newText, prefix)
    val prefixBytes = TextIndex.utf8Length(TextIndex.codePointSlice(oldText, 0, prefix))
    val removed = TextIndex.codePointSlice(oldText, prefix, totalOld - prefix - suffix)
    val inserted = TextIndex.codePointSlice(newText, prefix, totalNew - prefix - suffix)
    val removedBytes = TextIndex.utf8Length(removed)

    if (removed.contains('\n') || inserted.contains('\n')) {
        // Multi-line edit: rebuild the buffer and park the caret at the end
        // of the surviving prefix (its row/col are re-derived from the result).
        session.replaceContent(newText)
        val (row, col) = TextIndex.rowColAtByteOffset(newText, prefixBytes)
        session.setCursor(CursorPosition(row.toULong(), col.toULong()))
        return true
    }

    if (removed.isNotEmpty()) {
        val (delRow, delCol) = TextIndex.rowColAtByteOffset(oldText, prefixBytes + removedBytes)
        session.setCursor(CursorPosition(delRow.toULong(), delCol.toULong()))
        repeat(removed.codePointCount(0, removed.length)) { session.backspace() }
    }

    if (inserted.isNotEmpty()) {
        val (insRow, insCol) = TextIndex.rowColAtByteOffset(newText, prefixBytes)
        session.setCursor(CursorPosition(insRow.toULong(), insCol.toULong()))
        var insertedBytes = 0
        var index = 0
        while (index < inserted.length) {
            val codePoint = inserted.codePointAt(index)
            index += Character.charCount(codePoint)
            val char = String(Character.toChars(codePoint))
            session.insertChar(char)
            insertedBytes += TextIndex.utf8Length(char)
        }
        session.setCursor(CursorPosition(insRow.toULong(), (insCol + insertedBytes).toULong()))
    }
    return true
}

/**
 * A remember-stable no-suggestions input interceptor. The invisible pipe must
 * not sit under whole-word composition: autocorrect / suggestion IMEs hold a
 * word in the composing span until a release, and the engine only sees
 * committed text. Ask for a plain-text input by tagging the EditorInfo with
 * TYPE_TEXT_FLAG_NO_SUGGESTIONS — KeyboardOptions.autoCorrect is ignored by
 * most IMEs for KeyboardType.Text. The interceptor must be remember-stable:
 * passing a fresh instance while a session is live tears it down and restarts
 * the keyboard every recomposition.
 */
internal fun createNoSuggestionsInterceptor(): PlatformTextInputInterceptor =
    PlatformTextInputInterceptor { request, nextHandler ->
        val modifiedRequest = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                val connection = request.createInputConnection(outAttributes)
                outAttributes.inputType =
                    outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                return connection
            }
        }
        nextHandler.startInputMethod(modifiedRequest)
    }