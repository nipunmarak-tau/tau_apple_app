package com.example.cameraaccess.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.cameraaccess.ui.RecordingScreen
import com.example.cameraaccess.ui.SettingsScreen
import com.example.cameraaccess.ui.createsiteblock.CreateSiteBlockScreen
import com.example.cameraaccess.ui.dashboard.DashboardScreen
import com.example.cameraaccess.ui.login.LoginScreen
import com.example.cameraaccess.utils.TokenManager
import com.example.cameraaccess.viewmodel.SettingsViewModel

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val tokenManager = TokenManager(context)

    // Determine the start destination based on whether a token exists
    val startDestination = if (tokenManager.getAccessToken() != null) {
        "dashboard"
    } else {
        "login"
    }

    NavHost(navController, startDestination = startDestination) {

        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("dashboard") {
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }



        composable("dashboard") {
            DashboardScreen(
                onStartCapture = {
                    navController.navigate("create_site_block")
                },
                onLogout = {
                    // Clear tokens on logout
                    tokenManager.clear()
                    navController.navigate("login") {
                        popUpTo("dashboard") { inclusive = true }
                    }
                }
            )
        }

        composable("create_site_block") {
            CreateSiteBlockScreen(
                onBack = { navController.popBackStack() },
                onNext = {
                    navController.navigate("record")
                }
            )
        }

        composable("settings") {
            val settingsViewModel: SettingsViewModel = viewModel()
            SettingsScreen(
                viewModel = settingsViewModel,
                onBack = {
                    navController.popBackStack()
                },
                onPreviewSelected = {
                    navController.navigate("record")
                }
            )
        }

        composable("record") {
            RecordingScreen(navController = navController)
        }
    }
}
