package com.palazik.vpn.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subscriptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.palazik.vpn.ui.i18n.LocalStrings
import com.palazik.vpn.ui.i18n.Strings
import com.palazik.vpn.ui.viewmodel.MainViewModel

sealed class Screen(
    val route: String,
    val label: (Strings) -> String,
    val icon: ImageVector,
) {
    object Home          : Screen("home",     { it.navHome },     Icons.Rounded.Home)
    object Profiles      : Screen("profiles", { it.navProfiles }, Icons.AutoMirrored.Rounded.List)
    object Subscriptions : Screen("subs",     { it.navSubs },     Icons.Rounded.Subscriptions)
    object Settings      : Screen("settings", { it.navSettings }, Icons.Rounded.Settings)

    // Sub-screen — not a tab, no icon needed for the nav bar
    object Style : Screen("style", { it.navSettings }, Icons.Rounded.Settings)
}

/**
 * Minimal back-stack navigation — the desktop stand-in for androidx.navigation,
 * with the same routes, transitions and bottom-bar behaviour the Android app has.
 */
private class NavState {
    val backStack = mutableStateListOf("home")
    val current: String get() = backStack.last()
    var lastOpWasPop = false

    fun navigate(route: String) {
        lastOpWasPop = false
        // Tab switches replace the stack (popUpTo startDestination + launchSingleTop)
        if (route in TAB_ROUTES) {
            backStack.clear()
            backStack.add(route)
        } else if (backStack.last() != route) {
            backStack.add(route)
        }
    }

    fun popBackStack() {
        lastOpWasPop = true
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    companion object {
        val TAB_ROUTES = setOf("home", "profiles", "subs", "settings")
    }
}

@Composable
fun AppNavHost(vm: MainViewModel) {
    val nav = remember { NavState() }
    val ui by vm.ui.collectAsState()
    val strings = LocalStrings.current
    val tabs = remember { listOf(Screen.Home, Screen.Profiles, Screen.Subscriptions, Screen.Settings) }
    val snackState = remember { SnackbarHostState() }

    LaunchedEffect(ui.snackMessage) {
        ui.snackMessage?.let { message ->
            val result = snackState.showSnackbar(
                message = message,
                actionLabel = ui.snackActionLabel,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.undoSnackAction()
            vm.clearSnack()
        }
    }

    val currentRoute = nav.current
    // Hide the nav on settings sub-screens (Style + settings/*)
    val showNav = currentRoute != Screen.Style.route && !currentRoute.startsWith("settings/")

    BoxWithConstraints {
        // Desktop-style layout (like Hiddify): a left navigation rail when the window
        // is wide, the floating pill bar at the bottom when narrow.
        val wide = maxWidth >= 760.dp

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackState) },
            bottomBar = {
                if (showNav && !wide) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            shadowElevation = 8.dp,
                            shape = CircleShape,
                        ) {
                            Row(
                                Modifier.padding(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                tabs.forEach { screen ->
                                    NavPill(
                                        screen = screen,
                                        label = screen.label(strings),
                                        selected = currentRoute == screen.route,
                                        onClick = { nav.navigate(screen.route) },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            val back: () -> Unit = { nav.popBackStack() }
            Row(Modifier.padding(innerPadding).fillMaxSize()) {
                if (showNav && wide) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
                    ) {
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource("icon.png"),
                            contentDescription = "palazikVPN",
                            modifier = Modifier.size(44.dp),
                        )
                        Spacer(Modifier.height(20.dp))
                        tabs.forEach { screen ->
                            NavigationRailItem(
                                selected = currentRoute == screen.route,
                                onClick = { nav.navigate(screen.route) },
                                icon = { Icon(screen.icon, screen.label(strings), Modifier.size(26.dp)) },
                                label = {
                                    Text(
                                        screen.label(strings),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                },
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
                AnimatedContent(
                    targetState = currentRoute,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    transitionSpec = {
                        if (nav.lastOpWasPop) {
                            (fadeIn(tween(180, easing = EaseOutQuart)) +
                                slideInHorizontally(tween(180, easing = EaseOutQuart)) { -it / 14 }) togetherWith
                                (fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { it / 14 })
                        } else {
                            (fadeIn(tween(180, easing = EaseOutQuart)) +
                                slideInHorizontally(tween(180, easing = EaseOutQuart)) { it / 14 }) togetherWith
                                (fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { -it / 14 })
                        }
                    },
                    label = "nav_host",
                ) { route ->
                    // Keep content at a readable width on big windows instead of
                    // stretching phone-designed screens edge to edge.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 760.dp).fillMaxHeight()) {
                            when (route) {
                                Screen.Home.route          -> HomeScreen(vm)
                                Screen.Profiles.route      -> ProfilesScreen(vm)
                                Screen.Subscriptions.route -> SubscriptionsScreen(vm)
                                Screen.Settings.route      -> SettingsScreen(vm, onNavigate = { nav.navigate(it) })
                                Screen.Style.route               -> StyleScreen(vm, onBack = back)
                                SettingsRoutes.LANGUAGE          -> LanguageSettingsScreen(vm, back)
                                SettingsRoutes.CONNECTION        -> ConnectionSettingsScreen(vm, back)
                                SettingsRoutes.DNS               -> DnsSettingsScreen(vm, back)
                                SettingsRoutes.ROUTING           -> RoutingSettingsScreen(vm, back)
                                SettingsRoutes.GEO               -> GeoFilesSettingsScreen(vm, back)
                                SettingsRoutes.SUBSCRIPTION      -> SubscriptionSettingsScreen(vm, back)
                                SettingsRoutes.BACKUP            -> BackupSettingsScreen(vm, back)
                                SettingsRoutes.STARTUP           -> StartupSettingsScreen(vm, back)
                                SettingsRoutes.DIAGNOSTICS       -> DiagnosticsSettingsScreen(vm, back)
                                SettingsRoutes.ABOUT             -> AboutSettingsScreen(vm, back)
                                else                             -> HomeScreen(vm)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NavPill(
    screen: Screen,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        animationSpec = tween(180),
        label = "nav_container_${screen.route}",
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(180),
        label = "nav_content_${screen.route}",
    )
    val width by animateDpAsState(
        targetValue = if (selected) 132.dp else 56.dp,
        animationSpec = tween(220, easing = EaseOutQuart),
        label = "nav_width_${screen.route}",
    )

    Surface(
        onClick = onClick,
        color = container,
        contentColor = content,
        shape = CircleShape,
        modifier = Modifier.size(width = width, height = 56.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(screen.icon, label, Modifier.size(24.dp))
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(80)) },
                label = "nav_label_${screen.route}",
            ) { show ->
                if (show) {
                    Text(
                        label,
                        Modifier.padding(start = 8.dp),
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
