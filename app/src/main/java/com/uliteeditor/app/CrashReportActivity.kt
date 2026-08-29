package com.uliteeditor.app

import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.uliteeditor.editor.theme.UliteEditorTheme
import kotlinx.coroutines.delay

/**
 * The in-app crash screen: headline with the failing thread, device details,
 * and the raw stack trace. It is normally reached from [CrashHandler], which
 * has already copied the report to the clipboard — the "Copy report" button
 * re-copies on demand. "Close" (or back) kills the crashed process.
 */
class CrashReportActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD = "crash_thread"
        const val EXTRA_CAUSE = "crash_cause"
        const val EXTRA_MESSAGE = "crash_message"
        const val EXTRA_STACK_TRACE = "crash_stack_trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val threadName = intent.getStringExtra(EXTRA_THREAD).orEmpty()
        val cause = intent.getStringExtra(EXTRA_CAUSE).orEmpty()
        val message = intent.getStringExtra(EXTRA_MESSAGE).orEmpty()
        val stackTrace = intent.getStringExtra(EXTRA_STACK_TRACE).orEmpty()
        val device = collectDeviceDetails(this)
        setContent {
            UliteEditorTheme {
                val context = LocalContext.current
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) {
                    if (copied) {
                        copyReportToClipboard(
                            context,
                            buildReport(device, threadName, cause, message, stackTrace),
                        )
                        delay(2_000L)
                        copied = false
                    }
                }
                BackHandler { Process.killProcess(Process.myPid()) }
                CrashReportContent(
                    device = device,
                    threadName = threadName,
                    cause = cause,
                    message = message,
                    stackTrace = stackTrace,
                    copied = copied,
                    onCopy = { copied = true },
                    onClose = { Process.killProcess(Process.myPid()) },
                )
            }
        }
    }
}

@Composable
private fun CrashReportContent(
    device: DeviceDetails,
    threadName: String,
    cause: String,
    message: String,
    stackTrace: String,
    copied: Boolean,
    onCopy: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "UliteEditor stopped unexpectedly",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "The report was copied to your clipboard. Paste it somewhere "
                + "safe, then share it with the developer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Device details", style = MaterialTheme.typography.labelLarge)
                Text(
                    "  Model: ${device.manufacturer} ${device.model}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "  Android: ${device.androidRelease} (API ${device.sdkInt})",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "  App version: ${device.appVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "  Thread: $threadName",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "  $cause${if (message.isNotBlank()) ": $message" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Text(
                    text = if (stackTrace.isBlank()) "No stack trace captured." else stackTrace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onCopy, modifier = Modifier.weight(1f)) {
                Text(if (copied) "Copied" else "Copy report")
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                Text("Close")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}