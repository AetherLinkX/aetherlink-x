package com.palazik.vpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.Settings
import android.system.OsConstants
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.palazik.vpn.R
import com.palazik.vpn.data.SecurePreferences
import com.palazik.vpn.data.network.LocalProxyEndpoint
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.SplitTunnelMode
import com.palazik.vpn.data.model.VpnProfile
import com.palazik.vpn.palazikVPNApp
import com.palazik.vpn.ui.MainActivity
import go.Seq
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.ProcessFinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

class palazikVpnService : VpnService() {

    companion object {
        const val ACTION_START    = "com.palazik.vpn.START"
        const val ACTION_STOP     = "com.palazik.vpn.STOP"
        const val ACTION_SWITCH   = "com.palazik.vpn.SWITCH"
        const val EXTRA_PROFILE   = "profile_id"
        const val NOTIFICATION_ID = 1001
        private const val TAG     = "AetherLinkX"

        // initCoreEnv must only be called once per process lifetime (like v2rayNG)
        private val coreEnvInitialized = AtomicBoolean(false)

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
    }

    enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null
    private var tunBridge: TProxyService? = null
    private var localSocksPort: Int = 0
    private var sessionBytesIn: Long = 0L
    private var sessionBytesOut: Long = 0L
    private var lastBridgeStats = longArrayOf()
    private var lastBridgeDiagnosticAt = 0L
    private var lastAetherLinkXDiagnostics = ""
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    private var egressVerificationJob: Job? = null
    private val networkLock = Any()
    private val underlyingNetworks = linkedSetOf<Network>()

