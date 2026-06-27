package com.hawwwran.shushly

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hawwwran.shushly.feature.about.AboutScreen
import com.hawwwran.shushly.feature.aiconnection.AiConnectionScreen
import com.hawwwran.shushly.feature.aiconnection.AiConnectionViewModel
import com.hawwwran.shushly.feature.common.openAppNotificationSettings
import com.hawwwran.shushly.feature.history.DecisionDetailScreen
import com.hawwwran.shushly.feature.history.HistoryScreen
import com.hawwwran.shushly.feature.history.HistoryViewModel
import com.hawwwran.shushly.feature.home.HomeScreen
import com.hawwwran.shushly.feature.home.HomeViewModel
import com.hawwwran.shushly.feature.onboarding.OnboardingScreen
import com.hawwwran.shushly.feature.picker.AppPickerScreen
import com.hawwwran.shushly.feature.picker.PickerTarget
import com.hawwwran.shushly.feature.picker.PickerViewModel
import com.hawwwran.shushly.feature.settings.SettingsScreen
import com.hawwwran.shushly.feature.settings.StubScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }
}

private object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HISTORY = "history"
    const val ABOUT = "about"
    const val AI_CONNECTION = "ai_connection"
    const val PRIVACY = "privacy"
    const val APP_PICKER = "app_picker"
    const val DECISION_DETAIL = "decision_detail"
}

@Composable
private fun AppNavHost(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val onboardingComplete by viewModel.onboardingComplete.collectAsState()

    // Pin the start destination to the first resolved flag value so the NavHost graph is built once
    // (and isn't yanked when the flag flips true at the end of onboarding).
    var startRoute by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(onboardingComplete) {
        if (startRoute == null) {
            onboardingComplete?.let { startRoute = if (it) Routes.HOME else Routes.ONBOARDING }
        }
    }
    val start = startRoute ?: return // brief blank while DataStore resolves the flag

    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                viewModel = viewModel,
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onChooseApps = { target -> navController.navigate("${Routes.APP_PICKER}/${target.name}") },
            )
        }
        composable(
            route = "${Routes.APP_PICKER}/{target}",
            arguments = listOf(navArgument("target") { type = NavType.StringType }),
        ) {
            AppPickerScreen(
                viewModel = hiltViewModel<PickerViewModel>(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAiConnection = { navController.navigate(Routes.AI_CONNECTION) },
                onPrivacy = { navController.navigate(Routes.PRIVACY) },
                onSystemSettings = { context.openAppNotificationSettings() },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                viewModel = hiltViewModel<HistoryViewModel>(),
                onBack = { navController.popBackStack() },
                onOpenDetail = { id -> navController.navigate("${Routes.DECISION_DETAIL}/$id") },
            )
        }
        composable(
            route = "${Routes.DECISION_DETAIL}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { backStackEntry ->
            DecisionDetailScreen(
                viewModel = hiltViewModel<HistoryViewModel>(),
                id = backStackEntry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AI_CONNECTION) {
            AiConnectionScreen(
                viewModel = hiltViewModel<AiConnectionViewModel>(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PRIVACY) {
            StubScreen(
                title = "Privacy",
                line = "Coming in a later update.",
                onBack = { navController.popBackStack() },
            )
        }
    }
}
