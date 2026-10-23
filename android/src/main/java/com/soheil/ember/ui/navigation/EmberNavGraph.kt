package com.soheil.ember.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.soheil.ember.EmberVpnApp
import com.soheil.ember.ui.screens.HomeScreen
import com.soheil.ember.ui.screens.LoginScreen
import com.soheil.ember.ui.screens.LogsScreen
import com.soheil.ember.ui.screens.ServerListScreen
import com.soheil.ember.ui.screens.SettingsScreen
import com.soheil.ember.ui.screens.SplashScreen
import com.soheil.ember.ui.theme.ThemeController

object EmberRoutes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val HOME = "home"
    const val SERVERS = "servers"
    const val SETTINGS = "settings"
    const val LOGS = "logs"
}

@Composable
fun EmberNavGraph(
    navController: NavHostController = rememberNavController(),
    app: EmberVpnApp,
    themeController: ThemeController,
    onRequestConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    NavHost(navController = navController, startDestination = EmberRoutes.SPLASH) {
        composable(EmberRoutes.SPLASH) {
            SplashScreen(
                authRepository = app.authRepository,
                onSignedIn = {
                    navController.navigate(EmberRoutes.HOME) { popUpTo(EmberRoutes.SPLASH) { inclusive = true } }
                },
                onNeedsLogin = {
                    navController.navigate(EmberRoutes.LOGIN) { popUpTo(EmberRoutes.SPLASH) { inclusive = true } }
                },
            )
        }
        composable(EmberRoutes.LOGIN) {
            LoginScreen(
                authRepository = app.authRepository,
                onSignedIn = {
                    navController.navigate(EmberRoutes.HOME) { popUpTo(EmberRoutes.LOGIN) { inclusive = true } }
                },
            )
        }
        composable(EmberRoutes.HOME) {
            HomeScreen(
                app = app,
                themeController = themeController,
                onRequestConnect = onRequestConnect,
                onDisconnect = onDisconnect,
                onOpenServers = { navController.navigate(EmberRoutes.SERVERS) },
                onOpenSettings = { navController.navigate(EmberRoutes.SETTINGS) },
            )
        }
        composable(EmberRoutes.SERVERS) {
            ServerListScreen(
                serverListClient = app.serverListClient,
                proxyStateStore = app.proxyStateStore,
                onServerSelected = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable(EmberRoutes.SETTINGS) {
            SettingsScreen(
                settingsStore = app.settingsStore,
                onOpenLogs = { navController.navigate(EmberRoutes.LOGS) },
                onSignOut = {
                    onDisconnect()
                    app.tokenStore.clear()
                    app.settingsStore.clearAccountSession()
                    app.proxyStateStore.clear()
                    navController.navigate(EmberRoutes.LOGIN) { popUpTo(0) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(EmberRoutes.LOGS) {
            LogsScreen(onBack = { navController.popBackStack() })
        }
    }
}
