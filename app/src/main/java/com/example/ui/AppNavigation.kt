package com.example.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.SettingsRepository

enum class AppScreen(val route: String, val title: String) {
    RECORD("record", "Gravar"),
    VIDEOS("videos", "Vídeos"),
    SETTINGS("settings", "Ajustes")
}

@Composable
fun AppNavigation(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    var currentScreen by remember { mutableStateOf(AppScreen.RECORD) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                // Record Tab
                NavigationBarItem(
                    selected = currentScreen == AppScreen.RECORD,
                    onClick = {
                        if (currentScreen != AppScreen.RECORD) {
                            currentScreen = AppScreen.RECORD
                            navController.navigate(AppScreen.RECORD.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.RECORD) Icons.Filled.Videocam else Icons.Outlined.Videocam,
                            contentDescription = "Tela de Gravação"
                        )
                    },
                    label = { Text(AppScreen.RECORD.title) },
                    modifier = Modifier.testTag("tab_record")
                )

                // Videos Tab
                NavigationBarItem(
                    selected = currentScreen == AppScreen.VIDEOS,
                    onClick = {
                        if (currentScreen != AppScreen.VIDEOS) {
                            currentScreen = AppScreen.VIDEOS
                            navController.navigate(AppScreen.VIDEOS.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.VIDEOS) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary,
                            contentDescription = "Meus Vídeos"
                        )
                    },
                    label = { Text(AppScreen.VIDEOS.title) },
                    modifier = Modifier.testTag("tab_videos")
                )

                // Settings Tab
                NavigationBarItem(
                    selected = currentScreen == AppScreen.SETTINGS,
                    onClick = {
                        if (currentScreen != AppScreen.SETTINGS) {
                            currentScreen = AppScreen.SETTINGS
                            navController.navigate(AppScreen.SETTINGS.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    icon = {
                        Icon(
                            imageVector = if (currentScreen == AppScreen.SETTINGS) Icons.Filled.Settings else Icons.Outlined.Settings,
                            contentDescription = "Configurações"
                        )
                    },
                    label = { Text(AppScreen.SETTINGS.title) },
                    modifier = Modifier.testTag("tab_settings")
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppScreen.RECORD.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(AppScreen.RECORD.route) {
                MainScreen(settingsRepository = settingsRepository)
            }
            composable(AppScreen.VIDEOS.route) {
                VideosScreen()
            }
            composable(AppScreen.SETTINGS.route) {
                SettingsScreen(settingsRepository = settingsRepository)
            }
        }
    }
}
