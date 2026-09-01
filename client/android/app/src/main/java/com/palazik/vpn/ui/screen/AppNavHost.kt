package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.palazik.vpn.R
import com.palazik.vpn.ui.viewmodel.MainViewModel

sealed class Screen(
    val route: String,
    @androidx.annotation.StringRes val label: Int,
    val icon: ImageVector,
) {
    object Home          : Screen("home",     R.string.nav_home,     Icons.Rounded.Home)
    object Profiles      : Screen("profiles", R.string.nav_profiles, Icons.AutoMirrored.Rounded.List)
    object Subscriptions : Screen("subs",     R.string.nav_subs,     Icons.Rounded.Subscriptions)
    object Settings      : Screen("settings", R.string.nav_settings, Icons.Rounded.Settings)

    // Sub-screen — not a tab, no icon needed for the nav bar
    object Style : Screen("style", R.string.nav_settings, Icons.Rounded.Settings)
    object JsonConfig : Screen("json/{profileId}", R.string.nav_profiles, Icons.AutoMirrored.Rounded.List)
}

@Composable
fun AppNavHost(
    vm: MainViewModel,
    permLauncher: ActivityResultLauncher<Intent>,
) {
    val navController = rememberNavController()
    val shell by vm.shellUi.collectAsStateWithLifecycle()
    val tabs = remember { listOf(Screen.Home, Screen.Profiles, Screen.Settings) }
    val snackState = remember { SnackbarHostState() }

    LaunchedEffect(shell.snackMessage) {
        shell.snackMessage?.let { message ->
            val result = snackState.showSnackbar(
                message = message,
                actionLabel = shell.snackActionLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoSnackAction()
            vm.clearSnack()
        }
    }

    // Hide the bottom bar when on the Style sub-screen
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route
    // Hide the bottom bar on settings sub-screens (Style + settings/*)
    val showBottomBar = currentRoute == null ||
        (currentRoute != Screen.Style.route &&
            !currentRoute.startsWith("settings/") &&
            !currentRoute.startsWith("json/"))

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackState) },
        bottomBar = {
            if (showBottomBar) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 8.dp,
                        shape = CircleShape,
                        modifier = Modifier.border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                            CircleShape,
                        ),
                    ) {
                        Row(
                            Modifier.padding(5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val currentDest = navBackStack?.destination
                            tabs.forEach { screen ->
                                val selected = currentDest?.hierarchy?.any { it.route == screen.route } == true
                                NavPill(
                                    screen = screen,
                                    selected = selected,
                                    animationsEnabled = shell.animationsEnabled,
                                    onClick = {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = {
                if (shell.animationsEnabled) {
                    fadeIn(tween(180, easing = EaseOutQuart)) +
                        slideInHorizontally(tween(180, easing = EaseOutQuart)) { it / 14 }
                } else EnterTransition.None
            },
            exitTransition = {
                if (shell.animationsEnabled) {
                    fadeOut(tween(140)) +
                        slideOutHorizontally(tween(140)) { -it / 14 }
                } else ExitTransition.None
            },
            popEnterTransition = {
                if (shell.animationsEnabled) {
                    fadeIn(tween(180, easing = EaseOutQuart)) +
                        slideInHorizontally(tween(180, easing = EaseOutQuart)) { -it / 14 }
                } else EnterTransition.None
            },
            popExitTransition = {
                if (shell.animationsEnabled) {
                    fadeOut(tween(140)) +
                        slideOutHorizontally(tween(140)) { it / 14 }
                } else ExitTransition.None
            },
        ) {
            val back: () -> Unit = { navController.popBackStack() }
            composable(Screen.Home.route)          { HomeScreen(vm, permLauncher) }
            composable(Screen.Profiles.route)      {
                ProfilesScreen(
                    vm,
                    onOpenSubscriptions = { navController.navigate(Screen.Subscriptions.route) },
                    onOpenJson = { profileId -> navController.navigate("json/$profileId") },
                )
            }
            composable(Screen.Subscriptions.route) { SubscriptionsScreen(vm) }
            composable(Screen.Settings.route)      { SettingsScreen(vm, onNavigate = { navController.navigate(it) }) }
            composable(Screen.Style.route)                  { StyleScreen(vm, onBack = back) }
            composable(Screen.JsonConfig.route) { entry ->
                JsonConfigScreen(
                    vm = vm,
                    profileId = entry.arguments?.getString("profileId").orEmpty(),
                    onBack = back,
                )
            }
            composable(SettingsRoutes.LANGUAGE)             { LanguageSettingsScreen(vm, back) }
            composable(SettingsRoutes.CONNECTION)           { ConnectionSettingsScreen(vm, back) }
            composable(SettingsRoutes.DNS)                  { DnsSettingsScreen(vm, back) }
            composable(SettingsRoutes.ROUTING)              { RoutingSettingsScreen(vm, back) }
            composable(SettingsRoutes.GEO)                  { GeoFilesSettingsScreen(vm, back) }
            composable(SettingsRoutes.SUBSCRIPTION)         { SubscriptionSettingsScreen(vm, back) }
            composable(SettingsRoutes.SPLIT)                { SplitTunnelSettingsScreen(vm, back) }
            composable(SettingsRoutes.BACKUP)               { BackupSettingsScreen(vm, back) }
            composable(SettingsRoutes.STARTUP)              { StartupSettingsScreen(vm, back) }
            composable(SettingsRoutes.DIAGNOSTICS)          { DiagnosticsSettingsScreen(vm, back) }
            composable(SettingsRoutes.ABOUT)                { AboutSettingsScreen(vm, back) }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NavPill(
    screen: Screen,
    selected: Boolean,
    animationsEnabled: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        animationSpec = if (animationsEnabled) tween(180) else snap(),
        label = "nav_container_${screen.route}",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = if (animationsEnabled) tween(180) else snap(),
        label = "nav_content_${screen.route}",
    )
    val width by animateDpAsState(
        targetValue = if (selected) 132.dp else 56.dp,
        animationSpec = if (animationsEnabled) tween(220, easing = EaseOutQuart) else snap(),
        label = "nav_width_${screen.route}",
    )

    val label = stringResource(screen.label)
    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = CircleShape,
        modifier = Modifier
            .size(width = width, height = 56.dp)
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                CircleShape,
            ),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(screen.icon, label, Modifier.size(25.dp))
            AnimatedContent(
                targetState = selected,
                transitionSpec = {
                    if (animationsEnabled) {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(80))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }
                },
                label = "nav_label_${screen.route}",
            ) { show ->
                if (show) {
                    Text(
                        label,
                        Modifier.padding(start = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                    )
                }
            }
        }
    }
}

private val EaseOutQuart = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)
