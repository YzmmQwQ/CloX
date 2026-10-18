package com.ailife.clox.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import com.ailife.clox.applist.AppListPrefs
import com.ailife.clox.settings.compose.AboutScreen
import com.ailife.clox.settings.compose.AnimationScreen
import com.ailife.clox.settings.compose.AodScreen
import com.ailife.clox.settings.compose.AppListScreen
import com.ailife.clox.settings.compose.AppearanceScreen
import com.ailife.clox.settings.compose.DebugScreen
import com.ailife.clox.settings.compose.FolderMembersScreen
import com.ailife.clox.settings.compose.FoldersScreen
import com.ailife.clox.settings.compose.GesturesScreen
import com.ailife.clox.settings.compose.HealthScreen
import com.ailife.clox.settings.compose.HideAppsScreen
import com.ailife.clox.settings.compose.LocationScreen
import com.ailife.clox.settings.compose.LocalSettingsNavigator
import com.ailife.clox.settings.compose.NotificationScreen
import com.ailife.clox.settings.compose.QuickSettingsScreen
import com.ailife.clox.settings.compose.RootSettingsScreen
import com.ailife.clox.settings.compose.RtosScreen
import com.ailife.clox.settings.compose.SensorsScreen
import com.ailife.clox.settings.compose.SettingsRoutes
import com.ailife.clox.settings.compose.TapActionsScreen
import com.ailife.clox.settings.compose.TileEditorScreen
import com.ailife.clox.settings.compose.WatchfaceScreen
import com.ailife.clox.settings.compose.WeatherScreen
import com.ailife.clox.ui.theme.CloXWearTheme
import com.ailife.clox.ui.theme.ScreenShapeConfig
import com.ailife.clox.update.UpdateController

/**
 * Settings host: ONE [AppScaffold] (TimeText) wrapping a [SwipeDismissableNavHost],
 * with every screen implemented as a Wear M3 ScreenScaffold composable.
 */
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ScreenShapeConfig.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val tapActions = intent?.getStringExtra(EXTRA_OPEN_FRAGMENT) == FRAGMENT_TAP_ACTIONS
        val folderId = intent?.getStringExtra(EXTRA_OPEN_FOLDER)
        setContent {
            CloXWearTheme {
                SettingsApp(
                    openTapActions = tapActions,
                    initialFolderId = folderId,
                )
            }
        }
        UpdateController(this).check(userInitiated = false)
    }

    companion object {
        private const val EXTRA_OPEN_FRAGMENT = "clox.settings.open_fragment"
        const val FRAGMENT_TAP_ACTIONS = "tap_actions"

        /** Intent extra: folder id to open directly in the member picker. */
        const val EXTRA_OPEN_FOLDER = "clox.settings.open_folder"

        /** Intent that opens Settings directly on the Tap Actions editor. */
        fun tapActionsIntent(ctx: Context): Intent =
            Intent(ctx, SettingsActivity::class.java)
                .putExtra(EXTRA_OPEN_FRAGMENT, FRAGMENT_TAP_ACTIONS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun SettingsApp(openTapActions: Boolean, initialFolderId: String?) {
    val navController = rememberSwipeDismissableNavController()
    val navState = rememberSwipeDismissableNavHostState(rememberSwipeToDismissBoxState())

    CompositionLocalProvider(
        LocalSettingsNavigator provides { route -> navController.navigate(route) }
    ) {
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = SettingsRoutes.ROOT,
                state = navState,
            ) {
                composable(SettingsRoutes.ROOT) {
                    RootRoute(navController)
                }
                composable(SettingsRoutes.NOTIFICATIONS) { NotificationScreen() }
                composable(SettingsRoutes.QUICK_SETTINGS) { QuickSettingsScreen() }
                composable(SettingsRoutes.APP_LIST) { AppListScreen() }
                composable(SettingsRoutes.ANIMATIONS) { AnimationScreen() }
                composable(SettingsRoutes.WATCHFACE) { WatchfaceScreen() }
                composable(SettingsRoutes.GESTURES) { GesturesScreen() }
                composable(SettingsRoutes.AOD) { AodScreen() }
                composable(SettingsRoutes.APPEARANCE) { AppearanceScreen() }
                composable(SettingsRoutes.TAP_ACTIONS) { TapActionsScreen() }
                composable(SettingsRoutes.WEATHER) { WeatherScreen() }
                composable(SettingsRoutes.LOCATION) { LocationScreen() }
                composable(SettingsRoutes.HEALTH) { HealthScreen() }
                composable(SettingsRoutes.SENSORS) { SensorsScreen() }
                composable(SettingsRoutes.HIDE_APPS) { HideAppsScreen() }
                composable(SettingsRoutes.FOLDERS) { FoldersScreen() }
                composable(SettingsRoutes.FOLDER_MEMBERS) { entry ->
                    FolderMembersScreen(
                        folderId = entry.arguments?.getString("folderId").orEmpty())
                }
                composable(SettingsRoutes.RTOS) { RtosScreen() }
                composable(SettingsRoutes.DEBUG) { DebugScreen() }
                composable(SettingsRoutes.ABOUT) { AboutScreen() }
                composable(SettingsRoutes.TILE_EDITOR) { TileEditorScreen() }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (openTapActions) navController.navigate(SettingsRoutes.TAP_ACTIONS)
        initialFolderId?.let { navController.navigate(SettingsRoutes.folderMembers(it)) }
    }
}

@Composable
private fun RootRoute(navController: NavHostController) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hideAppsVisible by remember {
        mutableStateOf(AppListPrefs.hideAppsPassword(ctx) == null)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hideAppsVisible = AppListPrefs.hideAppsPassword(ctx) == null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    RootSettingsScreen(hideAppsVisible = hideAppsVisible) { route ->
        navController.navigate(route)
    }
}
