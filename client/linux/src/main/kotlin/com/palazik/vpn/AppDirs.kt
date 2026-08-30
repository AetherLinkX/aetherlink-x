package com.palazik.vpn

import java.io.File

/** XDG-style application directories. */
object AppDirs {

    const val APP_VERSION = "0.1.0-alpha"

    val configDir: File by lazy {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        File(xdg ?: "${System.getProperty("user.home")}/.config", "AetherLinkXClient").apply { mkdirs() }
    }

    val dataDir: File by lazy {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        File(xdg ?: "${System.getProperty("user.home")}/.local/share", "AetherLinkXClient").apply { mkdirs() }
    }

    /** geoip.dat / geosite.dat live here (xray reads them via XRAY_LOCATION_ASSET). */
    val assetsDir: File by lazy { File(dataDir, "assets").apply { mkdirs() } }

    /** Downloaded/bundled binaries (xray, tun2socks) end up here when not packaged. */
    val binDir: File by lazy { File(dataDir, "bin").apply { mkdirs() } }

    /** Runtime state: generated config, pid files, resolv.conf backup. */
    val runDir: File by lazy { File(dataDir, "run").apply { mkdirs() } }

    /**
     * Resources bundled into the packaged distribution by CI
     * (xray, tun2socks, geoip.dat, geosite.dat) — null when running from gradle.
     */
    val bundledResourcesDir: File?
        get() = System.getProperty("compose.application.resources.dir")
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
}
