package com.uliteeditor.editor.ime

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannedString
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import com.uliteeditor.editor.bidi.TextIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uniffi.ulite_editor_core.CursorPosition
import uniffi.ulite_editor_core.EditorSession

/**
 * Compose-only IME surface for the editor.
 *
 * The editor never renders an Android text control of its own; it draws its
 * own canvas and caret. So the IME must still be able to talk to us without a
 * hidden [android.view.View] / EditText — otherwise the OS would draw its
 * teardrop cursor and selection handles and report bogus CursorAnchorInfo from
 * a (0,0) field. This file gives the editor a real [InputConnection] the
 * platform requests directly, implemented here with no View underneath.
 *
 * Data flow (the Rust engine buffer stays the single source of truth):
 * - A mirror holds the text the IME is expected to see — the engine text plus
 *   any live composing span. UTF-16 offsets the IME exchanges never meet the
 *   engine's UTF-8 byte offsets directly; [TextIndex] converts at the boundary.
 * - Committed text lands in the engine immediately. While an IME holds a
 *   composing span (autocorrect / suggestions / multi-tap) the span stays OUT
 *   of the engine and is only suffixed to the mirror; Compose previews it on
 *   the canvas via [onComposingChanged]. The span becomes one engine edit the
 *   moment it is released, focus is lost, or the connection closes, so text is
 *   never lost to a cancelled span.
 * - Caret-only moves (arrow keys, setSelection) re-anchor the mirror to the
 *   engine cursor and report through [onImeCaretMoved], so the canvas caret
 *   and the keyboard agree.
 *
 * All IME calls can arrive on a binder thread, so every mutation is dispatched
 * to the main looper (the only thread allowed to touch [EditorSession]) and the
 * mirror state is additionally guarded by a lock, because the query methods
 * (`get*`, [getSurroundingText]) may still be serviced synchronously by the
 * IME on any thread.
 */

/**
 * Composable-side handle to the live [EditorImeNode], so the editor can push
 * engine-side caret moves (taps) into the connection's mirror.
 */
internal class ImeHandle {
    internal var node: EditorImeNode? = null

    /** Re-anchors the active connection's mirror caret to the engine cursor. */
    fun syncSelectionFromEngine() {
        node?.syncSelectionFromEngine()
    }
}

/**
 * Modifier node that opens the platform text-input session while the wrapped
 * element is focused. On focus it establishes a session whose request's
 * [PlatformTextInputMethodRequest.createInputConnection] returns a fresh
 * [EditorImeConnection]; the session teardown is automatic when focus is lost
 * or the node is detached.
 */
internal class EditorImeNode(
    var session: EditorSession,
    var onComposingChanged: (String?) -> Unit,
    var onEdited: () -> Unit,
    var onImeCaretMoved: () -> Unit,
    var onFocusChanged: (Boolean) -> Unit,
    var onLog: ((String) -> Unit)?,
    var handle: ImeHandle?,
) : Modifier.Node(), PlatformTextInputModifierNode, FocusEventModifierNode {

    private var sessionJob: Job? = null

    /** The live connection, so [syncSelectionFromEngine] can reach it. */
    var activeConnection: EditorImeConnection? = null

    override fun onFocusEvent(focusState: FocusState) {
        sessionJob?.cancel()
        if (focusState.isFocused) {
            sessionJob = coroutineScope.launch {
                // Suspends until the session closes; cancelled on blur/detach.
                establishTextInputSession {
                    startInputMethod(createImeRequest())
                }
            }
        } else {
            // The session coroutine was cancelled above. If the platform did
            // not close the connection (and so did not land the span), commit
            // a leftover composing span so focus loss never drops a word.
            activeConnection?.commitPendingComposition()
        }
        onFocusChanged(focusState.isFocused)
    }

    /** Re-anchors the live connection's mirror caret to the engine cursor. */
    fun syncSelectionFromEngine() {
        activeConnection?.syncSelectionFromEngine()
    }

    private fun createImeRequest(): PlatformTextInputMethodRequest =
        PlatformTextInputMethodRequest { outAttributes ->
            outAttributes.inputType =
                InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            outAttributes.imeOptions =
                EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI
            val initialCaret = TextIndex.utf16IndexAtByteOffset(
                session.bufferText(),
                TextIndex.absoluteByteOffsetOfCursor(session),
            )
            outAttributes.initialSelStart = initialCaret
            outAttributes.initialSelEnd = initialCaret

            val connection =
                EditorImeConnection(
                    session = session,
                    onComposingChanged = onComposingChanged,
                    onEdited = onEdited,
                    onImeCaretMoved = onImeCaretMoved,
                    onLog = onLog,
                )
            activeConnection = connection
            connection
        }
}

