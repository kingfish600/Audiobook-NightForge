package com.forge.audiobookforge.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.forge.audiobookforge.ForgeApp
import com.forge.audiobookforge.conversion.ConversionState

/**
 * Night Forge: a pitch-black, zero-brightness fullscreen activity that stays
 * RESUMED for the duration of a render. OEM governors grant big-core access
 * to the frontmost app and revoke it from everyone else — wake locks, thread
 * priorities, and Game Space all failed to override that. Being the foreground
 * app is the one lever they cannot discriminate against.
 *
 * Launched by BookDetail when a render starts with "keep screen awake" enabled.
 * Exits itself the moment the conversion leaves Running state.
 */
class ForgeNightActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // True zero on OLED = pixels off; display state remains "on".
        window.attributes.screenBrightness = 0f

        setContent {
            // Standalone activity: no CompositionLocal provider here — reach
            // the app graph through the Application instance instead.
            val container = (application as ForgeApp).container
            val state by container.conversion.state.collectAsState()

            LaunchedEffect(state) {
                if (state !is ConversionState.Running) finish()
            }

            MaterialTheme(
                colorScheme = MaterialTheme.colorScheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                )
            ) {
                Surface(Modifier.fillMaxSize().background(Color.Black), color = Color.Black) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🌙", color = Color(0xFF1A1A1A), style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(16.dp))
                        val s = state
                        if (s is ConversionState.Running) {
                            Text(
                                "${s.bookTitle}",
                                color = Color(0xFF8F886B),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "chapter ${s.chaptersDone + 1} of ${s.chaptersTotal} — ${s.chapterTitle}",
                                color = Color(0xFF222222),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "forging… leave this screen on",
                                color = Color(0xFF1E1E1E),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            Text("finishing up…", color = Color(0xFF222222), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
