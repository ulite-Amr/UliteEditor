package com.uliteeditor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import com.uliteeditor.editor.theme.UliteEditorTheme
import com.uliteeditor.editor.view.EditorComponent

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UliteEditorTheme {
                EditorComponent(modifier = Modifier.safeDrawingPadding())
            }
        }
    }
}