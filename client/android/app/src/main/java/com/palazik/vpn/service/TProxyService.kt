package com.palazik.vpn.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Reliable Android TUN -> SOCKS bridge based on hev-socks5-tunnel.
 *
 * Xray owns the protocol and transport stack; hev only translates IP packets from
 * Android's VpnService descriptor to the private SOCKS5 inbound. The JNI library is
 * built from a pinned upstream revision with this exact package/class name.
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor,
    private val socksPort: Int,
    private val enableIpv6: Boolean,
) {
    companion object {
        private const val TAG = "AetherLinkX-Hev"

        @JvmStatic
        private external fun TProxyStartService(configPath: String, fd: Int): Boolean

        @JvmStatic
        private external fun TProxyStopService(): Boolean

        @JvmStatic
        private external fun TProxyIsRunning(): Boolean

        @JvmStatic
        private external fun TProxyGetStats(): LongArray?

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }

    private var configFile: File? = null

    fun start(): Boolean {
        val file = File(context.filesDir, "aetherlink-hev.yaml")
        file.writeText(buildConfig())
        configFile = file
        val started = TProxyStartService(file.absolutePath, vpnInterface.fd)
        if (!started) {
            Log.e(TAG, "Native tunnel rejected the VPN descriptor")
            return false
        }
        // The native entry point starts its worker asynchronously. A false result is
        // fatal; isRunning can briefly lag behind start and is therefore diagnostic.
        Log.i(TAG, "TUN-to-SOCKS bridge started (running=${TProxyIsRunning()})")
        return true
    }

    fun stop() {
        runCatching { TProxyStopService() }
            .onFailure { Log.w(TAG, "Failed to stop native tunnel", it) }
        configFile?.delete()
        configFile = null
    }

    fun isRunning(): Boolean = runCatching { TProxyIsRunning() }.getOrDefault(false)

    fun stats(): LongArray = runCatching { TProxyGetStats() ?: longArrayOf() }
        .getOrDefault(longArrayOf())

    private fun buildConfig(): String = buildString {
        appendLine("tunnel:")
        appendLine("  mtu: 1500")
        appendLine("  ipv4: 10.10.14.1")
        if (enableIpv6) appendLine("  ipv6: 'fd66:6ca7:14e7::1'")
        appendLine("  multi-queue: false")
        appendLine("  icmp: 'reply'")
        appendLine("socks5:")
        appendLine("  address: 127.0.0.1")
        appendLine("  port: $socksPort")
        appendLine("  udp: 'udp'")
        appendLine("  pipeline: true")
        appendLine("misc:")
        appendLine("  log-level: warn")
        appendLine("  connect-timeout: 10000")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        appendLine("  task-stack-size: 24576")
        appendLine("  tcp-buffer-size: 8192")
    }
}
