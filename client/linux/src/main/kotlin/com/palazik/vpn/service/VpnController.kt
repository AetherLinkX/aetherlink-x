package com.palazik.vpn.service

import com.palazik.vpn.AppDirs
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.VpnProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Desktop replacement for palazikVpnService: runs the official xray binary as a
 * child process (SOCKS :10808 / HTTP :10809), optionally captures the whole
 * system through tun2socks (TUN mode), and exposes the same observable state
 * the Android service companion did.
 */
object VpnController {

    enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private val _connectionState = MutableStateFlow(ServiceState.STOPPED)
    val connectionState: StateFlow<ServiceState> = _connectionState

    private val _bytesIn  = MutableStateFlow(0L)
    private val _bytesOut = MutableStateFlow(0L)
    private val _connectedSince = MutableStateFlow(0L)
    private val _diagnostics = MutableStateFlow<List<String>>(emptyList())
    private val _lastError = MutableStateFlow<String?>(null)
    val bytesIn:  StateFlow<Long> = _bytesIn
    val bytesOut: StateFlow<Long> = _bytesOut
    val connectedSince: StateFlow<Long> = _connectedSince
    val diagnostics: StateFlow<List<String>> = _diagnostics
    val lastError: StateFlow<String?> = _lastError

    @Volatile var activeProfile: VpnProfile? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var xrayProcess: Process? = null
    private var xrayBinary: File? = null
    private var statsJob: Job? = null
    private var logJob: Job? = null
    private var systemProxyApplied = false

    private val directClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // ── Start ─────────────────────────────────────────────────────────────────

    fun start(profile: VpnProfile, settings: AppSettings) {
        if (_connectionState.value == ServiceState.STARTING || _connectionState.value == ServiceState.RUNNING) {
            addDiagnostic("Start ignored: VPN already starting/running")
            return
        }
        activeProfile = profile
        _connectionState.value = ServiceState.STARTING
        _lastError.value = null
        addDiagnostic("Starting ${profile.name}")

        scope.launch {
            try {
                XrayAssets.ensureGeoFiles(directClient) { addDiagnostic(it) }

                val xray = XrayAssets.findXray()
                    ?: throw Exception("xray binary not found — install the xray package or reinstall palazikVPN")
                xrayBinary = xray

                val config = XrayConfigBuilder.build(profile, settings)
                val configFile = File(AppDirs.runDir, "config.json")
                configFile.writeText(config)

                val proc = ProcessBuilder(xray.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["XRAY_LOCATION_ASSET"] = AppDirs.assetsDir.absolutePath
                    }
                    .start()
                xrayProcess = proc
                pumpLogs(proc)

                // Wait for the SOCKS inbound — the same readiness signal Android gets
                // from startLoop() returning.
                if (!waitForPort(XrayConfigBuilder.SOCKS_PORT, timeoutMs = 8000, proc = proc)) {
                    val alive = proc.isAlive
                    teardownXray()
                    throw Exception(
                        if (alive) "xray did not open the local proxy port"
                        else "xray exited early — check Diagnostics for its log"
                    )
                }
                addDiagnostic("xray core running (SOCKS :${XrayConfigBuilder.SOCKS_PORT}, HTTP :${XrayConfigBuilder.HTTP_PORT})")

                if (settings.tunMode) {
                    val tun2socks = XrayAssets.findTun2socks()
                        ?: throw Exception("tun2socks binary not found — required for TUN mode")
                    val serverIps = TunManager.resolveServerIps(serverHost(profile))
                    if (serverIps.isEmpty()) throw Exception("Could not resolve server address for TUN bypass route")
                    TunManager.up(serverIps, settings, tun2socks) { addDiagnostic(it) }
                } else if (settings.systemProxy) {
                    SystemProxy.enable { addDiagnostic(it) }
                    systemProxyApplied = true
                }

                _connectionState.value = ServiceState.RUNNING
                _connectedSince.value = System.currentTimeMillis()
                addDiagnostic("Connected: ${profile.name}")
                startStatsPolling()

            } catch (e: Exception) {
                _lastError.value = e.message ?: e.javaClass.simpleName
                addDiagnostic("Start failed: ${e.message ?: e.javaClass.simpleName}")
                failVpn()
            }
        }
    }

