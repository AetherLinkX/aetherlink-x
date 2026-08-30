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
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.palazik.vpn.R
import com.palazik.vpn.data.SecurePreferences
import com.palazik.vpn.data.model.AppSettings
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
import java.util.concurrent.atomic.AtomicBoolean

class palazikVpnService : VpnService() {

    companion object {
        const val ACTION_START    = "com.palazik.vpn.START"
        const val ACTION_STOP     = "com.palazik.vpn.STOP"
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null

    // v2rayNG: registerDefaultNetworkCallback returns our VPN interface, so we use
    // requestNetwork with a specific request to get the real underlying network,
    // then call setUnderlyingNetworks so xray's outbound sockets bypass the TUN.
    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
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
            _lastError.value = "No active profile selected"
            addDiagnostic("Start failed: no active profile")
            _connectionState.value = ServiceState.ERROR
            stopSelf()
            return
        }
        activeProfile = profile

        _connectionState.value = ServiceState.STARTING
        _lastError.value = null
        addDiagnostic("Starting ${profile.name}")
        startForeground(NOTIFICATION_ID, buildNotification("Connecting…"))

        scope.launch {
            try {
                prepareGeodata()
                initializeLibv2ray()

                val settings = loadAppSettings()
                val config = XrayConfigBuilder.build(profile, settings)
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
                    ): Long = 0L
                })
                coreController = controller

                // Pass actual TUN fd — xray reads packets from it via the "tun" inbound.
                // v2rayNG: tunFd = vpnInterface?.fd ?: 0
                controller.startLoop(config, iface.fd)

                _connectionState.value = ServiceState.RUNNING
                _connectedSince.value = System.currentTimeMillis()
                addDiagnostic("Connected: ${profile.name}")
                updateNotification("Connected — ${profile.name}")
                startStatsPolling()

            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed: ${e.message}", e)
                _lastError.value = e.message ?: e.javaClass.simpleName
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

        // Without an IPv6 address + ::/0 route, apps' IPv6 traffic bypasses the tunnel on
        // dual-stack networks and leaks the real IP. With IPv6 enabled we carry it; with it
        // disabled we still claim ::/0 so the OS drops it inside the TUN instead.
        runCatching {
            builder.addAddress("fd66:6ca7:14e7::1", 126)
            builder.addRoute("::", 0)
        }.onFailure { Log.w(TAG, "IPv6 TUN setup failed: ${it.message}") }

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
            ?: throw IllegalStateException("establish() returned null — missing VPN permission?")
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
        unregisterNetworkCallbackSafely()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

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
        }
    }

    /** Blocking: stop the native core, wait briefly, then close the TUN. */
    private fun teardownCore() {
        // v2rayNG: stopLoop() before closing the interface, otherwise the core fails to
        // stop and subsequent startLoop calls report "port in use".
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
    }

    private fun failVpn() {
        if (_connectionState.value == ServiceState.STOPPED) {
            _connectionState.value = ServiceState.ERROR
            return
        }
        statsJob?.cancel()
        statsJob = null

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
        statsJob = scope.launch {
            while (isActive) {
                delay(1000)
                try {
                    val ctrl = coreController ?: break
                    // Current AndroidLibXrayLite exposes the aggregate API in its
                    // gomobile surface. The payload is `tag,direction,value;...` and
                    // reading it atomically resets the corresponding core counters.
                    ctrl.queryAllOutboundTrafficStats()
                        .split(';')
                        .forEach { record ->
                            val parts = record.split(',', limit = 3)
                            if (parts.size != 3 || parts[0] != "proxy") return@forEach
                            val value = parts[2].toLongOrNull()?.coerceAtLeast(0L) ?: return@forEach
                            when (parts[1]) {
                                "downlink" -> _bytesIn.value += value
                                "uplink" -> _bytesOut.value += value
                            }
                        }
                } catch (_: Exception) {}
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
            .addAction(0, "Disconnect", stopPi)
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
}
