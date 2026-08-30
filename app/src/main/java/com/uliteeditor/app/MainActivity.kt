package com.uliteeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.uliteeditor.editor.metrics.EditorMetrics
import com.uliteeditor.editor.settings.EditorSettings
import com.uliteeditor.editor.theme.UliteEditorTheme
import com.uliteeditor.editor.view.EditorComponent

class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UliteEditorTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    // The settings object is owned here and shared with the
                    // editor: toggling the bar's action re-renders the editor
                    // live — the editor exposes the switch, the host pulls it.
                    val editorSettings = remember { EditorSettings() }
                    var metrics by remember { mutableStateOf<EditorMetrics?>(null) }
                    TopAppBar(
                        title = { Text("UliteEditor") },
                        actions = {
                            IconToggleButton(
                                checked = editorSettings.wordWrapEnabled,
                                onCheckedChange = { editorSettings.wordWrapEnabled = it },
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.WrapText,
                                    contentDescription = "Toggle word wrap",
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    )
                    EditorComponent(
                        modifier = Modifier.weight(1f),
                        settings = editorSettings,
                        onMetricsChange = { metrics = it },
                    )
                    // The info bar is a host concern: the library only emits
                    // EditorMetrics, apps that use it decide what to display.
                    EditorStatusBar(metrics)
                }
            }
        }
    }
}

@Composable
private fun EditorStatusBar(metrics: EditorMetrics?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = metrics?.let {
                "Ln ${it.line}, Col ${it.column}  ·  ${it.charIndex} ch  ·  " +
                    "x${it.scrollX.toInt()} y${it.scrollY.toInt()}  ·  ${it.fontSizeSp.toInt()}sp"
            } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}