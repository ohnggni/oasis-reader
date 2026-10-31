package com.oasisreader.app.ui

import android.app.Activity
import com.oasisreader.app.ui.theme.isAppDark
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Status-bar icons for screens drawn on the normal page background. */
@Composable
fun PageStatusBarIcons() {
    val dark = isAppDark()
    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).isAppearanceLightStatusBars = !dark
        }
    }
}
