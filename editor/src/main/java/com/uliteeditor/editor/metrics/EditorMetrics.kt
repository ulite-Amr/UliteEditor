package com.uliteeditor.editor.metrics

/**
 * Live editor state a host can render into its own UI (an info bar in the
 * sample app). The library only reports; it never displays this — apps that
 * consume the component decide what to do with it.
 */
data class EditorMetrics(
    val line: ULong,
    val column: ULong,
    /** Absolute cursor position in UTF-16 code units. */
    val charIndex: Long,
    val scrollX: Float,
    val scrollY: Float,
    val fontSizeSp: Float,
)