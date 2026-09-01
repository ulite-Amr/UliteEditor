package com.uliteeditor.editor.ime

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.text.style.ResolvedTextDirection

/**
 * Maps the active soft-keyboard input language to a caret-anchor direction, so
 * a caret sitting on an LTR↔RTL BiDi run boundary hugs the side the user is
 * actually typing into (typing Arabic → the RTL run's side, Latin → LTR).
 *
 * Direction is read from the current IME subtype's language tag: a tag in the
 * RTL set maps to Rtl, every other non-blank tag maps to Ltr (the platform
 * default, so a Latin keyboard keeps the LTR side of a boundary), and when
 * the keyboard exposes no subtype or an empty tag this returns null and the
 * caret rule falls back to the nearest strong character — the editor degrades
 * gracefully without an Android-IME dependency.
 */
internal object EditorInputDirection {
    private val RTL_LANG_TAGS = setOf(
        // Arabic
        "ar", "fa", "ps", "sd", "ur", "ug", "ckb", "ars", "prs",
        // Hebrew
        "he", "iw", "yi",
        // Other RTL scripts
        "dv", "nqo", "syr",
    )

    /**
     * The active keyboard's direction: Rtl when its primary language is in
     * the mapped RTL set, Ltr for any other non-blank tag, or null when the
     * IME exposes no subtype or an empty language tag.
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
