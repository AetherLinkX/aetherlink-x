package com.palazik.vpn.service

import com.palazik.vpn.AppDirs
import com.palazik.vpn.data.model.AppSettings
import java.io.File
import java.net.InetAddress

/**
 * Full-device tunnel — the Linux equivalent of Android's VpnService TUN.
 *
 * tun2socks (xjasonlyu) creates a `palazik0` TUN device and feeds every packet into
 * xray's local SOCKS inbound. Routing mirrors the Android interface setup:
 *  - 10.10.14.1/30 on the TUN (v2rayNG's address pair)
 *  - 0.0.0.0/1 + 128.0.0.0/1 via TUN (wins over the default route without touching it)
 *  - explicit bypass route for the proxy server so xray's own traffic skips the TUN
 *  - IPv6 either routed through the TUN or blackholed so it can't leak (Android parity)
 *  - DNS pointed at the configured VPN DNS servers (systemd-resolved or resolv.conf)
 *  - optional kill switch: blackhole catch-all routes that take over if the TUN dies
 *
 * Privilege comes from polkit: the up/down scripts run via `pkexec`.
 */
object TunManager {

    private const val TUN_DEV = "palazik0"

    @Volatile var isUp = false
        private set

    /** Resolve every IPv4 address of the proxy server for the bypass route. */
    fun resolveServerIps(address: String): List<String> = runCatching {
        InetAddress.getAllByName(address)
            .filter { it is java.net.Inet4Address }
            .map { it.hostAddress }
            .distinct()
    }.getOrDefault(emptyList())

    fun up(serverIps: List<String>, settings: AppSettings, tun2socks: File, log: (String) -> Unit) {
        val script = File(AppDirs.runDir, "tun-up.sh")
        script.writeText(buildUpScript(serverIps, settings, tun2socks))
        script.setExecutable(true)

        log("Requesting root (pkexec) for TUN mode…")
        val proc = ProcessBuilder("pkexec", "bash", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        val code = proc.waitFor()
        if (output.isNotBlank()) output.lines().takeLast(6).forEach { log("tun: $it") }
        when (code) {
            0 -> { isUp = true; log("TUN device $TUN_DEV up") }
            126, 127 -> throw Exception("Root authorization cancelled — TUN mode needs pkexec")
            else -> throw Exception("TUN setup failed (exit $code)")
        }
    }

    fun down(log: (String) -> Unit) {
        if (!isUp) return
        val script = File(AppDirs.runDir, "tun-down.sh")
        script.writeText(buildDownScript())
        script.setExecutable(true)
        runCatching {
            val proc = ProcessBuilder("pkexec", "bash", script.absolutePath)
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() == 0) log("TUN device removed") else log("TUN teardown reported errors")
        }.onFailure { log("TUN teardown failed: ${it.message}") }
        isUp = false
    }

