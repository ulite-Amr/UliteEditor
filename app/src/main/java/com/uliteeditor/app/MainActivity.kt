package com.uliteeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.uliteeditor.app.BuildConfig
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
                    val context = LocalContext.current
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
                            IconButton(onClick = { copyLatestLogToClipboard(context) }) {
                                Icon(
                                    imageVector = Icons.Filled.ContentCopy,
                                    contentDescription = "Copy the session log",
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
                        onLog = { EditorLog.info(it) },
                    )
                    // The info bar is a host concern: the library only emits
                    // EditorMetrics, apps that use it decide what to display.
                    EditorStatusBar(metrics)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Reopen a fresh session on each foreground (a prior onStop closed the
        // writer) so a long-lived process keeps logging across backgroundings.
        EditorLog.open(this)
    }

    override fun onStop() {
        super.onStop()
        // Flush + close the session log cleanly so backgrounding leaves a
        // readable file (no half-written trailing line when the user returns).
        EditorLog.close()
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
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
            Text(
                text = "sha ${BuildConfig.GIT_SHA}" + if (BuildConfig.GIT_DIRTY) " (dirty)" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Copies the newest session log to the system clipboard (no adb needed on the
 * device) and confirms with a Toast. A clipboard copy survives the share-sheet
 * dependency on a configured target, so the log is always gettable on-device.
 */
private fun copyLatestLogToClipboard(context: android.content.Context) {
    val text = EditorLog.latestSessionText()
    if (text == null) {
        showToast(context, "No session log written yet.")
        return
    }
    val manager = context.getSystemService(android.content.ClipboardManager::class.java)
    if (manager == null) {
        showToast(context, "Could not copy the session log")
        return
    }
    manager.setPrimaryClip(android.content.ClipData.newPlainText("UliteEditor logs", text))
    showToast(context, "Session log copied to clipboard")
}

private fun showToast(context: android.content.Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}
