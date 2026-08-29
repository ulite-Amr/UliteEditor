package com.uliteeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.uliteeditor.app.sample.EditorBridgeSample
import com.uliteeditor.editor.theme.UliteEditorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UliteEditorTheme {
                EditorBridgeSample()
            }
        }
    }
}