    private fun buildUpScript(serverIps: List<String>, settings: AppSettings, tun2socks: File): String {
        val dns = settings.dnsServers.filter { it.isNotBlank() }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        val run = AppDirs.runDir.absolutePath
        return """
            #!/bin/bash
            set -e
            TUN=$TUN_DEV
            RUN="$run"

            # Remember the current default route for the server bypass
            read -r _ _ GW _ DEV _ <<< "${'$'}(ip route show default | head -1)"
            if [ -z "${'$'}GW" ] || [ -z "${'$'}DEV" ]; then
              echo "No default route found"; exit 1
            fi
            echo "${'$'}GW ${'$'}DEV" > "${'$'}RUN/default-route"

            # Start tun2socks (creates the TUN device)
            nohup "${tun2socks.absolutePath}" \
              -device "tun://${'$'}TUN" \
              -proxy "socks5://127.0.0.1:${XrayConfigBuilder.SOCKS_PORT}" \
              -loglevel warn \
              > "${'$'}RUN/tun2socks.log" 2>&1 &
            echo ${'$'}! > "${'$'}RUN/tun2socks.pid"

            # Wait for the device to appear
            for i in ${'$'}(seq 1 50); do
              ip link show "${'$'}TUN" > /dev/null 2>&1 && break
              sleep 0.1
            done
            ip link show "${'$'}TUN" > /dev/null 2>&1 || { echo "tun2socks did not create ${'$'}TUN"; exit 1; }

            # v2rayNG address pair
            ip addr replace 10.10.14.1/30 dev "${'$'}TUN"
            ip link set "${'$'}TUN" up mtu 1500

            # Proxy server bypass — xray's own packets must use the real uplink
            ${serverIps.joinToString("\n            ") { "ip route replace $it via \"${'$'}GW\" dev \"${'$'}DEV\"" }}

            # Capture everything else (more specific than the default route)
            ip route replace 0.0.0.0/1 dev "${'$'}TUN"
            ip route replace 128.0.0.0/1 dev "${'$'}TUN"

            # IPv6: carry it or blackhole it — never leak it (Android parity)
            ${if (settings.enableIpv6) """ip -6 addr replace fd66:6ca7:14e7::1/126 dev "${'$'}TUN" || true
            ip -6 route replace ::/1 dev "${'$'}TUN" || true
            ip -6 route replace 8000::/1 dev "${'$'}TUN" || true""" else """ip -6 route replace blackhole ::/1 || true
            ip -6 route replace blackhole 8000::/1 || true"""}

            ${if (settings.lockdownMode) """# Kill switch: if the TUN vanishes these catch-alls drop traffic
            ip route replace blackhole 0.0.0.0/1 metric 50
            ip route replace blackhole 128.0.0.0/1 metric 50
            touch "${'$'}RUN/lockdown"""" else """rm -f "${'$'}RUN/lockdown""""}

            # DNS
            if command -v resolvectl > /dev/null 2>&1 && resolvectl status > /dev/null 2>&1; then
              resolvectl dns "${'$'}TUN" ${dns.joinToString(" ")}
              resolvectl domain "${'$'}TUN" "~."
              resolvectl default-route "${'$'}TUN" true 2>/dev/null || true
              echo resolvectl > "${'$'}RUN/dns-mode"
            else
              cp /etc/resolv.conf "${'$'}RUN/resolv.conf.bak"
              printf '%s\n' ${dns.joinToString(" ") { "\"nameserver $it\"" }} > /etc/resolv.conf
              echo resolvconf > "${'$'}RUN/dns-mode"
            fi

            echo OK
        """.trimIndent()
    }

    private fun buildDownScript(): String {
        val run = AppDirs.runDir.absolutePath
        return """
            #!/bin/bash
            TUN=$TUN_DEV
            RUN="$run"

            [ -f "${'$'}RUN/tun2socks.pid" ] && kill "${'$'}(cat "${'$'}RUN/tun2socks.pid")" 2>/dev/null
            rm -f "${'$'}RUN/tun2socks.pid"

            # Kill-switch + IPv6 blackholes
            ip route del blackhole 0.0.0.0/1 metric 50 2>/dev/null
            ip route del blackhole 128.0.0.0/1 metric 50 2>/dev/null
            ip -6 route del blackhole ::/1 2>/dev/null
            ip -6 route del blackhole 8000::/1 2>/dev/null
            rm -f "${'$'}RUN/lockdown"

            # Routes through the TUN disappear with the device
            ip link del "${'$'}TUN" 2>/dev/null

            # Restore DNS
            if [ "${'$'}(cat "${'$'}RUN/dns-mode" 2>/dev/null)" = "resolvconf" ] && [ -f "${'$'}RUN/resolv.conf.bak" ]; then
              cp "${'$'}RUN/resolv.conf.bak" /etc/resolv.conf
              rm -f "${'$'}RUN/resolv.conf.bak"
            fi
            rm -f "${'$'}RUN/dns-mode" "${'$'}RUN/default-route"

            echo OK
            exit 0
        """.trimIndent()
    }
}
