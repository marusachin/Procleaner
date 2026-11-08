package com.example.procleaner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFFE1F2FC.toInt()
        window.navigationBarColor = 0xFFE1F2FC.toInt()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        Scheduler.refresh(this)
        setContent {
            MaterialTheme(colorScheme = SkyScheme) { App() }
        }
    }
}
