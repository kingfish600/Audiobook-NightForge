package com.forge.audiobookforge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
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
            CompositionLocalProvider(LocalAppContainer provides container) {
                ForgeTheme {
                    ForgeRoot()
                }
            }
        }
    }
}
