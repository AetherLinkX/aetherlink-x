package com.palazik.vpn

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.palazik.vpn.data.model.DesignSystem
import com.palazik.vpn.data.model.VpnState
import com.palazik.vpn.service.VpnController
import com.palazik.vpn.ui.i18n.LocalStrings
import com.palazik.vpn.ui.i18n.stringsFor
import com.palazik.vpn.ui.screen.AppNavHost
import com.palazik.vpn.ui.screen.OnboardingScreen
import com.palazik.vpn.ui.theme.palazikVPNTheme
import com.palazik.vpn.ui.viewmodel.MainViewModel

/** Schemes the app can import straight from the command line (like Android deep links). */
private val IMPORT_SCHEMES = listOf(
    "palazikvpn", "vmess", "vless", "ss", "trojan", "hysteria2",
    "wireguard", "socks5", "tuic", "anytls", "xhttp", "httpproxy",
)

fun main(args: Array<String>) {
    val autoconnect = "--autoconnect" in args
    val importLink = args.firstOrNull { arg ->
        IMPORT_SCHEMES.any { arg.startsWith("$it://") }
    }

    // Never leave a TUN device / system proxy behind, whatever way we exit.
    Runtime.getRuntime().addShutdownHook(Thread { VpnController.shutdown() })

    // Renderer override for setups where the OpenGL context fails (e.g. hybrid
    // NVIDIA through XWayland): PALAZIKVPN_RENDER=software|opengl. Without it,
    // Skiko tries OpenGL and falls back to software rendering automatically.
    System.getenv("PALAZIKVPN_RENDER")?.let { render ->
        when (render.lowercase()) {
            "software" -> System.setProperty("skiko.renderApi", "SOFTWARE")
            "opengl"   -> System.setProperty("skiko.renderApi", "OPENGL")
        }
    }

    // Skiko vsyncs to 60 fps on many Linux setups regardless of the monitor, and
    // XWayland often reports 60/unknown for high-Hz panels — so don't trust the
    // detected rate as a cap. Disable vsync and let frames pace to the detected
    // rate, a generous default, or PALAZIKVPN_FPS.
    val maxHz = runCatching {
        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .maxOfOrNull { it.displayMode.refreshRate } ?: 0
    }.getOrDefault(0)
    val fps = System.getenv("PALAZIKVPN_FPS")?.toIntOrNull()
        ?: if (maxHz > 60) maxHz else 165
    System.setProperty("skiko.vsync.enabled", "false")
    System.setProperty("skiko.fps.limit", fps.toString())

    application {
        val vm = remember { MainViewModel() }
        val ui by vm.ui.collectAsState()
        val vpnState = ui.vpnState
        // Wide enough for the desktop navigation rail layout by default.
        val windowState = rememberWindowState(width = 1040.dp, height = 860.dp)
        var windowVisible by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            importLink?.let { vm.importProfileFromLink(it) }
            if (autoconnect) {
                windowVisible = false
                vm.connect()
            }
        }

        val strings = stringsFor(ui.language)

        // Tray — the desktop stand-in for the persistent notification, QS tile and widget.
        // AWT has no tray on native Wayland (Hyprland etc.), so guard it: without a tray,
        // closing the window must quit instead of stranding an unreachable process.
        val traySupported = remember { androidx.compose.ui.window.isTraySupported }
        if (traySupported) {
            Tray(
                icon = painterResource("icon.png"),
                tooltip = "AetherLink X — ${vpnState.name}",
                onAction = { windowVisible = true },
                menu = {
                    Item(
                        if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING)
                            strings.disconnect else strings.connect,
                        onClick = { vm.toggleVpn() },
                    )
                    Item(if (windowVisible) "Hide" else "Show", onClick = { windowVisible = !windowVisible })
                    Separator()
                    Item("Quit", onClick = {
                        VpnController.shutdown()
                        exitApplication()
                    })
                },
            )
        }

        Window(
            onCloseRequest = {
                if (traySupported) {
                    windowVisible = false   // close to tray, like a foreground service
                } else {
                    VpnController.shutdown()
                    exitApplication()
                }
            },
            state = windowState,
            visible = windowVisible,
            title = "AetherLink X",
            icon = painterResource("icon.png"),
        ) {
            // Desktop runs at 1.0 density by default, which reads small next to a phone
            // UI — scale everything up a notch.
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalStrings provides strings,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                    baseDensity.density * 1.12f,
                    baseDensity.fontScale,
                ),
            ) {
                palazikVPNTheme(
                    appTheme           = ui.appTheme,
                    darkModePreference = ui.darkMode,
                    useMiuix           = ui.designSystem == DesignSystem.MIUIX,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color    = Color.Transparent,
                    ) {
                        val onboardingPrefs = remember { com.palazik.vpn.data.repository.ProfileRepository.themePrefs }
                        var showOnboarding by remember {
                            mutableStateOf(!onboardingPrefs.getBoolean("onboarding_done", false))
                        }
                        if (showOnboarding) {
                            OnboardingScreen(onFinish = {
                                onboardingPrefs.edit().putBoolean("onboarding_done", true).apply()
                                showOnboarding = false
                            })
                        } else {
                            AppNavHost(vm = vm)
                        }
                    }
                }
            }
        }
    }
}