    private fun serverHost(profile: VpnProfile): String =
        if (profile.protocol == com.palazik.vpn.data.model.Protocol.WIREGUARD) {
            profile.wgEndpoint.substringBeforeLast(":").removePrefix("[").removeSuffix("]")
                .ifBlank { profile.address.substringBefore("/") }
        } else {
            profile.address
        }

    // ── Stop ──────────────────────────────────────────────────────────────────

    fun stop() {
        val s = _connectionState.value
        if (s == ServiceState.STOPPED || s == ServiceState.STOPPING) return
        _connectionState.value = ServiceState.STOPPING
        addDiagnostic("Stopping VPN")
        statsJob?.cancel()
        statsJob = null

        scope.launch {
            if (systemProxyApplied) {
                SystemProxy.disable { addDiagnostic(it) }
                systemProxyApplied = false
            }
            TunManager.down { addDiagnostic(it) }
            teardownXray()
            _connectionState.value = ServiceState.STOPPED
            _lastError.value = null
            _connectedSince.value = 0L
            _bytesIn.value  = 0L
            _bytesOut.value = 0L
            addDiagnostic("Stopped")
        }
    }

    /** Called once on app exit — make sure nothing keeps running or stays misconfigured. */
    fun shutdown() {
        runCatching {
            if (systemProxyApplied) SystemProxy.disable { }
            TunManager.down { }
            teardownXray()
        }
    }

    private fun teardownXray() {
        logJob?.cancel()
        logJob = null
        xrayProcess?.let { proc ->
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        xrayProcess = null
    }

    private fun failVpn() {
        statsJob?.cancel()
        statsJob = null
        if (systemProxyApplied) {
            SystemProxy.disable { addDiagnostic(it) }
            systemProxyApplied = false
        }
        TunManager.down { addDiagnostic(it) }
        teardownXray()
        _bytesIn.value = 0L
        _bytesOut.value = 0L
        _connectedSince.value = 0L
        _connectionState.value = ServiceState.ERROR
        addDiagnostic("Service entered error state")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun pumpLogs(proc: Process) {
        logJob?.cancel()
        logJob = scope.launch {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!isActive) break
                        if (line.isNotBlank()) addDiagnostic("xray: ${line.take(200)}")
                    }
                }
            }
            // Stream closed → process died. If we thought we were connected, fail over.
            if (_connectionState.value == ServiceState.RUNNING) {
                _lastError.value = "xray exited unexpectedly"
                addDiagnostic("xray exited unexpectedly")
                failVpn()
            }
        }
    }

    private fun waitForPort(port: Int, timeoutMs: Long, proc: Process): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) return false
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
            }.isSuccess
            if (ok) return true
            Thread.sleep(150)
        }
        return false
    }

    // ── Stats polling ─────────────────────────────────────────────────────────

    // `xray api statsquery -reset` returns counters accumulated since the previous
    // query — same delta semantics as libv2ray's queryStats on Android.
    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                val xray = xrayBinary ?: break
                runCatching {
                    val proc = ProcessBuilder(
                        xray.absolutePath, "api", "statsquery",
                        "--server=127.0.0.1:${XrayConfigBuilder.API_PORT}",
                        "-pattern", "outbound>>>proxy",
                        "-reset",
                    ).redirectErrorStream(true).start()
                    val out = proc.inputStream.bufferedReader().readText()
                    proc.waitFor(2, TimeUnit.SECONDS)
                    parseStats(out)
                }
            }
        }
    }

    private fun parseStats(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val stats = json.optJSONArray("stat") ?: return
        for (i in 0 until stats.length()) {
            val item = stats.optJSONObject(i) ?: continue
            val name = item.optString("name")
            val value = item.optLong("value", item.optString("value").toLongOrNull() ?: 0L)
            when {
                name.endsWith("downlink") -> _bytesIn.value += value
                name.endsWith("uplink")   -> _bytesOut.value += value
            }
        }
    }

    private fun addDiagnostic(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _diagnostics.value = (_diagnostics.value + "$stamp  $message").takeLast(80)
    }
}
