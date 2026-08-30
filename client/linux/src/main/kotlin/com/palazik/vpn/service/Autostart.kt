package com.palazik.vpn.service

import java.io.File

/**
 * Linux equivalent of the Android BootReceiver: an XDG autostart entry that
 * launches the app (minimized, auto-connecting) when the user logs in.
 */
object Autostart {

    private val entry = File(
        "${System.getProperty("user.home")}/.config/autostart/palazikvpn.desktop"
    )

    /** Best guess at how this app was launched, for the Exec= line. */
    private fun launcherCommand(): String {
        // Packaged app-image: <dist>/bin/palazikVPN next to lib/ and the bundled runtime
        System.getProperty("compose.application.resources.dir")?.let { res ->
            val bin = File(res).parentFile?.parentFile?.resolve("bin/palazikVPN")
            if (bin != null && bin.canExecute()) return bin.absolutePath
        }
        // Installed binary on PATH
        System.getenv("PATH").orEmpty().split(":").forEach { dir ->
            val f = File(dir, "palazikvpn")
            if (f.canExecute()) return f.absolutePath
        }
        return "palazikvpn"
    }

    fun sync(enabled: Boolean) {
        runCatching {
            if (enabled) {
                entry.parentFile?.mkdirs()
                entry.writeText(
                    """
                    [Desktop Entry]
                    Type=Application
                    Name=palazikVPN
                    Comment=Auto-connect VPN on login
                    Exec=${launcherCommand()} --autoconnect
                    Icon=palazikVPN
                    Terminal=false
                    X-GNOME-Autostart-enabled=true
                    """.trimIndent() + "\n"
                )
            } else {
                entry.delete()
            }
        }
    }
}
