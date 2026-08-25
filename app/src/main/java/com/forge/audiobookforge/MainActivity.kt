package com.forge.audiobookforge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.forge.audiobookforge.di.LocalAppContainer
import com.forge.audiobookforge.ui.ForgeRoot
import com.forge.audiobookforge.ui.theme.ForgeTheme

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val container = (application as ForgeApp).container
        setContent {
            // Day mode: keep the display on while a render runs and this app is
            // frontmost — user watches progress/listens without throttling.
            val forgeScreen by container.settings.forgeScreen.collectAsState()
            val convState by container.conversion.state.collectAsState()
            LaunchedEffect(forgeScreen, convState) {
                val running = convState is com.forge.audiobookforge.conversion.ConversionState.Running
                if (forgeScreen == "day" && running) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            CompositionLocalProvider(LocalAppContainer provides container) {
                ForgeTheme {
                    ForgeRoot()
                }
            }
        }
    }
}
