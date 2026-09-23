package com.devfahim.upscaler.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.devfahim.upscaler.R
import com.devfahim.upscaler.domain.repository.AppSettings
import com.devfahim.upscaler.domain.repository.JobsRepository
import com.devfahim.upscaler.domain.repository.SettingsRepository
import com.devfahim.upscaler.ui.navigation.Routes
import com.devfahim.upscaler.ui.screens.about.AboutScreen
import com.devfahim.upscaler.ui.screens.home.HomeScreen
import com.devfahim.upscaler.ui.screens.library.LibraryScreen
import com.devfahim.upscaler.ui.screens.onboarding.OnboardingScreen
import com.devfahim.upscaler.ui.screens.options.VideoOptionsScreen
import com.devfahim.upscaler.ui.screens.processing.ProcessingScreen
import com.devfahim.upscaler.ui.screens.result.ResultScreen
import com.devfahim.upscaler.ui.screens.settings.SettingsScreen
import com.devfahim.upscaler.ui.screens.splash.SplashScreen
import com.devfahim.upscaler.ui.theme.UpscalerTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Root state: settings + first-launch routing. */
@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    jobsRepository: JobsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whether there is any history - drives the Home "recent" strip. */
    val hasJobs: StateFlow<Boolean> = jobsRepository.observeJobs()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}

@Composable
fun UpscalerRoot(
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val settings by rootViewModel.settings.collectAsStateWithLifecycle()
    val current = settings ?: return // brief blank while DataStore loads

    UpscalerTheme(themeMode = current.themeMode) {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination

        val showBottomBar = currentDestination?.hierarchy?.any { dest ->
            dest.route in Routes.bottomBar
        } == true

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        BottomItem.entries.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any {
                                it.route == item.route
                            } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        painter = painterResource(item.icon),
                                        contentDescription = stringResource(item.labelRes),
                                    )
                                },
                                label = { Text(stringResource(item.labelRes)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(bottom = if (showBottomBar) padding.calculateBottomPadding() else Dp(0f))
            ) {
                UpscalerNavHost(
                    navController = navController,
                    startOnboarding = !current.onboardingDone,
                )
            }
        }
    }
}

private enum class BottomItem(
    val route: String,
    val icon: Int,
    val labelRes: Int,
) {
    HOME(Routes.HOME, R.drawable.ic_nav_home, R.string.nav_home),
    LIBRARY(Routes.LIBRARY, R.drawable.ic_photo_library, R.string.nav_library),
    SETTINGS(Routes.SETTINGS, R.drawable.ic_nav_settings, R.string.nav_settings),
}

@Composable
private fun UpscalerNavHost(
    navController: NavHostController,
    startOnboarding: Boolean,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) },
        exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) },
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onDecide = { showOnboarding ->
                    navController.navigate(if (showOnboarding) Routes.ONBOARDING else Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                startOnboarding = startOnboarding,
            )
        }
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onOpenJob = { jobId -> navController.navigate(Routes.result(jobId)) },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onOpenVideoOptions = { encodedUri ->
                    navController.navigate(Routes.videoOptions(encodedUri))
                },
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenJob = { jobId -> navController.navigate(Routes.result(jobId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PROCESSING) { entry ->
            val jobId = entry.arguments?.getString(Routes.PROCESSING_ARG) ?: return@composable
            ProcessingScreen(
                jobId = jobId,
                onDone = {
                    navController.navigate(Routes.result(jobId)) {
                        popUpTo(Routes.PROCESSING) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(Routes.RESULT) { entry ->
            val jobId = entry.arguments?.getString(Routes.RESULT_ARG) ?: return@composable
            ResultScreen(
                jobId = jobId,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.VIDEO_OPTIONS) { entry ->
            val uri = entry.arguments?.getString(Routes.VIDEO_OPTIONS_ARG) ?: return@composable
            VideoOptionsScreen(
                encodedUri = uri,
                onBack = { navController.popBackStack() },
                onStart = { jobId ->
                    navController.navigate(Routes.processing(jobId)) {
                        popUpTo(Routes.VIDEO_OPTIONS) { inclusive = true }
                    }
                },
            )
        }
    }
}
