package com.palazik.vpn

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** XDG-style application directories. */
object AppDirs {

    const val APP_VERSION = "0.1.0-alpha"

    private fun secureDir(file: File): File = file.apply {
        mkdirs()
        runCatching {
            Files.setPosixFilePermissions(toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    val configDir: File by lazy {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        secureDir(File(xdg ?: "${System.getProperty("user.home")}/.config", "AetherLinkXClient"))
    }

    val dataDir: File by lazy {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        secureDir(File(xdg ?: "${System.getProperty("user.home")}/.local/share", "AetherLinkXClient"))
    }

    /** geoip.dat / geosite.dat live here (xray reads them via XRAY_LOCATION_ASSET). */
    val assetsDir: File by lazy { secureDir(File(dataDir, "assets")) }

    /** Downloaded/bundled binaries (xray, tun2socks) end up here when not packaged. */
    val binDir: File by lazy { secureDir(File(dataDir, "bin")) }

    /** Runtime state: generated config, pid files, resolv.conf backup. */
    val runDir: File by lazy { secureDir(File(dataDir, "run")) }

    /**
     * Resources bundled into the packaged distribution by CI
     * (xray, tun2socks, geoip.dat, geosite.dat) — null when running from gradle.
     */
    val bundledResourcesDir: File?
        get() = System.getProperty("compose.application.resources.dir")
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
}
