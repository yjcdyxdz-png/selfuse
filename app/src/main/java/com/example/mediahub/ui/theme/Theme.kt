package com.example.mediahub.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        // 手动选择深色/浅色时，状态栏和导航栏图标颜色也要跟着变
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}
