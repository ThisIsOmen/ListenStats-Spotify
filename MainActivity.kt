package com.listenstats

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Color.White) {
                    App()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Bus.resume.update { it + 1 }
        Genres.kick(this)
    }
}
