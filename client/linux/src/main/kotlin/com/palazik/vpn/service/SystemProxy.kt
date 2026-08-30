package com.palazik.vpn.service

/**
 * Applies/clears the desktop system proxy (the proxy-mode equivalent of Android's
 * full-device VpnService capture). Best-effort: GNOME via gsettings, KDE via
 * kwriteconfig — anything else just gets a diagnostic hint.
 */
object SystemProxy {

    private var applied = false

    private fun run(vararg cmd: String): Boolean = runCatching {
        ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

    private fun has(cmd: String): Boolean =
        System.getenv("PATH").orEmpty().split(":").any { java.io.File(it, cmd).canExecute() }

    fun enable(log: (String) -> Unit) {
        val host = "127.0.0.1"
        val socks = XrayConfigBuilder.SOCKS_PORT.toString()
        val http = XrayConfigBuilder.HTTP_PORT.toString()
        var ok = false

        if (has("gsettings")) {
            ok = run("gsettings", "set", "org.gnome.system.proxy", "mode", "manual") &&
                run("gsettings", "set", "org.gnome.system.proxy.socks", "host", host) &&
                run("gsettings", "set", "org.gnome.system.proxy.socks", "port", socks) &&
                run("gsettings", "set", "org.gnome.system.proxy.http", "host", host) &&
                run("gsettings", "set", "org.gnome.system.proxy.http", "port", http) &&
                run("gsettings", "set", "org.gnome.system.proxy.https", "host", host) &&
                run("gsettings", "set", "org.gnome.system.proxy.https", "port", http)
            if (ok) log("System proxy set via gsettings (GNOME)")
        }

        val kwrite = listOf("kwriteconfig6", "kwriteconfig5").firstOrNull { has(it) }
        if (kwrite != null) {
            val kok = run(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "1") &&
                run(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "socksProxy", "socks://$host $socks") &&
                run(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy", "http://$host $http") &&
                run(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy", "http://$host $http")
            if (kok) {
                run("dbus-send", "--type=signal", "/KIO/Scheduler",
                    "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:")
                log("System proxy set via $kwrite (KDE)")
            }
            ok = ok || kok
        }

        if (!ok) {
            log("No supported desktop proxy tool found — set SOCKS5 $host:$socks / HTTP $host:$http manually")
        }
        applied = ok
    }

    fun disable(log: (String) -> Unit) {
        if (!applied) return
        if (has("gsettings")) {
            run("gsettings", "set", "org.gnome.system.proxy", "mode", "none")
        }
        val kwrite = listOf("kwriteconfig6", "kwriteconfig5").firstOrNull { has(it) }
        if (kwrite != null) {
            run(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "0")
            run("dbus-send", "--type=signal", "/KIO/Scheduler",
                "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:")
        }
        log("System proxy restored")
        applied = false
    }
}
