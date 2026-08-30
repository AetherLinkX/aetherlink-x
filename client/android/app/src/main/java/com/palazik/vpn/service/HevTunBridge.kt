package com.palazik.vpn.service

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Owns the native HEV tun2socks bridge used between Android's TUN descriptor and
 * Xray's loopback SOCKS inbound. The native library is built from the pinned,
 * MIT-licensed heiher/hev-socks5-tunnel source in CI.
 */
class HevTunBridge(private val context: Context) {

    fun start(vpnInterface: ParcelFileDescriptor, enableIpv6: Boolean, mtu: Int = 1500) {
        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: 10.10.14.1")
            if (enableIpv6) appendLine("  ipv6: 'fd66:6ca7:14e7::1'")
            appendLine("socks5:")
            appendLine("  port: 10808")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            appendLine("misc:")
            appendLine("  task-stack-size: 20480")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml")
        configFile.writeText(config)

        check(TProxyStartService(configFile.absolutePath, vpnInterface.fd)) {
            "HEV tun2socks refused to start"
        }
        Thread.sleep(100)
        check(TProxyIsRunning()) { "HEV tun2socks stopped during startup" }
    }

    fun stop() {
        if (TProxyIsRunning()) check(TProxyStopService()) { "HEV tun2socks failed to stop" }
    }

    fun isRunning(): Boolean = TProxyIsRunning()

    fun stats(): LongArray = TProxyGetStats() ?: longArrayOf(0L, 0L, 0L, 0L)

    private companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}
