package com.uliteeditor.editor.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Editor preferences apps can tune and hand to [com.uliteeditor.editor.view.EditorComponent].
 * The component reads them live (composition-observed), so mutating a property
 * re-renders immediately — the host never rebuilds state lists the editor
 * owns; it just flips switches.
 */
class EditorSettings {
    /** When true, soft-wraps long rows at the component's width. */
    var wordWrapEnabled by mutableStateOf(true)
}