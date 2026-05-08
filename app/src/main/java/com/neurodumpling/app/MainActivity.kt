package com.neurodumpling.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.neurodumpling.app.ui.MindMapCanvas
import com.neurodumpling.app.ui.DarkBg
import com.neurodumpling.app.ui.LightBg
import com.neurodumpling.app.viewmodel.MindMapViewModel
import androidx.compose.runtime.SideEffect
import com.google.accompanist.systemuicontroller.rememberSystemUiController

class MainActivity : ComponentActivity() {
    private val viewModel: MindMapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isDark by viewModel.isDarkMode.collectAsState()
            val systemUiController = rememberSystemUiController()
            
            SideEffect {
                systemUiController.setSystemBarsColor(
                    color = android.graphics.Color.TRANSPARENT.let { androidx.compose.ui.graphics.Color(it) },
                    darkIcons = !isDark
                )
            }

            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                    color = if (isDark) DarkBg else LightBg
                ) {
                    MindMapCanvas(viewModel)
                }
            }
        }
    }
}
