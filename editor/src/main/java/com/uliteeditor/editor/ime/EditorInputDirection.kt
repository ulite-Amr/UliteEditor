package com.uliteeditor.editor.ime

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.text.style.ResolvedTextDirection

/**
 * Maps the active soft-keyboard input language to a caret-anchor direction, so
 * a caret sitting on an LTR↔RTL BiDi run boundary hugs the side the user is
 * actually typing into (typing Arabic → the RTL run's side, Latin → LTR).
 *
 * Direction is read from the current IME subtype's language tag. When the
 * keyboard exposes none (or reports a script we do not map) this returns null
 * and the caret rule falls back to the nearest strong character, so the
 * editor degrades gracefully without an Android-IME dependency.
 */
internal object EditorInputDirection {
    private val RTL_LANG_TAGS = setOf(
        // Arabic
        "ar", "fa", "ps", "sd", "ur", "ug", "ckb", "ars", "uz",
        // Hebrew
        "he", "iw", "yi",
        // Other RTL scripts
        "dv", "nqo", "syr", "prv",
    )

    /**
     * The [ResolvedTextDirection] of the active keyboard, or null when the
     * IME exposes no language or the language is not mapped.
     */
    fun current(context: Context): ResolvedTextDirection? {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return null
        val tag = imm.currentInputMethodSubtype?.languageTag ?: return null
        if (tag.isBlank()) return null
        val primary = tag.substringBefore('-').substringBefore('_').lowercase()
        return if (primary in RTL_LANG_TAGS) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr
    }
}