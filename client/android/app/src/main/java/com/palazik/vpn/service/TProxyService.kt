package com.palazik.vpn.service

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.palazik.vpn.data.network.LocalProxyEndpoint
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

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
    private var logFile: File? = null

    fun start(): Boolean {
        // A rapid reconnect can arrive while the previous native worker is still
        // unwinding.  The JNI bridge is process-global and rejects a second worker,
        // which used to surface as the vague "Не удалось запустить туннель" error.
        // Always drain a stale worker before handing it a new VPN descriptor.
        if (isRunning()) {
            Log.w(TAG, "Stale native tunnel found before start; stopping it first")
            runCatching { TProxyStopService() }
            if (!awaitStopped()) {
                Log.e(TAG, "Stale native tunnel did not stop in time")
                return false
            }
        }
        val file = File(context.filesDir, "aetherlink-hev.yaml")
        val nativeLog = File(context.cacheDir, "aetherlink-hev.log")
        nativeLog.delete()
        logFile = nativeLog
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

    /**
     * The JNI start call only confirms that the native worker thread was created.
     * Wait for the worker to finish parsing its config before declaring the Android
     * data plane ready.  This also turns an early native exit into a deterministic
     * startup error instead of a connected-looking VPN with no traffic.
     */
    fun awaitRunning(timeoutMs: Long = 3_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var stableSince = 0L
        do {
            val now = System.nanoTime()
            if (isRunning()) {
                if (stableSince == 0L) stableSince = now
                // JNI marks the worker as running before all native initialization has
                // settled.  Require a short stable interval so an immediate config or
                // descriptor failure cannot briefly masquerade as a healthy bridge.
                if (now - stableSince >= 250_000_000L) return true
            } else {
                stableSince = 0L
            }
            Thread.sleep(25L)
        } while (System.nanoTime() < deadline)
        Log.e(TAG, "Native tunnel did not enter running state: ${logTail()}")
        return false
    }

    /** Confirm that Xray's private SOCKS listener is accepting RFC 1928 handshakes. */
    fun awaitSocksReady(timeoutMs: Long = 5_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var lastError: Throwable?
        do {
            try {
                Socket().use { socket ->
                    socket.soTimeout = 750
                    socket.connect(InetSocketAddress(LocalProxyEndpoint.ipv4Loopback, socksPort), 750)
                    socket.getOutputStream().apply {
                        write(byteArrayOf(0x05, 0x01, 0x00))
                        flush()
                    }
                    val reply = ByteArray(2)
                    var offset = 0
                    while (offset < reply.size) {
                        val read = socket.getInputStream().read(reply, offset, reply.size - offset)
                        if (read < 0) error("SOCKS listener closed during greeting")
                        offset += read
                    }
                    if (reply.contentEquals(byteArrayOf(0x05, 0x00))) return true
                    error("unexpected SOCKS greeting ${reply.joinToString("") { "%02x".format(it) }}")
                }
            } catch (error: Throwable) {
                lastError = error
                Thread.sleep(50L)
            }
        } while (System.nanoTime() < deadline)
        Log.e(TAG, "SOCKS listener was not ready", lastError)
        return false
    }

    fun stop() {
        runCatching { TProxyStopService() }
            .onFailure { Log.w(TAG, "Failed to stop native tunnel", it) }
        if (!awaitStopped()) {
            Log.w(TAG, "Native tunnel did not report a clean stop before timeout")
        }
        configFile?.delete()
        configFile = null
    }

    private fun awaitStopped(timeoutMs: Long = 3_000L): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        do {
            if (!isRunning()) return true
            try {
                Thread.sleep(25L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return !isRunning()
            }
        } while (System.nanoTime() < deadline)
        return !isRunning()
    }

    fun isRunning(): Boolean = runCatching { TProxyIsRunning() }.getOrDefault(false)

    fun stats(): LongArray = runCatching { TProxyGetStats() ?: longArrayOf() }
        .getOrDefault(longArrayOf())

    fun logTail(maxLines: Int = 12): String = runCatching {
        logFile?.takeIf(File::isFile)?.readLines()?.takeLast(maxLines)?.joinToString(" | ")
    }.getOrNull().orEmpty()

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
        // Xray implements standard RFC 1928 negotiation.  Do not send CONNECT before
        // the method-selection reply. The optional HEV pipeline extension is not part
        // of RFC 1928 and provides no benefit for this loopback-only connection.
        appendLine("  pipeline: false")
        appendLine("misc:")
        appendLine("  log-level: warn")
        appendLine("  log-file: '${logFile?.absolutePath ?: "stderr"}'")
        appendLine("  connect-timeout: 10000")
        appendLine("  tcp-read-write-timeout: 300000")
        appendLine("  udp-read-write-timeout: 60000")
        // Upstream HEV and v2rayNG use substantially larger worker stacks/buffers.
        // The previous small stack/buffer pair was below the upstream production
        // defaults used for many concurrent browser and game sessions.
        appendLine("  task-stack-size: 86016")
        appendLine("  tcp-buffer-size: 262144")
        appendLine("  udp-recv-buffer-size: 524288")
        appendLine("  udp-copy-buffer-nums: 10")
    }
}
