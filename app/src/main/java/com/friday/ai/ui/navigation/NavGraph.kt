package com.friday.ai.ui.navigation

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.friday.ai.MainActivity
import com.friday.ai.core.FileAnalyzer
import com.friday.ai.core.ScreenAnalyzer
import com.friday.ai.ui.chat.ChatScreen
import com.friday.ai.ui.chat.ChatViewModel
import com.friday.ai.ui.dashboard.LazuriDashboardScreen
import com.friday.ai.ui.diagnostics.DiagnosticsScreen
import com.friday.ai.ui.settings.SettingsScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun FridayNavGraph(activity: MainActivity) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "chat") {
        composable("chat") {
            val viewModel: ChatViewModel = koinViewModel()
            val screenAnalyzer: ScreenAnalyzer = koinInject()
            val fileAnalyzer: FileAnalyzer = koinInject()

            val screenCaptureLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    screenAnalyzer.captureScreen(
                        resultCode = result.resultCode,
                        data = result.data!!,
                        onCaptured = { base64 -> viewModel.onScreenCaptured(base64) },
                        onError = { err -> viewModel.onDismissError() }
                    )
                }
            }

            val filePickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let { uri ->
                        val fileContent = fileAnalyzer.readFile(uri)
                        if (fileContent != null) {
                            viewModel.onFileSelected(fileContent)
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                activity.onWakeWordActivated = {
                    viewModel.onMicClick()
                }
                viewModel.onAnalyzeScreenRequested = {
                    val intent = screenAnalyzer.getProjectionIntent(activity)
                    screenCaptureLauncher.launch(intent)
                }
                viewModel.onAnalyzeFileRequested = {
                    val intent = fileAnalyzer.getFilePickerIntent()
                    filePickerLauncher.launch(intent)
                }
            }

            ChatScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                viewModel = viewModel
            )
        }
        composable("settings") {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenDashboard = { navController.navigate("lazuri_dashboard") },
                onOpenDiagnostics = { navController.navigate("diagnostics") }
            )
        }
        composable("diagnostics") {
            DiagnosticsScreen(
                viewModel = koinViewModel(),
                onNavigateBack = { navController.popBackStack() },
                onOpenSettings = {
                    // Settings is where the screen is opened from; going "back" lands there.
                    if (!navController.popBackStack("settings", inclusive = false)) navController.navigate("settings")
                }
            )
        }
        composable("lazuri_dashboard") {
            LazuriDashboardScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
