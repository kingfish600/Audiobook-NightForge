package com.forge.audiobookforge.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.forge.audiobookforge.di.LocalAppContainer
import com.forge.audiobookforge.ui.screens.BookDetailScreen
import com.forge.audiobookforge.ui.screens.LibraryScreen
import com.forge.audiobookforge.ui.screens.SettingsScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeRoot() {
    val nav = rememberNavController()
    val container = LocalAppContainer.current
    val playerUi by container.player.ui.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audiobook Forge") },
                navigationIcon = {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                },
                actions = {
                    IconButton(onClick = { nav.navigate("settings") }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = { if (playerUi.visible) MiniPlayer(playerUi) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "library",
            modifier = Modifier.padding(padding),
        ) {
            composable("library") {
                LibraryScreen(onOpenBook = { id -> nav.navigate("book/$id") })
            }
            composable(
                route = "book/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                BookDetailScreen(bookId = entry.arguments?.getString("bookId"))
            }
            composable("settings") { SettingsScreen() }
        }
    }
}
