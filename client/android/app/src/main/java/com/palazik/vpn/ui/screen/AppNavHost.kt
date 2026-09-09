package com.palazik.vpn.ui.screen

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.compose.BackHandler
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var settingsDetailRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState(
        initialPage = selectedTabIndex,
        pageCount = { tabs.size },
    )
    val snackState = remember { SnackbarHostState() }

    // Compose equivalent of IndexedStack + keep-alive: pages are created lazily,
    // retained after the first visit, and switched without reconstructing the full
    // navigation destination tree. scrollToPage is intentionally immediate.
    LaunchedEffect(selectedTabIndex) {
        if (pagerState.currentPage != selectedTabIndex) {
            pagerState.scrollToPage(selectedTabIndex)
        }
    }

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

    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route
    // Settings detail pages are local overlays inside the retained Settings tab.
    val showBottomBar = (currentRoute == null || currentRoute == Screen.Home.route) &&
        settingsDetailRoute == null

    BackHandler(enabled = settingsDetailRoute != null) {
        settingsDetailRoute = null
    }

    Scaffold(
        modifier = Modifier.aetherScreenBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
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
                            tabs.forEachIndexed { index, screen ->
                                val selected = (currentRoute == null || currentRoute == Screen.Home.route) &&
                                    selectedTabIndex == index
                                NavPill(
                                    screen = screen,
                                    selected = selected,
                                    animationsEnabled = shell.animationsEnabled,
                                    onClick = {
                                        if (currentRoute != Screen.Home.route) {
                                            navController.popBackStack(Screen.Home.route, inclusive = false)
                                        }
                                        if (screen != Screen.Settings) settingsDetailRoute = null
                                        selectedTabIndex = index
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
            // Screen-sized fade/slide transitions force both heavy destination trees to
            // render simultaneously. Keeping navigation itself immediate removes the tab
            // hitch; the pill and controls retain their lightweight local animations.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {
            val back: () -> Unit = { navController.popBackStack() }
            composable(Screen.Home.route) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    userScrollEnabled = false,
                    beyondViewportPageCount = tabs.lastIndex,
                    key = { tabs[it].route },
                ) { page ->
                    when (tabs[page]) {
                        Screen.Home -> HomeScreen(vm, permLauncher)
                        Screen.Profiles -> ProfilesScreen(
                            vm,
                            onOpenSubscriptions = { navController.navigate(Screen.Subscriptions.route) },
                            onOpenJson = { profileId -> navController.navigate("json/$profileId") },
                        )
                        Screen.Settings -> SettingsTabStack(
                            vm = vm,
                            detailRoute = settingsDetailRoute,
                            onNavigate = { settingsDetailRoute = it },
                            onBack = { settingsDetailRoute = null },
                        )
                        else -> Unit
                    }
                }
            }
            composable(Screen.Subscriptions.route) { SubscriptionsScreen(vm) }
            composable(Screen.JsonConfig.route) { entry ->
                JsonConfigScreen(
                    vm = vm,
                    profileId = entry.arguments?.getString("profileId").orEmpty(),
                    onBack = back,
                )
            }
        }
    }
}

/**
 * Keeps the settings hub inside the retained tab pager while a detail page is
 * open. The previous NavHost destinations disposed the whole Home destination
 * (including all three tab trees) and rebuilt it on every Back press.
 */
@Composable
private fun SettingsTabStack(
    vm: MainViewModel,
    detailRoute: String?,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SettingsScreen(vm = vm, onNavigate = onNavigate)
        if (detailRoute != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .aetherScreenBackground(),
            ) {
                when (detailRoute) {
                    SettingsRoutes.STYLE        -> StyleScreen(vm, onBack)
                    SettingsRoutes.LANGUAGE     -> LanguageSettingsScreen(vm, onBack)
                    SettingsRoutes.CONNECTION   -> ConnectionSettingsScreen(vm, onBack)
                    SettingsRoutes.DNS          -> DnsSettingsScreen(vm, onBack)
                    SettingsRoutes.ROUTING      -> RoutingSettingsScreen(vm, onBack)
                    SettingsRoutes.GEO          -> GeoFilesSettingsScreen(vm, onBack)
                    SettingsRoutes.SUBSCRIPTION -> SubscriptionSettingsScreen(vm, onBack)
                    SettingsRoutes.SPLIT        -> SplitTunnelSettingsScreen(vm, onBack)
                    SettingsRoutes.BACKUP       -> BackupSettingsScreen(vm, onBack)
                    SettingsRoutes.STARTUP      -> StartupSettingsScreen(vm, onBack)
                    SettingsRoutes.DIAGNOSTICS  -> DiagnosticsSettingsScreen(vm, onBack)
                    SettingsRoutes.ABOUT        -> AboutSettingsScreen(vm, onBack)
                }
            }
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