/**
 * [ModifierNodeElement] that creates and updates the [EditorImeNode] across
 * recompositions, keeping [handle] pointed at the live node.
 */
internal data class EditorImeNodeElement(
    private val session: EditorSession,
    private val handle: ImeHandle,
    private val onComposingChanged: (String?) -> Unit,
    private val onEdited: () -> Unit,
    private val onImeCaretMoved: () -> Unit,
    private val onFocusChanged: (Boolean) -> Unit,
    private val onLog: ((String) -> Unit)?,
) : ModifierNodeElement<EditorImeNode>() {
    override fun create(): EditorImeNode {
        val node =
            EditorImeNode(
                session,
                onComposingChanged,
                onEdited,
                onImeCaretMoved,
                onFocusChanged,
                onLog,
                handle,
            )
        handle.node = node
        return node
    }

    override fun update(node: EditorImeNode) {
        node.session = session
        node.onComposingChanged = onComposingChanged
        node.onEdited = onEdited
        node.onImeCaretMoved = onImeCaretMoved
        node.onFocusChanged = onFocusChanged
        node.onLog = onLog
        node.handle = handle
        handle.node = node
        node.activeConnection?.onCallbacksChanged(
            onComposingChanged,
            onEdited,
            onImeCaretMoved,
            onLog,
        )
    }
}

/**
 * Attaches the editor's Compose-native IME node (see [EditorImeNode]). The
 * caller must apply `focusTarget` on the same element and it must come AFTER
 * this modifier in the chain: `EditorImeNode` is a [FocusEventModifierNode]
 * and only observes focus targets in its own subtree, so placing its focus
 * node above this one would leave it blind (no focus events, no session, no
 * keyboard). `focusRequester` may come before or after.
 */
internal fun Modifier.editorIme(
    session: EditorSession,
    handle: ImeHandle,
    onComposingChanged: (String?) -> Unit,
    onEdited: () -> Unit,
    onImeCaretMoved: () -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    onLog: ((String) -> Unit)?,
): Modifier =
    then(
        EditorImeNodeElement(
            session = session,
            handle = handle,
            onComposingChanged = onComposingChanged,
            onEdited = onEdited,
            onImeCaretMoved = onImeCaretMoved,
            onFocusChanged = onFocusChanged,
            onLog = onLog,
        ),
    )

/**
 * The editor's [InputConnection], implemented directly (not via a View-backed
 * [android.view.inputmethod.BaseInputConnection]). See the file KDoc.
 */
