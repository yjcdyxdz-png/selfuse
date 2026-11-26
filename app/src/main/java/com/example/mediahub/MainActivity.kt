package com.example.mediahub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.example.mediahub.data.AppSettings
import com.example.mediahub.ui.AppRoot
import com.example.mediahub.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val modeFlow = remember { AppSettings.themeMode(applicationContext) }
            val mode by modeFlow.collectAsState(initial = AppSettings.THEME_SYSTEM)
            val dark = when (mode) {
                AppSettings.THEME_LIGHT -> false
                AppSettings.THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            AppTheme(dark = dark) { AppRoot() }
        }
    }
}