    // v2rayNG: registerDefaultNetworkCallback returns our VPN interface, so we use
    // requestNetwork with a specific request to get the real underlying network,
    // then call setUnderlyingNetworks so xray's outbound sockets bypass the TUN.
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }

    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateUnderlyingNetwork(network, available = true)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateUnderlyingNetwork(network, available = true)
            }
            override fun onLost(network: Network) {
                updateUnderlyingNetwork(network, available = false)
            }
        }
    }

    private fun updateUnderlyingNetwork(network: Network, available: Boolean) {
        val snapshot = synchronized(networkLock) {
            if (available) underlyingNetworks.add(network) else underlyingNetworks.remove(network)
            underlyingNetworks.toTypedArray()
        }
        // Keep a newly available LTE/Wi-Fi network when Android reports the old one
        // lost a moment later. Clearing everything here caused intermittent routing
        // back into our own TUN during network handoff.
        setUnderlyingNetworks(snapshot.takeIf { it.isNotEmpty() })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // v2rayNG sets permitAll thread policy in onCreate to avoid NetworkOnMainThreadException
        // during core init which may do brief I/O
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn(intent.getStringExtra(EXTRA_PROFILE))
            ACTION_STOP  -> stopVpn()
            ACTION_SWITCH -> switchVpn(intent.getStringExtra(EXTRA_PROFILE))
            null -> {
                addDiagnostic("Sticky restart ignored: missing start action")
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onRevoke() { stopVpn() }

    override fun onDestroy() {
        // Synchronous teardown here — the process is going away, so we cannot rely on a
        // coroutine launched on `scope` (which we cancel below) to finish the cleanup.
        statsJob?.cancel()
        statsJob = null
        egressVerificationJob?.cancel()
        egressVerificationJob = null
        unregisterNetworkCallbackSafely()
        val s = _connectionState.value
        if (s != ServiceState.STOPPED && s != ServiceState.ERROR) {
            teardownCore()
            _connectionState.value = ServiceState.STOPPED
            _connectedSince.value = 0L
            _bytesIn.value = 0L
            _bytesOut.value = 0L
        }
        scope.cancel()
        super.onDestroy()
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private fun startVpn(profileId: String?) {
        if (_connectionState.value == ServiceState.STARTING || _connectionState.value == ServiceState.RUNNING) {
            Log.d(TAG, "VPN already starting/running")
            addDiagnostic("Start ignored: VPN already starting/running")
            return
        }
        val profile = if (profileId != null) {
            loadProfileById(profileId) ?: activeProfile?.takeIf { it.id == profileId }
        } else {
            activeProfile ?: loadActiveProfile()
        }
            ?: run {
            Log.e(TAG, "activeProfile is null")
            _lastError.value = "Активный профиль не выбран"
            addDiagnostic("Start failed: no active profile")
            _connectionState.value = ServiceState.ERROR
            stopSelf()
            return
        }
        activeProfile = profile

        _connectionState.value = ServiceState.STARTING
        _lastError.value = null
        addDiagnostic("Starting ${profile.name}")
        startForeground(NOTIFICATION_ID, buildNotification("Подключение…"))

        scope.launch {
            try {
                prepareGeodata()
                initializeLibv2ray()

                val settings = loadAppSettings()
                // Resolve ALX before establish(): once the TUN is active, resolving
                // the transport server through the tunnel can recursively invoke the
                // same outbound. Keep the subscription hostname as REALITY SNI while
                // giving Xray an immutable IP endpoint for this VPN session.
                val runtimeProfile = AetherLinkXEndpointBootstrap.resolve(
                    profile = profile,
                    enableIpv6 = settings.enableIpv6,
                )
                if (runtimeProfile.address != profile.address) {
                    addDiagnostic("ALX endpoint pinned for this session: ${runtimeProfile.address}")
                }
                // Android permits only one active VpnService, but another app can keep a
                // loopback proxy alive for a moment after the system revokes its VPN. A
                // fresh private port avoids collisions without trying to kill other apps.
                val socksPort = LocalProxyEndpoint.allocate()
                localSocksPort = socksPort
                val config = XrayConfigBuilder.build(
                    profile = runtimeProfile,
                    settings = settings,
                    localSocksPort = socksPort,
                    includeHttpInbound = false,
                    includeNativeTun = false,
                )
                Log.d(TAG, "Xray config built for ${profile.name}")

                // Register network callback BEFORE establish() so setUnderlyingNetworks
                // is set before the TUN interface captures all traffic
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
                    } catch (e: Exception) {
                        Log.w(TAG, "requestNetwork failed: ${e.message}")
                    }
                }

                val iface = buildVpnInterface(settings)
                vpnInterface = iface
                Log.d(TAG, "TUN established, fd=${iface.fd}")

                val controller = Libv2ray.newCoreController(V2RayCallback())
                controller.registerProcessFinder(object : ProcessFinder {
                    override fun findProcessByConnection(
                        network: String, src: String, srcPort: Long,
                        dst: String, dstPort: Long,
                    ): Long {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1L
                        val protocol = when (network.lowercase()) {
                            "tcp" -> OsConstants.IPPROTO_TCP
                            "udp" -> OsConstants.IPPROTO_UDP
                            else -> return -1L
                        }
                        if (src.isBlank() || dst.isBlank() || dstPort == 0L) return -1L
                        return runCatching {
                            connectivity.getConnectionOwnerUid(
                                protocol,
                                InetSocketAddress(src, srcPort.toInt()),
                                InetSocketAddress(dst, dstPort.toInt()),
                            ).toLong()
                        }.getOrDefault(-1L)
                    }
                })
                coreController = controller

                // Keep the protocol core behind a regular SOCKS inbound and let the
                // dedicated hev bridge own Android's TUN descriptor. Passing the fd to
                // Xray's built-in TUN works on some devices but can create a routing loop
                // where core probes pass while device traffic never reaches the outbound.
                controller.startLoop(config, 0)
                check(controller.isRunning) { "Xray core stopped during startup" }

                val bridge = runCatching {
                    TProxyService(
                        context = applicationContext,
                        vpnInterface = iface,
                        socksPort = socksPort,
                        enableIpv6 = settings.enableIpv6,
                    ).also {
                        // startLoop() starts Xray synchronously, nevertheless verify the
                        // actual listener that HEV will use.  A process-level running flag
                        // cannot prove that a specific inbound is reachable.
                        check(it.awaitSocksReady()) { "Xray SOCKS listener is not ready" }
                        check(it.start()) { "hev tunnel failed to start" }
                        check(it.awaitRunning()) { "hev worker exited during startup: ${it.logTail()}" }
                    }
                }.getOrElse { cause ->
                    throw IllegalStateException(
                        "Не удалось запустить системный TUN-мост: " +
                            (cause.message ?: cause.javaClass.simpleName),
                        cause,
                    )
                }
                tunBridge = bridge
                addDiagnostic("Android data plane: hev-tun → SOCKS → Xray")
                LocalProxyEndpoint.publish(socksPort)

                _connectionState.value = ServiceState.RUNNING
                _connectedSince.value = System.currentTimeMillis()
                addDiagnostic("Connected: ${profile.name}")
                updateNotification("Подключено — ${profile.name}")
                startStatsPolling()
                startEgressVerification(controller, settings, profile.name)

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                _lastError.value = userFacingError(e)
                addDiagnostic("Start failed: ${e.message ?: e.javaClass.simpleName}")
                withContext(Dispatchers.Main) { failVpn() }
            }
        }
    }

    // ── Geodata / init ────────────────────────────────────────────────────────

    // v2rayNG: getExternalFilesDir("assets") ?: getDir("assets", 0)
    private fun userAssetPath(): String {
        return (applicationContext.getExternalFilesDir("assets")
            ?: applicationContext.getDir("assets", 0)).absolutePath
    }

    // v2rayNG: ANDROID_ID.toByteArray().copyOf(32) → Base64(NO_PADDING | URL_SAFE)
    // BUG FIX: Settings.Secure.ANDROID_ID is the *key name* ("android_id"), not the value.
    // Must read it via Settings.Secure.getString(resolver, ANDROID_ID) to get the real
    // per-device id — otherwise every install derives the same XUDP base key.
    private fun getDeviceIdForXUDPBaseKey(): String {
        val androidId = (
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                ?: "AetherLink X"
            ).toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(
            androidId.copyOf(32),
            Base64.NO_PADDING or Base64.URL_SAFE
        )
    }

    private fun prepareGeodata() {
        val assets  = applicationContext.assets
        val destDir = File(userAssetPath()).also { it.mkdirs() }

        listOf("geoip.dat", "geosite.dat").forEach { fileName ->
            val dest = File(destDir, fileName)
            if (!dest.exists() || dest.length() == 0L) {
                try {
                    assets.open(fileName).use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    Log.d(TAG, "Copied $fileName → ${dest.absolutePath} (${dest.length()} bytes)")
                } catch (e: Exception) {
                    throw RuntimeException("Missing asset: $fileName", e)
                }
            }
        }
    }

    // v2rayNG CoreNativeManager: Seq.setContext() first, then initCoreEnv(), only once via AtomicBoolean
    private fun initializeLibv2ray() {
        if (coreEnvInitialized.compareAndSet(false, true)) {
            try {
                Seq.setContext(applicationContext)
                val assetPath = userAssetPath()
                val deviceId  = getDeviceIdForXUDPBaseKey()
                Libv2ray.initCoreEnv(assetPath, deviceId)
                Log.d(TAG, "Libv2ray.initCoreEnv($assetPath)")
            } catch (e: Exception) {
                coreEnvInitialized.set(false)
                throw e
            }
        } else {
            Log.d(TAG, "Libv2ray already initialized, skipping")
        }
    }

    // ── TUN interface ─────────────────────────────────────────────────────────

    // v2rayNG uses /30 mask with point-to-point pair (e.g. 10.10.14.1/30),
    // not /24. This matches xray-core's TUN expectations.
    private fun buildVpnInterface(settings: AppSettings): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("AetherLink X")
            .setMtu(1500)
            .addAddress("10.10.14.1", 30)   // v2rayNG default: OPTION_1
            .addRoute("0.0.0.0", 0)

        if (settings.enableIpv6) {
            runCatching {
                builder.addAddress("fd66:6ca7:14e7::1", 126)
                builder.addRoute("::", 0)
            }.onFailure { Log.w(TAG, "IPv6 TUN setup failed: ${it.message}") }
        }

        settings.dnsServers.forEach { dns ->
            runCatching { builder.addDnsServer(dns) }
                .onFailure { Log.w(TAG, "Invalid DNS server ignored: $dns") }
        }

        applyAppFilter(builder, settings)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Kill switch: drop packets while the VPN handler isn't ready instead of letting
        // them fall through to the underlying network. For a full system-level kill switch
        // the user must also enable "Always-on VPN" + "Block connections without VPN".
        if (settings.lockdownMode) {
            runCatching { builder.setBlocking(true) }
                .onFailure { Log.w(TAG, "setBlocking failed: ${it.message}") }
        }

        return builder
            .establish()
            ?: throw IllegalStateException("Не выдано разрешение на создание VPN")
    }

    /**
     * Apply split tunnelling. Android only lets us call one of addAllowedApplication /
     * addDisallowedApplication per builder, never both, so the two modes are mutually
     * exclusive. Our own package is always kept off the tunnel to avoid a loop.
     */
    private fun applyAppFilter(builder: Builder, settings: AppSettings) {
        val packages = settings.bypassPackages.filter { it != packageName }

        if (settings.splitTunnelMode == SplitTunnelMode.ONLY && packages.isNotEmpty()) {
            // Whitelist: route only the chosen apps. We are not in the list, so our own
            // traffic stays direct — no need (and not allowed) to also disallow ourselves.
            packages.forEach { pkg ->
                runCatching { builder.addAllowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "Allowed app ignored: $pkg") }
            }
        } else {
            // Bypass (default): the chosen apps plus ourselves skip the tunnel.
            builder.addDisallowedApplication(packageName)
            packages.forEach { pkg ->
                runCatching { builder.addDisallowedApplication(pkg) }
                    .onFailure { Log.w(TAG, "Bypass app ignored: $pkg") }
            }
        }
    }

    private fun loadAppSettings(): AppSettings {
        val prefs = SecurePreferences.get(applicationContext)
        return com.palazik.vpn.data.model.AppSettingsCodec.fromJson(prefs.getString("app_settings", null))
    }

    private fun loadActiveProfile(): VpnProfile? {
        val prefs = SecurePreferences.get(applicationContext)
        val links = runCatching { JSONArray(prefs.getString("profiles_links", "[]")) }.getOrNull() ?: return null
        val meta = runCatching { JSONArray(prefs.getString("profiles_meta", "[]")) }.getOrNull() ?: return null
        for (i in 0 until meta.length()) {
            val item = meta.optJSONObject(i) ?: continue
            if (item.optBoolean("isActive", false)) {
                loadProfileById(item.optString("id"), links)?.let { return it }
            }
        }
        return null
    }

    private fun loadProfileById(id: String): VpnProfile? {
        val prefs = SecurePreferences.get(applicationContext)
        val links = runCatching { JSONArray(prefs.getString("profiles_links", "[]")) }.getOrNull() ?: return null
        return loadProfileById(id, links)
    }

    private fun loadProfileById(id: String, links: JSONArray): VpnProfile? {
        for (i in 0 until links.length()) {
            val profile = com.palazik.vpn.data.codec.ProfileCodec.decode(links.optString(i))
            if (profile?.id == id) return profile
        }
        return null
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    private fun stopVpn() {
        val s = _connectionState.value
        // Guard STOPPING too — stop may be requested again while async teardown runs
        if (s == ServiceState.STOPPED || s == ServiceState.STOPPING) return
        _connectionState.value = ServiceState.STOPPING
        addDiagnostic("Stopping VPN")
        statsJob?.cancel()
        statsJob = null
        egressVerificationJob?.cancel()
        egressVerificationJob = null
        unregisterNetworkCallbackSafely()

        // BUG FIX: teardownCore() does a blocking Thread.sleep(100) + native stopLoop.
        // stopVpn() is invoked from onStartCommand (main thread) and the xray shutdown
        // callback, so run the blocking part off the main thread to avoid jank/ANR.
        scope.launch {
            teardownCore()
            _connectionState.value = ServiceState.STOPPED
            _lastError.value = null
            _connectedSince.value = 0L
            _bytesIn.value  = 0L
            _bytesOut.value = 0L
            addDiagnostic("Stopped")
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** Blocking: stop packet intake, then the native core, and finally close the TUN. */
    private fun teardownCore() {
        LocalProxyEndpoint.clear(localSocksPort)
        localSocksPort = 0
        try { tunBridge?.stop() } catch (e: Throwable) { Log.w(TAG, "hev stop: ${e.message}") }
        tunBridge = null
        try { coreController?.stopLoop() } catch (e: Exception) { Log.w(TAG, "stopLoop: ${e.message}") }
        coreController = null

        // Small delay to allow async core stop before closing TUN (v2rayNG: Thread.sleep(100))
        try { Thread.sleep(100) } catch (_: InterruptedException) {}

        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "iface close: ${e.message}") }
        vpnInterface = null
    }

    private fun unregisterNetworkCallbackSafely() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(defaultNetworkCallback) } catch (_: Exception) {}
        }
        synchronized(networkLock) { underlyingNetworks.clear() }
        setUnderlyingNetworks(null)
    }

    private fun failVpn() {
        if (_connectionState.value == ServiceState.STOPPED) {
            _connectionState.value = ServiceState.ERROR
            return
        }
        statsJob?.cancel()
        statsJob = null
        egressVerificationJob?.cancel()
        egressVerificationJob = null
        LocalProxyEndpoint.clear(localSocksPort)
        localSocksPort = 0
        sessionBytesIn = 0L
        sessionBytesOut = 0L

        try { tunBridge?.stop() } catch (e: Throwable) { Log.w(TAG, "hev stop: ${e.message}") }
        tunBridge = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { connectivity.unregisterNetworkCallback(defaultNetworkCallback) } catch (_: Exception) {}
        }

        try { coreController?.stopLoop() } catch (e: Exception) { Log.w(TAG, "stopLoop: ${e.message}") }
        coreController = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (e: Exception) { Log.w(TAG, "iface close: ${e.message}") }
        vpnInterface = null
        _bytesIn.value = 0L
        _bytesOut.value = 0L
        _connectedSince.value = 0L
        _connectionState.value = ServiceState.ERROR
        addDiagnostic("Service entered error state")
        stopSelf()
    }

    // ── CoreCallbackHandler ───────────────────────────────────────────────────

    private inner class V2RayCallback : CoreCallbackHandler {
        override fun onEmitStatus(level: Long, msg: String): Long {
            Log.d(TAG, "xray[$level]: $msg")
            addDiagnostic("xray[$level]: $msg")
            return 0L
        }
        override fun shutdown(): Long {
            Log.d(TAG, "xray: shutdown")
            addDiagnostic("xray requested shutdown")
            scope.launch(Dispatchers.Main) { stopVpn() }
            return 0L
        }
        override fun startup(): Long = 0L
    }

    // ── Stats polling ─────────────────────────────────────────────────────────

    private fun startStatsPolling() {
        statsJob?.cancel()
        sessionBytesIn = 0L
        sessionBytesOut = 0L
        lastBridgeStats = longArrayOf()
        lastBridgeDiagnosticAt = 0L
        lastAetherLinkXDiagnostics = ""
        statsJob = scope.launch {
            var transientFailures = 0
            while (isActive) {
                delay(1000)
                try {
                    val controller = coreController ?: break
                    check(controller.isRunning) { "Xray core stopped unexpectedly" }
                    check(tunBridge?.isRunning() == true) { "Android TUN bridge stopped unexpectedly" }
                    controller.queryAllOutboundTrafficStats()
                        .split(';')
                        .filter(String::isNotBlank)
                        .forEach { row ->
                            val parts = row.split(',')
                            if (parts.size != 3 || parts[0] != "proxy") return@forEach
                            val value = parts[2].toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                            when (parts[1]) {
                                "uplink" -> sessionBytesOut += value
                                "downlink" -> sessionBytesIn += value
                            }
                        }
                    _bytesOut.value = sessionBytesOut
                    _bytesIn.value = sessionBytesIn

                    // Native HEV counters show whether Android packets actually reach
                    // the TUN bridge.  Keep a low-frequency snapshot in the exportable
                    // diagnostic log so a report identifies the broken layer precisely.
                    val bridgeStats = tunBridge?.stats() ?: longArrayOf()
                    if (bridgeStats.size >= 4) {
                        val now = System.currentTimeMillis()
                        val changed = !bridgeStats.contentEquals(lastBridgeStats)
                        if (changed && now - lastBridgeDiagnosticAt >= 5_000L) {
                            addDiagnostic(
                                "HEV packets tx=${bridgeStats[0]}/${bridgeStats[1]}B " +
                                    "rx=${bridgeStats[2]}/${bridgeStats[3]}B; " +
                                    "Xray proxy up=$sessionBytesOut down=$sessionBytesIn",
                            )
                            lastBridgeStats = bridgeStats.copyOf()
                            lastBridgeDiagnosticAt = now
                        }
                    }
                    captureAetherLinkXDiagnostics()
                    transientFailures = 0
                } catch (e: Exception) {
                    transientFailures++
                    addDiagnostic("Tunnel health check failed ($transientFailures/3)")
                    if (transientFailures >= 3 || coreController?.isRunning != true) {
                        _lastError.value = "Туннель неожиданно остановился. Повторите подключение"
                        withContext(Dispatchers.Main) { failVpn() }
                        break
                    }
                }
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Stop action — lets the user disconnect straight from the notification shade.
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, palazikVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, palazikVPNApp.CHANNEL_VPN)
            .setContentTitle("AetherLink X")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setOngoing(true)
            .addAction(0, "Отключить", stopPi)
        builder.setContentIntent(pi)
        return builder.build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun addDiagnostic(message: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        _diagnostics.value = (_diagnostics.value + "$stamp  $message").takeLast(80)
    }

    /** Add only changed, credential-free ALX protocol-stage counters. */
    private fun captureAetherLinkXDiagnostics(force: Boolean = false) {
        if (activeProfile?.protocol != Protocol.AETHERLINK_X) return
        val snapshot = runCatching { Libv2ray.aetherLinkXDiagnostics() }.getOrNull().orEmpty()
        if (snapshot.isBlank() || (!force && snapshot == lastAetherLinkXDiagnostics)) return
        lastAetherLinkXDiagnostics = snapshot
        addDiagnostic("ALX stages: $snapshot")
    }

    /**
     * Replace the outbound profile while keeping ownership of Android's VpnService.
     * Android allows only one VPN app at a time; rebuilding inside the same service
     * avoids another permission dialog and prevents the old and new cores from
     * competing for a loopback listener.
     */
    private fun switchVpn(profileId: String?) {
        val profile = profileId?.let(::loadProfileById)
            ?: activeProfile?.takeIf { it.id == profileId }
            ?: run {
                _lastError.value = "Выбранный профиль не найден"
                return
            }
        if (activeProfile?.id == profile.id && _connectionState.value == ServiceState.RUNNING) return
        if (_connectionState.value == ServiceState.STARTING || _connectionState.value == ServiceState.STOPPING) {
            _lastError.value = "Дождитесь завершения текущего переключения"
            return
        }
        if (_connectionState.value !in listOf(ServiceState.RUNNING, ServiceState.ERROR)) {
            activeProfile = profile
            startVpn(profile.id)
            return
        }

        // A profile switch is a real disconnect/reconnect cycle.  Publishing STARTING
        // before the old TUN/core had stopped made the UI look connected to the new
        // location while packets could still be handled by the previous one.
        _connectionState.value = ServiceState.STOPPING
        _lastError.value = null
        addDiagnostic("Stopping current VPN before switching to ${profile.name}")
        updateNotification("Отключение перед переключением…")
        statsJob?.cancel()
        statsJob = null
        egressVerificationJob?.cancel()
        egressVerificationJob = null
        unregisterNetworkCallbackSafely()
        scope.launch {
            teardownCore()
            _connectedSince.value = 0L
            _bytesIn.value = 0L
            _bytesOut.value = 0L
            _connectionState.value = ServiceState.STOPPED
            activeProfile = profile
            addDiagnostic("Old VPN stopped; starting ${profile.name}")
            startVpn(profile.id)
        }
    }

    /**
     * Validate the selected outbound in the background.
     *
     * `CoreController.measureDelay` opens its own probe connection. It is useful
     * diagnostics, but it is not the Android VPN data plane and must never be a
     * condition for keeping an already-running TUN alive. In particular a REALITY +
     * X-Wing handshake can exceed a short synthetic-probe timeout on a cold/slow
     * device while normal application traffic succeeds moments later.
     */
    private fun startEgressVerification(
        controller: CoreController,
        settings: AppSettings,
        profileName: String,
    ) {
        egressVerificationJob?.cancel()
        egressVerificationJob = scope.launch {
            // Let Android publish the VPN network and the underlying-network callback
            // settle before the first cold cryptographic handshake.
            delay(1_200L)
            val socksPort = localSocksPort
            val dataPlaneResult = runCatching {
                withTimeout(30_000L) { TunnelDataPlaneProbe.run(socksPort) }
            }
            if (!isActive || coreController !== controller || !controller.isRunning) return@launch
            dataPlaneResult.onSuccess { probe ->
                addDiagnostic(
                    "Client core verified via live SOCKS: HTTP ${probe.tcpStatus}, " +
                        "payload=${probe.tcpBytes}B, UDP/DNS answers=${probe.dnsAnswers}",
                )
                updateNotification("Подключено — $profileName")
            }.onFailure { error ->
                addDiagnostic(
                    "Client core/SOCKS self-test failed: " +
                        (error.message ?: error.javaClass.simpleName),
                )
                captureAetherLinkXDiagnostics(force = true)
                updateNotification("Подключено — $profileName (проверка сети ожидается)")
            }

            // Preserve the Xray-native timing probe as a second independent signal. It
            // must not replace the SOCKS test above because it bypasses HEV's endpoint.
            runCatching { verifyCoreEgress(controller, settings) }
                .onSuccess { delayMs -> addDiagnostic("Xray native egress verified ($delayMs ms)") }
                .onFailure { error ->
                    addDiagnostic("Xray native probe deferred: ${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    /** Probe URLs sequentially so a cold phone does not perform three X-Wing handshakes at once. */
    private suspend fun verifyCoreEgress(controller: CoreController, settings: AppSettings): Long {
        val urls = listOf(
            settings.pingTestUrl,
            "https://cp.cloudflare.com/generate_204",
            "https://www.gstatic.com/generate_204",
        ).filter(String::isNotBlank).distinct()

        repeat(2) { attempt ->
            for (url in urls) {
                currentCoroutineContext().ensureActive()
                val measured = withTimeoutOrNull(12_000L) {
                    runCatching { controller.measureDelay(url) }.getOrNull()
                }
                if (measured != null && measured >= 0L) return measured
            }
            if (attempt == 0) delay(1_500L)
        }
        throw IllegalStateException("проверочные URL пока не ответили через выбранный outbound")
    }

    private fun userFacingError(error: Throwable): String {
        val raw = error.message.orEmpty()
        return when {
            raw.contains("address already in use", ignoreCase = true) ->
                "Локальный порт занят другим процессом. Повторите подключение"
            raw.contains("permission", ignoreCase = true) ->
                "Android не выдал разрешение VPN"
            raw.contains("Missing asset", ignoreCase = true) ->
                "В приложении отсутствуют служебные гео-файлы"
            raw.contains("core stopped", ignoreCase = true) ->
                "Ядро Xray остановилось во время запуска туннеля"
            raw.contains("SOCKS listener", ignoreCase = true) ->
                "Ядро запущено, но локальный SOCKS-вход не открылся"
            raw.contains("hev worker", ignoreCase = true) ||
                raw.contains("hev tunnel", ignoreCase = true) ->
                "Системный TUN-мост Android не запустился"
            else -> "Не удалось запустить туннель. Проверьте профиль и сеть"
        }
    }
}