internal class EditorImeConnection(
    private val session: EditorSession,
    private var onComposingChanged: (String?) -> Unit,
    private var onEdited: () -> Unit,
    private var onImeCaretMoved: () -> Unit,
    private var onLog: ((String) -> Unit)? = null,
) : InputConnection {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    /** Mirror of the text the IME sees (engine text + live composing span). */
    private var mirror: String = session.bufferText()
    /** UTF-16 caret in [mirror] (always a collapsed selection here). */
    private var mirrorCaret: Int = engineCaretUtf16(mirror, session)
    /** UTF-16 composing range in [mirror]; -1 when no span is active. */
    private var composingStart: Int = -1
    private var composingEnd: Int = -1

    private var composingPreview: String? = null

    /** Refreshes callbacks on recomposition while the session is live. */
    fun onCallbacksChanged(
        onComposingChanged: (String?) -> Unit,
        onEdited: () -> Unit,
        onImeCaretMoved: () -> Unit,
        onLog: ((String) -> Unit)?,
    ) {
        this.onComposingChanged = onComposingChanged
        this.onEdited = onEdited
        this.onImeCaretMoved = onImeCaretMoved
        this.onLog = onLog
    }

    /** Re-anchors the mirror caret to the current engine cursor. */
    fun syncSelectionFromEngine() {
        mainHandler.post {
            synchronized(lock) { pullSelectionFromEngine() }
        }
    }

    /** Lands the mirror — with any composing span — into the engine. */
    fun commitPendingComposition() {
        mainHandler.post {
            synchronized(lock) { commitPendingCompositionLocked("commitPendingComposition") }
        }
    }

    override fun getHandler(): Handler = mainHandler

    // getEditable is no longer declared on InputConnection starting compileSdk
    // 37, so it cannot be an override here; it is kept as a plain method so
    // older runtimes, whose interface still declares it, get an explicit null
    // instead of an AbstractMethodError.
    fun getEditable(): Editable? = null

    /** This editor owns a full mirror of the text; content commits are never used. */
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false

    // ------------------------------------------------------------------
    // text queries (served from the mirror on any thread, lock-guarded)
    // ------------------------------------------------------------------

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        synchronized(lock) {
            val count = n.coerceAtMost(mirrorCaret)
            val text = mirror.substring(mirrorCaret - count, mirrorCaret)
            if (flags and InputConnection.GET_TEXT_WITH_STYLES != 0) {
                SpannedString(text)
            } else {
                text
            }
        }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        synchronized(lock) {
            val count = n.coerceAtMost(mirror.length - mirrorCaret)
            val text = mirror.substring(mirrorCaret, mirrorCaret + count)
            if (flags and InputConnection.GET_TEXT_WITH_STYLES != 0) {
                SpannedString(text)
            } else {
                text
            }
        }

    override fun getSelectedText(flags: Int): CharSequence? = null

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        synchronized(lock) {
            ExtractedText().apply {
                text = mirror
                startOffset = 0
                selectionStart = mirrorCaret
                selectionEnd = mirrorCaret
            }
        }

    override fun getSurroundingText(
        beforeLength: Int,
        afterLength: Int,
        flags: Int,
    ): SurroundingText? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            synchronized(lock) {
                val limit = 192
                val before = beforeLength.coerceAtMost(limit)
                val after = afterLength.coerceAtMost(limit)
                val windowStart = (mirrorCaret - before).coerceAtLeast(0)
                val windowEnd = (mirrorCaret + after).coerceAtMost(mirror.length)
                SurroundingText(
                    mirror.substring(windowStart, windowEnd),
                    mirrorCaret - windowStart,
                    mirrorCaret - windowStart,
                    windowStart,
                )
            }
        } else {
            // SurroundingText is API 31; older runtimes get null (allowed).
            null
        }

    // ------------------------------------------------------------------
    // edits (dispatched to the main looper)
    // ------------------------------------------------------------------

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        mainHandler.post {
            synchronized(lock) {
                val committed = text?.toString() ?: ""
                val composingStartBefore = composingStart
                val composingEndBefore = composingEnd
                val caretBefore = mirrorCaret
                val bufferBefore = session.bufferText()
                if (composingStart >= 0) {
                    // Capture the span start before replaceMirror clears it
                    // (it drops the span because the edit covers the span).
                    val originalStart = composingStart
                    replaceMirror(composingStart, composingEnd, committed)
                    mirrorCaret = originalStart + committed.length
                    composingStart = -1
                    composingEnd = -1
                } else {
                    replaceMirror(mirrorCaret, mirrorCaret, committed)
                    mirrorCaret += committed.length
                }
                setEngineCaretTo(mirrorCaret)
                syncEngineAndNotify()
                logImeEdit(
                    method = "commitText",
                    text = committed,
                    composingBeforeStart = composingStartBefore,
                    composingBeforeEnd = composingEndBefore,
                    composingAfterStart = composingStart,
                    composingAfterEnd = composingEnd,
                    caretBefore = caretBefore,
                    caretAfter = mirrorCaret,
                    bufferBefore = bufferBefore,
                    bufferAfter = session.bufferText(),
                )
            }
        }
        return true
    }

    override fun commitCompletion(completion: CompletionInfo?): Boolean = false

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        mainHandler.post {
            synchronized(lock) {
                val composed = text?.toString() ?: ""
                val composingStartBefore = composingStart
                val composingEndBefore = composingEnd
                val caretBefore = mirrorCaret
                val bufferBefore = session.bufferText()
                val start = if (composingStart >= 0) composingStart else mirrorCaret
                replaceMirror(start, if (composingStart >= 0) composingEnd else start, composed)
                composingStart = start
                composingEnd = start + composed.length
                mirrorCaret = when {
                    newCursorPosition > 0 -> composingEnd
                    else -> composingStart
                }
                setEngineCaretTo(mirrorCaret)
                syncEngineAndNotify()
                logImeEdit(
                    method = "setComposingText",
                    text = composed,
                    composingBeforeStart = composingStartBefore,
                    composingBeforeEnd = composingEndBefore,
                    composingAfterStart = composingStart,
                    composingAfterEnd = composingEnd,
                    caretBefore = caretBefore,
                    caretAfter = mirrorCaret,
                    bufferBefore = bufferBefore,
                    bufferAfter = session.bufferText(),
                )
            }
        }
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        mainHandler.post {
            synchronized(lock) {
                val from = start.coerceIn(0, mirror.length)
                val to = end.coerceIn(from, mirror.length)
                val composingStartBefore = composingStart
                val composingEndBefore = composingEnd
                val caretBefore = mirrorCaret
                val bufferBefore = session.bufferText()
                if (from == to) {
                    composingStart = -1
                    composingEnd = -1
                } else {
                    composingStart = from
                    composingEnd = to
                }
                setComposingPreview()
                logImeEdit(
                    method = "setComposingRegion",
                    text = "",
                    composingBeforeStart = composingStartBefore,
                    composingBeforeEnd = composingEndBefore,
                    composingAfterStart = composingStart,
                    composingAfterEnd = composingEnd,
                    caretBefore = caretBefore,
                    caretAfter = mirrorCaret,
                    bufferBefore = bufferBefore,
                    bufferAfter = session.bufferText(),
                )
            }
        }
        return true
    }

    override fun finishComposingText(): Boolean {
        mainHandler.post {
            synchronized(lock) { commitPendingCompositionLocked("finishComposingText") }
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val before = beforeLength.coerceAtLeast(0)
        val after = afterLength.coerceAtLeast(0)
        mainHandler.post {
            synchronized(lock) {
                val caretBefore = mirrorCaret
                val composingBeforeStart = composingStart
                val composingBeforeEnd = composingEnd
                val bufferBefore = session.bufferText()
                val deleted = deleteAround(mirrorCaret - before, mirrorCaret + after)
                logImeEdit(
                    method = "deleteSurroundingText",
                    text = deleted,
                    composingBeforeStart = composingBeforeStart,
                    composingBeforeEnd = composingBeforeEnd,
                    composingAfterStart = composingStart,
                    composingAfterEnd = composingEnd,
                    caretBefore = caretBefore,
                    caretAfter = mirrorCaret,
                    bufferBefore = bufferBefore,
                    bufferAfter = session.bufferText(),
                )
            }
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        mainHandler.post {
            synchronized(lock) {
                val caretBefore = mirrorCaret
                val composingBeforeStart = composingStart
                val composingBeforeEnd = composingEnd
                val bufferBefore = session.bufferText()
                val start = codePointsBack(mirrorCaret, beforeLength)
                val end = codePointsForward(mirrorCaret, afterLength)
                val deleted = deleteAround(start, end)
                logImeEdit(
                    method = "deleteSurroundingTextInCodePoints",
                    text = deleted,
                    composingBeforeStart = composingBeforeStart,
                    composingBeforeEnd = composingBeforeEnd,
                    composingAfterStart = composingStart,
                    composingAfterEnd = composingEnd,
                    caretBefore = caretBefore,
                    caretAfter = mirrorCaret,
                    bufferBefore = bufferBefore,
                    bufferAfter = session.bufferText(),
                )
            }
        }
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        mainHandler.post {
            synchronized(lock) {
                mirrorCaret = end.coerceIn(0, mirror.length)
                setEngineCaretTo(mirrorCaret)
                pullSelectionFromEngine()
                onImeCaretMoved()
            }
        }
        return true
    }

    override fun beginBatchEdit(): Boolean = true

    override fun endBatchEdit(): Boolean = true

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val handled = handleKeyEvent(event)
        if (!handled) {
            // No platform default to defer to without a View.
            return false
        }
        return true
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

    // No View means the OS gets no CursorAnchorInfo; the editor draws its own
    // caret, so returning false keeps the IME from requesting cursor geometry.

    override fun performEditorAction(id: Int): Boolean = false

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false

    override fun performContextMenuAction(id: Int): Boolean = false

    override fun clearMetaKeyStates(states: Int): Boolean = false

    override fun reportFullscreenMode(enabled: Boolean): Boolean = false

    override fun closeConnection() {
        mainHandler.post {
            synchronized(lock) { commitPendingCompositionLocked("closeConnection") }
        }
    }

    // ------------------------------------------------------------------
    // engine ↔ mirror bookkeeping (main looper, lock held)
    // ------------------------------------------------------------------

    private fun engineCaretUtf16(buffer: String, session: EditorSession): Int {
        val byteOffset = TextIndex.absoluteByteOffsetOfCursor(session)
            .coerceIn(0L, buffer.toByteArray(Charsets.UTF_8).size.toLong())
        return TextIndex.utf16IndexAtByteOffset(buffer, byteOffset)
    }

    /** The engine text the IME edits: the mirror minus the live composing span. */
    private fun engineTextToApply(): String =
        if (composingStart < 0) {
            mirror
        } else {
            mirror.substring(0, composingStart) + mirror.substring(composingEnd)
        }

    /** Moves the engine cursor to the mirror caret's UTF-16 offset. */
    private fun setEngineCaretTo(mirrorOffset: Int) {
        val engineText = engineTextToApply()
        val engineOffset = mirrorOffsetToEngine(mirrorOffset).coerceIn(0, engineText.length)
        val byteOffset = TextIndex.utf8Length(engineText.substring(0, engineOffset))
        val (row, column) = TextIndex.rowColAtByteOffset(engineText, byteOffset)
        session.setCursor(CursorPosition(row.toULong(), column.toULong()))
    }

    /** Maps a mirror UTF-16 offset into engine-text UTF-16 space. */
    private fun mirrorOffsetToEngine(offset: Int): Int {
        if (composingStart < 0) return offset
        return when {
            offset < composingStart -> offset
            offset > composingEnd -> offset - (composingEnd - composingStart)
            else -> composingStart
        }
    }

    /** Maps an engine-text UTF-16 offset back into mirror space. */
    private fun engineOffsetToMirror(offset: Int): Int {
        if (composingStart < 0) return offset
        return if (offset >= composingStart) offset + (composingEnd - composingStart) else offset
    }

    /** Re-reads the authoritative engine caret into [mirrorCaret]. */
    private fun pullSelectionFromEngine() {
        val engineText = engineTextToApply()
        val cursor = session.cursor()
        val byteOffset =
            TextIndex.byteOffsetAtRowCol(engineText, cursor.row.toInt(), cursor.column.toInt())
        val engineUtf16 = TextIndex.utf16IndexAtByteOffset(engineText, byteOffset.toLong())
        mirrorCaret = engineOffsetToMirror(engineUtf16)
    }

    private fun setComposingPreview() {
        val preview = if (composingStart < 0) {
            null
        } else {
            // A composition crossing a newline would re-flow the whole row from
            // a stale top; preview only up to the first break, the rest commits
            // normally on release.
            mirror.substring(composingStart, composingEnd)
                .substringBefore('\n')
                .takeIf { it.isNotEmpty() }
        }
        if (preview != composingPreview) {
            composingPreview = preview
            onComposingChanged(preview)
        }
    }

    /** Applies one IME edit to the engine and refreshes the mirror/caret. */
    private fun syncEngineAndNotify() {
        val bufferBefore = session.bufferText()
        applyImeEdit(session, engineTextToApply())
        setEngineCaretTo(mirrorCaret)
        pullSelectionFromEngine()
        if (session.bufferText() != bufferBefore) {
            onEdited()
        } else {
            // No net text change (e.g. an edit that cancelled out); the caret
            // movement alone is what the preview needs to reflect.
            onImeCaretMoved()
        }
        setComposingPreview()
    }

    /**
     * Emits one diagnostic line per IME edit call on the main looper (lock
     * held): the method name, the text it carried, the composing span and
     * UTF-16 caret before/after, and whether this call actually mutated the
     * engine buffer (a composing-only edit holds the span out of the engine
     * and leaves `bufferText` unchanged). No-op unless the host wired
     * [onLog]. Used exclusively for on-device Bug A debugging; it never
     * touches edit logic.
     */
    private fun logImeEdit(
        method: String,
        text: String,
        composingBeforeStart: Int,
        composingBeforeEnd: Int,
        composingAfterStart: Int,
        composingAfterEnd: Int,
        caretBefore: Int,
        caretAfter: Int,
        bufferBefore: String,
        bufferAfter: String,
    ) {
        val log = onLog ?: return
        val esc = text.replace("\n", "\\n").replace("\u0000", "\\u0000")
        log(
            "ime method=$method text=\"$esc\" " +
                "composingBefore=[$composingBeforeStart,$composingBeforeEnd) " +
                "composingAfter=[$composingAfterStart,$composingAfterEnd) " +
                "mutated=${bufferAfter != bufferBefore} " +
                "utf16CaretBefore=$caretBefore utf16CaretAfter=$caretAfter " +
                "bufferLenBefore=${bufferBefore.length} bufferLenAfter=${bufferAfter.length}",
        )
    }

    /** Lands the mirror — with any composing span — into the engine. (lock held) */
    private fun commitPendingCompositionLocked(method: String) {
        if (composingStart < 0) return
        val composingStartBefore = composingStart
        val composingEndBefore = composingEnd
        val spanText = mirror.substring(composingStartBefore, composingEndBefore)
        val caret = mirrorCaret
        val bufferBefore = session.bufferText()
        composingStart = -1
        composingEnd = -1
        applyImeEdit(session, mirror)
        setEngineCaretTo(caret)
        pullSelectionFromEngine()
        onEdited()
        setComposingPreview()
        logImeEdit(
            method = method,
            text = spanText,
            composingBeforeStart = composingStartBefore,
            composingBeforeEnd = composingEndBefore,
            composingAfterStart = composingStart,
            composingAfterEnd = composingEnd,
            caretBefore = caret,
            caretAfter = mirrorCaret,
            bufferBefore = bufferBefore,
            bufferAfter = session.bufferText(),
        )
    }

    /** Replaces [mirror] range [start, end) with [replacement], adjusting the
     *  composing span and dropping it when the edit touches it. */
    private fun replaceMirror(start: Int, end: Int, replacement: String) {
        val from = start.coerceIn(0, mirror.length)
        val to = end.coerceIn(from, mirror.length)
        mirror = mirror.substring(0, from) + replacement + mirror.substring(to)
        if (composingStart < 0) return
        val delta = replacement.length - (to - from)
        when {
            composingEnd <= from -> {
                // Span sits strictly before the edit, whose length change is
                // after it — the span's indices do not move.
            }
            to <= composingStart -> {
                // Span sits strictly after the edit; shift it by the length
                // delta of the text inserted before it.
                composingStart += delta
                composingEnd += delta
            }
            else -> {
                // The edit overlaps the span — the span no longer describes
                // a contiguous run, so drop it.
                composingStart = -1
                composingEnd = -1
            }
        }
    }

    /** Deletes [start, end) from the mirror and syncs the engine; returns the
     *  deleted text so callers can log what was removed. (lock held) */
    private fun deleteAround(start: Int, end: Int): String {
        val from = start.coerceIn(0, mirror.length)
        val to = end.coerceIn(from, mirror.length)
        val deleted = mirror.substring(from, to)
        replaceMirror(from, to, "")
        mirrorCaret = from
        setEngineCaretTo(mirrorCaret)
        syncEngineAndNotify()
        return deleted
    }

    private fun codePointsBack(from: Int, n: Int): Int {
        var offset = from
        var remaining = n
        while (remaining > 0 && offset > 0) {
            offset--
            // A low surrogate that follows its high surrogate is one code
            // point: back over both (malformed lone surrogates just step one).
            if (mirror[offset].isLowSurrogate() && offset > 0 &&
                mirror[offset - 1].isHighSurrogate()
            ) {
                offset--
            }
            remaining--
        }
        return offset
    }

    private fun codePointsForward(from: Int, n: Int): Int {
        var offset = from
        var remaining = n
        while (remaining > 0 && offset < mirror.length) {
            offset++
            if (mirror[offset - 1].isHighSurrogate() && offset < mirror.length &&
                mirror[offset].isLowSurrogate()
            ) {
                offset++
            }
            remaining--
        }
        return offset
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return true
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> {
                mainHandler.post {
                    synchronized(lock) {
                        deleteAround(codePointsBack(mirrorCaret, 1), mirrorCaret)
                    }
                }
                true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                mainHandler.post {
                    synchronized(lock) {
                        deleteAround(mirrorCaret, codePointsForward(mirrorCaret, 1))
                    }
                }
                true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                commitText("\n", 1)
                true
            }
            else -> {
                val char = event.getUnicodeChar(event.metaState)
                if (char != 0) {
                    commitText(char.toString(), 1)
                    true
                } else {
                    false
                }
            }
        }
    }
}
