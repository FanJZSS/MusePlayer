package com.muse.player.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.muse.player.ui.library.FolderScreen
import com.muse.player.ui.library.LibraryScreen
import com.muse.player.ui.library.LibraryViewModel
import com.muse.player.ui.settings.SettingsScreen

@Composable
fun MuseApp() {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()

    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(navController = navController, viewModel = libraryViewModel)
        }
        composable("folder/{bucketId}") { entry ->
            FolderScreen(
                navController = navController,
                viewModel = libraryViewModel,
                bucketId = entry.arguments?.getString("bucketId").orEmpty()
            )
        }
        composable("settings") {
            SettingsScreen(navController = navController)
        }
    }
}
