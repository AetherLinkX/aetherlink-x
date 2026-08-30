package com.palazik.vpn.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.palazik.vpn.data.model.DesignSystem
import com.palazik.vpn.ui.screen.AppNavHost
import com.palazik.vpn.ui.screen.OnboardingScreen
import com.palazik.vpn.ui.theme.DarkModePreference
import com.palazik.vpn.ui.theme.palazikVPNTheme
import com.palazik.vpn.ui.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.palazik.vpn.ui.locale.LocaleHelper.wrap(newBase))
    }

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.connect()
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind system bars — Compose handles insets
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotificationPermissionIfNeeded()
        handleIntent(intent)

        setContent {
            val ui by vm.ui.collectAsState()

            palazikVPNTheme(
                appTheme           = ui.appTheme,
                darkModePreference = ui.darkMode,
                useMiuix           = ui.designSystem == DesignSystem.MIUIX,
            ) {
                val isDark = when (ui.darkMode) {
                    DarkModePreference.ALWAYS_DARK  -> true
                    DarkModePreference.ALWAYS_LIGHT -> false
                    DarkModePreference.SYSTEM       -> isSystemInDarkTheme()
                }

                // Adapt system bar icon colors whenever theme/dark mode changes
                SystemBarColors(isDark = isDark)

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color    = Color.Transparent,
                ) {
                    val onboardingPrefs = remember {
                        getSharedPreferences("palazik_theme", MODE_PRIVATE)
                    }
                    var showOnboarding by remember {
                        mutableStateOf(!onboardingPrefs.getBoolean("onboarding_done", false))
                    }
                    if (showOnboarding) {
                        OnboardingScreen(onFinish = {
                            onboardingPrefs.edit().putBoolean("onboarding_done", true).apply()
                            showOnboarding = false
                        })
                    } else {
                        AppNavHost(vm = vm, permLauncher = vpnPermLauncher)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        when (data.scheme?.lowercase()) {
            // palazikvpn://import?config=<link>  → import the wrapped link
            // palazikvpn://<base64>#name          → import the share link itself
            "palazikvpn", "alxclient" ->
                vm.importProfileFromLink(data.getQueryParameter("config") ?: data.toString())
            // Other proxy schemes opened from a browser / file manager
            "aetherlinkx", "vmess", "vless", "ss", "trojan", "hysteria2", "wireguard", "socks5", "tuic", "anytls", "xhttp", "httpproxy" ->
                vm.importProfileFromLink(data.toString())
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

/**
 * Transparent status + nav bars with icon color adapting to isDark.
 * Light mode → dark icons (visible on white/light backgrounds).
 * Dark mode  → light icons (visible on dark backgrounds).
 */
@Composable
private fun SystemBarColors(isDark: Boolean) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(isDark) {
            val window   = (view.context as? ComponentActivity)?.window ?: return@DisposableEffect onDispose {}
            val ctrl     = WindowInsetsControllerCompat(window, view)

            window.statusBarColor         = Color.Transparent.toArgb()
            window.navigationBarColor     = Color.Transparent.toArgb()

            // isAppearanceLightStatusBars = true → dark icons (for light backgrounds)
            ctrl.isAppearanceLightStatusBars     = !isDark
            ctrl.isAppearanceLightNavigationBars = !isDark

            onDispose {}
        }
    }
}
