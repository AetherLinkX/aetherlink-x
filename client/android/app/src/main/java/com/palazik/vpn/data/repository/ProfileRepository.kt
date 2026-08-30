package com.palazik.vpn.data.repository

import android.content.Context
import com.palazik.vpn.data.SecurePreferences
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.AppSettingsCodec
import com.palazik.vpn.data.model.PingMode
import com.palazik.vpn.data.model.ProfileValidator
import com.palazik.vpn.data.model.Subscription
import com.palazik.vpn.data.model.VpnProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class UpdateInfo(val version: String, val url: String)

@Singleton
class ProfileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @Named("direct") private val directClient: OkHttpClient,
    @Named("proxy")  private val proxyClient:  OkHttpClient,
) {
    private val prefs = SecurePreferences.get(context)

    private val _profiles      = MutableStateFlow<List<VpnProfile>>(emptyList())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    private val _subscriptions = MutableStateFlow<List<Subscription>>(emptyList())
    val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()

    private val _pingMode      = MutableStateFlow(PingMode.TCP)
    val pingMode: StateFlow<PingMode> = _pingMode.asStateFlow()

    private val _settings      = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // Serializes concurrent subscription updates to prevent profile duplication
    private val updateMutex = Mutex()

    private companion object {
        const val MAX_CONCURRENT_PINGS = 24
    }

    init { loadFromPrefs() }

    // ── Profiles ──────────────────────────────────────────────────────────────

    fun addProfile(profile: VpnProfile) {
        val shouldActivate = _profiles.value.none { it.isActive }
        _profiles.value = if (shouldActivate) {
            _profiles.value + profile.copy(isActive = true)
        } else {
            _profiles.value + profile.copy(isActive = false)
        }
        saveProfiles()
    }

    fun removeProfile(id: String) {
        _profiles.value = ensureActiveProfile(_profiles.value.filter { it.id != id })
        saveProfiles()
    }

    fun updateProfile(profile: VpnProfile) {
        _profiles.value = _profiles.value.map { if (it.id == profile.id) profile else it }
        saveProfiles()
    }

    fun getActiveProfile(): VpnProfile? = _profiles.value.firstOrNull { it.isActive }

    fun setActiveProfile(id: String) {
        _profiles.value = _profiles.value.map { it.copy(isActive = it.id == id) }
        saveProfiles()
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    fun addSubscription(sub: Subscription) {
        _subscriptions.value = _subscriptions.value + sub
        saveSubscriptions()
    }

    fun removeSubscription(id: String) {
        // Atomically remove both the subscription and all its profiles, then persist both
        val newProfiles = ensureActiveProfile(_profiles.value.filter { it.subscriptionId != id })
        val newSubs     = _subscriptions.value.filter { it.id != id }
        _profiles.value      = newProfiles
        _subscriptions.value = newSubs
        saveProfiles()
        saveSubscriptions()
    }

    /**
     * Update a subscription by fetching its URL and replacing old profiles with new ones.
     *
     * Strategy (mirrors v2rayNG AngConfigManager.updateConfigViaSub):
     *  1. Try via the local SOCKS proxy (127.0.0.1:10808) — works when VPN is running.
     *  2. On any failure, fall back to a direct connection — works when VPN is off.
     *
     * Profile replacement (mirrors v2rayNG parseBatchConfig with append=false):
     *  - Remember the currently active profile for this subscription.
     *  - DELETE all old profiles that belong to this subscription.
     *  - ADD all freshly parsed profiles.
     *  - If the previously-active profile appears in the new list (matched by fingerprint),
     *    restore its active flag so the user's selection is preserved across updates.
     */
    suspend fun updateSubscription(sub: Subscription): Result<Int> = withContext(Dispatchers.IO) {
        updateMutex.withLock {
            runCatching {
                val fetched = fetchSubscriptionBody(sub.url)
                val body = fetched.body
                val usage = parseUserInfo(fetched.userInfo)

                val freshProfiles = ProfileCodec.decodeSubscriptionBody(body)
                    .filter { ProfileValidator.validate(it).isEmpty() }
                    .map { it.copy(subscriptionId = sub.id) }
                if (freshProfiles.isEmpty()) throw Exception("No valid profiles in subscription")

                // v2rayNG: remember which profile was selected before wiping
                val snapshot      = _profiles.value
                val prevActive    = snapshot.firstOrNull { it.subscriptionId == sub.id && it.isActive }
                fun VpnProfile.fingerprint() = listOf(
                    address,
                    port,
                    uuid,
                    protocol.name,
                    transport.name,
                    path,
                    host,
                    security.name,
                    sni,
                ).joinToString("|")
                val prevFingerprint = prevActive?.fingerprint()
                var restoredActive = false

                // All profiles NOT belonging to this subscription are kept untouched
                val retained = snapshot.filter { it.subscriptionId != sub.id }
                val retainedHasActive = retained.any { it.isActive }

                // Map new profiles; restore active flag if fingerprint matches previous active.
                val restoredMerged = freshProfiles.map { p ->
                    if (!restoredActive && prevFingerprint != null && p.fingerprint() == prevFingerprint) {
                        restoredActive = true
                        p.copy(isActive = true)
                    } else {
                        p.copy(isActive = false)
                    }
                }
                val merged = if (!retainedHasActive && restoredMerged.none { it.isActive }) {
                    restoredMerged.mapIndexed { index, p -> p.copy(isActive = index == 0) }
                } else {
                    restoredMerged
                }
                val retainedProfiles = if (merged.any { it.isActive }) {
                    retained.map { it.copy(isActive = false) }
                } else {
                    retained
                }

                // Single atomic write — old sub profiles deleted, new ones added
                _profiles.value = retainedProfiles + merged
                saveProfiles()

                val updated = sub.copy(
                    lastUpdated  = System.currentTimeMillis(),
                    profileCount = merged.size,
                    uploadBytes    = usage?.upload ?: sub.uploadBytes,
                    downloadBytes  = usage?.download ?: sub.downloadBytes,
                    totalBytes     = usage?.total ?: sub.totalBytes,
                    expireEpochSec = usage?.expire ?: sub.expireEpochSec,
                )
                _subscriptions.value = _subscriptions.value.map { if (it.id == sub.id) updated else it }
                saveSubscriptions()

                merged.size
            }
        }
    }

    suspend fun updateAllSubscriptions(): List<Result<Int>> =
        _subscriptions.value.map { updateSubscription(it) }

    // ── Geo files ───────────────────────────────────────────────────────────────

    /**
     * Download user-supplied geoip.dat / geosite.dat into the directory xray reads from,
     * overriding the bundled copies. Returns how many files were refreshed, or fails if a
     * configured URL could not be fetched. Files with a blank URL are left untouched.
     */
    suspend fun updateGeoFiles(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val targets = buildList {
                _settings.value.geoipUrl.trim().takeIf { it.isNotBlank() }?.let { add("geoip.dat" to it) }
                _settings.value.geositeUrl.trim().takeIf { it.isNotBlank() }?.let { add("geosite.dat" to it) }
            }
            if (targets.isEmpty()) throw Exception("No geo file URLs set")

            val dir = (context.getExternalFilesDir("assets") ?: context.getDir("assets", 0))
                .also { it.mkdirs() }

            targets.forEach { (fileName, url) ->
                val req = Request.Builder().url(url).build()
                directClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw Exception("$fileName: HTTP ${resp.code}")
                    val bytes = resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                        ?: throw Exception("$fileName: empty response")
                    File(dir, fileName).writeBytes(bytes)
                }
            }
            targets.size
        }
    }

    /** Register a free Cloudflare WARP account and add it as a WireGuard profile. */
    suspend fun provisionWarp(): Result<Unit> = withContext(Dispatchers.IO) {
        com.palazik.vpn.data.warp.WarpProvisioner.register(directClient).map { addProfile(it) }
    }

    /**
     * Ask GitHub for the latest release and return it if it's newer than [currentVersion],
     * or null if already up to date.
     */
    suspend fun checkForUpdate(currentVersion: String): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/AetherLinkX/aetherlink-x/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            val body = directClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                resp.body?.string()?.takeIf { it.isNotBlank() } ?: throw Exception("Empty response")
            }
            val json = JSONObject(body)
            val tag  = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return@runCatching null
            val url  = json.optString("html_url").ifBlank { "https://github.com/AetherLinkX/aetherlink-x/releases" }
            if (isNewerVersion(tag, currentVersion)) UpdateInfo(tag.trimStart('v', 'V'), url) else null
        }
    }

    /** Compare dotted version strings numerically, ignoring a leading "v"/"V". */
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parts(v: String) = v.trimStart('v', 'V').split('.', '-', '_').mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // ── Ping ──────────────────────────────────────────────────────────────────

    fun setPingMode(mode: PingMode) {
        _pingMode.value = mode
        prefs.edit().putString("ping_mode", mode.name).apply()
    }

    /**
     * Ping a profile using the currently-selected ping mode.
     *
     * TCP  — Direct TCP connect to the server's address:port (v2rayNG SpeedtestManager.tcping).
     *         Works without the VPN running. Measures raw reachability of the server.
     *
     * GET / HEAD — HTTP request to https://cp.cloudflare.com/ routed through the local SOCKS
     *         proxy (127.0.0.1:10808) so it actually travels through the xray/proxy outbound.
     *         v2rayNG routes real-ping tests through its local proxy port so the result
     *         reflects end-to-end latency through the proxy profile.
     *         Requires the VPN / xray service to be running.
     */
    suspend fun pingProfile(profile: VpnProfile): Long = withContext(Dispatchers.IO) {
        val latency = measureLatency(profile)
        updateProfile(profile.copy(latencyMs = latency, lastTested = System.currentTimeMillis()))
        latency
    }

    /**
     * Ping many profiles concurrently and commit all results in a SINGLE atomic write.
     *
     * Batch pings ALWAYS use TCP: HTTP/HEAD modes route through the single running local
     * proxy, so they measure the active tunnel — not each candidate server — and would give
     * every profile the same (wrong) latency. TCP connects to each server:port directly, so
     * it is the only mode that meaningfully compares multiple profiles, and it works without
     * the VPN running.
     *
     * Pinging via [pingProfile] in a loop is slow (3s timeout each, serial), and doing the
     * per-profile writes concurrently would race on _profiles (read-modify-write). Here we
     * measure in parallel, then fold every result into one list update.
     *
     * Concurrency is capped so a large subscription doesn't open hundreds of sockets at once
     * (which can exhaust file descriptors and just thrashes the IO dispatcher anyway).
     */
    suspend fun pingProfiles(profiles: List<VpnProfile>): Unit = withContext(Dispatchers.IO) {
        if (profiles.isEmpty()) return@withContext
        val gate = Semaphore(MAX_CONCURRENT_PINGS)
        val results = profiles
            .map { p -> async { gate.withPermit { p.id to tcpPing(p) } } }
            .awaitAll()
            .toMap()
        val now = System.currentTimeMillis()
        _profiles.value = _profiles.value.map { p ->
            results[p.id]?.let { p.copy(latencyMs = it, lastTested = now) } ?: p
        }
        saveProfiles()
    }

    /** Measure latency for one profile (respecting the selected mode) without persisting. */
    private suspend fun measureLatency(profile: VpnProfile): Long = runCatching {
        when (_pingMode.value) {
            PingMode.TCP       -> tcpPing(profile)
            PingMode.HTTP_GET  -> httpPing(head = false)
            PingMode.HTTP_HEAD -> httpPing(head = true)
        }
    }.getOrElse { -1L }

    /** Direct TCP connect to the server's address:port. Returns -1 on failure. */
    private fun tcpPing(profile: VpnProfile): Long {
        // v2rayNG SpeedtestManager.socketConnectTime: try twice, keep the best
        var best = -1L
        repeat(2) {
            val start = System.currentTimeMillis()
            runCatching {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(profile.address, profile.port), 3000)
                }
            }.onSuccess {
                val t = System.currentTimeMillis() - start
                if (best == -1L || t < best) best = t
            }
        }
        return best
    }

    /**
     * HTTP(S) latency through the running local SOCKS proxy — i.e. the latency of the
     * ACTIVE tunnel end-to-end. Not per-profile; callers must ensure the VPN is running.
     */
    private fun httpPing(head: Boolean): Long {
        val req = Request.Builder().url("https://cp.cloudflare.com/")
            .apply { if (head) head() else get() }
            .build()
        val start = System.currentTimeMillis()
        proxyClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 204) throw Exception("HTTP ${resp.code}")
        }
        return System.currentTimeMillis() - start
    }

    // ── Backup / restore ────────────────────────────────────────────────────────

    /** Export all profiles as alxclient:// links, one per line (for backup/share). */
    fun exportProfilesText(): String =
        _profiles.value.joinToString("\n") { ProfileCodec.encodePalazik(it) }

    /**
     * Import profiles from a newline-separated (or base64) backup body.
     * Returns the number of profiles added. Skips invalid ones.
     */
    fun importProfilesText(body: String): Int {
        val parsed = ProfileCodec.decodeSubscriptionBody(body)
            .filter { ProfileValidator.validate(it).isEmpty() }
        if (parsed.isEmpty()) return 0
        val existingIds = _profiles.value.map { it.id }.toSet()
        var hasActive = _profiles.value.any { it.isActive }
        val toAdd = parsed
            .filter { it.id !in existingIds }
            .map { p ->
                val activate = !hasActive
                if (activate) hasActive = true
                p.copy(isActive = activate)
            }
        if (toAdd.isEmpty()) return 0
        _profiles.value = _profiles.value + toAdd
        saveProfiles()
        return toAdd.size
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveProfiles() {
        val links = JSONArray()
        val meta  = JSONArray()
        _profiles.value.forEach { p ->
            links.put(ProfileCodec.encodePalazik(p))
            meta.put(JSONObject().apply {
                put("id",         p.id)
                put("isActive",   p.isActive)
                put("latency",    p.latencyMs)
                put("lastTested", p.lastTested)
                put("subId",      p.subscriptionId ?: "")
            })
        }
        prefs.edit()
            .putString("profiles_links", links.toString())
            .putString("profiles_meta",  meta.toString())
            .apply()
    }

    private fun ensureActiveProfile(profiles: List<VpnProfile>): List<VpnProfile> {
        if (profiles.isEmpty() || profiles.any { it.isActive }) return profiles
        return profiles.mapIndexed { index, profile -> profile.copy(isActive = index == 0) }
    }

    private fun saveSubscriptions() {
        val arr = JSONArray()
        _subscriptions.value.forEach { sub ->
            arr.put(JSONObject().apply {
                put("id",           sub.id)
                put("name",         sub.name)
                put("url",          sub.url)
                put("lastUpdated",  sub.lastUpdated)
                put("profileCount", sub.profileCount)
                put("uploadBytes",    sub.uploadBytes)
                put("downloadBytes",  sub.downloadBytes)
                put("totalBytes",     sub.totalBytes)
                put("expireEpochSec", sub.expireEpochSec)
            })
        }
        prefs.edit().putString("subscriptions_json", arr.toString()).apply()
    }

    private fun loadFromPrefs() {
        val linksJson = prefs.getString("profiles_links", null)
        val metaJson  = prefs.getString("profiles_meta",  null)

        data class Meta(val isActive: Boolean, val latency: Long, val lastTested: Long, val subId: String?)
        val metaMap = mutableMapOf<String, Meta>()
        if (metaJson != null) runCatching {
            val arr = JSONArray(metaJson)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                metaMap[o.getString("id")] = Meta(
                    isActive   = o.optBoolean("isActive", false),
                    latency    = o.optLong("latency", -1L),
                    lastTested = o.optLong("lastTested", 0L),
                    subId      = o.optString("subId").takeIf { it.isNotEmpty() },
                )
            }
        }

        if (linksJson != null) runCatching {
            val arr    = JSONArray(linksJson)
            val loaded = mutableListOf<VpnProfile>()
            for (i in 0 until arr.length()) {
                ProfileCodec.decode(arr.getString(i))?.let { profile ->
                    val m = metaMap[profile.id]
                    loaded.add(profile.copy(
                        isActive       = m?.isActive ?: false,
                        latencyMs      = m?.latency  ?: -1L,
                        lastTested     = m?.lastTested ?: 0L,
                        subscriptionId = m?.subId    ?: profile.subscriptionId,
                    ))
                }
            }
            _profiles.value = loaded
        }

        val subsJson = prefs.getString("subscriptions_json", null)
        if (subsJson != null) runCatching {
            val arr    = JSONArray(subsJson)
            val loaded = mutableListOf<Subscription>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                loaded.add(Subscription(
                    id           = o.getString("id"),
                    name         = o.getString("name"),
                    url          = o.getString("url"),
                    lastUpdated  = o.optLong("lastUpdated", 0L),
                    profileCount = o.optInt("profileCount", 0),
                    uploadBytes    = o.optLong("uploadBytes", -1L),
                    downloadBytes  = o.optLong("downloadBytes", -1L),
                    totalBytes     = o.optLong("totalBytes", -1L),
                    expireEpochSec = o.optLong("expireEpochSec", -1L),
                ))
            }
            _subscriptions.value = loaded
        }

        val saved = prefs.getString("ping_mode", PingMode.TCP.name)
        _pingMode.value = runCatching { PingMode.valueOf(saved ?: "") }.getOrElse {
            when (saved) {
                "PROXY_GET",  "HTTP_GET"  -> PingMode.HTTP_GET
                "PROXY_HEAD", "HTTP_HEAD" -> PingMode.HTTP_HEAD
                else                      -> PingMode.TCP
            }
        }

        val settingsJson = prefs.getString(AppSettingsCodec.KEY, null)
        if (settingsJson != null) {
            _settings.value = AppSettingsCodec.fromJson(settingsJson)
        }
    }

    fun updateSettings(settings: AppSettings) {
        val normalized = settings.copy(
            dnsServers = settings.dnsServers.map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { AppSettings().dnsServers },
            remoteDns = settings.remoteDns.trim().ifBlank { AppSettings().remoteDns },
            directDns = settings.directDns.trim().ifBlank { AppSettings().directDns },
            bypassPackages = settings.bypassPackages.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            subscriptionUpdateIntervalHours = settings.subscriptionUpdateIntervalHours.coerceAtLeast(2L),
            subscriptionUserAgent = settings.subscriptionUserAgent.trim().ifBlank { AppSettings().subscriptionUserAgent },
            customDirectDomains = settings.customDirectDomains.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            customBlockedDomains = settings.customBlockedDomains.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        )
        _settings.value = normalized
        saveSettings()
    }

    private fun saveSettings() {
        prefs.edit().putString(AppSettingsCodec.KEY, AppSettingsCodec.toJson(_settings.value)).apply()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Fetch the raw subscription body.
     *
     * Mirrors v2rayNG AngConfigManager.updateConfigViaSub:
     *  1. Try via local SOCKS/HTTP proxy (works when xray service is running).
     *  2. On any error, retry direct (works when VPN is off).
     *
     * Returns the raw string (may be base64 or plain links). Throws if both fail.
     */
    private fun fetchSubscriptionBody(url: String): SubscriptionFetch {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", _settings.value.subscriptionUserAgent.ifBlank { "AetherLinkX/0.1" })
            .build()

        // Attempt 1: through proxy (so the fetch itself goes through the active profile)
        val proxyResult = runCatching {
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                val body = resp.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw Exception("Empty body")
                SubscriptionFetch(body, resp.header("Subscription-Userinfo"))
            }
        }
        if (proxyResult.isSuccess) return proxyResult.getOrThrow()

        // Attempt 2: direct (VPN not running, or proxy refused)
        return directClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw Exception("Empty body from direct fetch")
            SubscriptionFetch(body, resp.header("Subscription-Userinfo"))
        }
    }

    private data class SubscriptionFetch(val body: String, val userInfo: String?)

    private data class Usage(val upload: Long, val download: Long, val total: Long, val expire: Long)

    /**
     * Parse the `Subscription-Userinfo` header, e.g.
     *   "upload=455; download=2342; total=10737418240; expire=2218532"
     * Missing keys default to -1 (not reported). Returns null if the header is absent.
     */
    private fun parseUserInfo(raw: String?): Usage? {
        if (raw.isNullOrBlank()) return null
        val map = raw.split(";")
            .mapNotNull { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim().lowercase() to (kv[1].trim().toLongOrNull() ?: return@mapNotNull null)
                else null
            }.toMap()
        if (map.isEmpty()) return null
        return Usage(
            upload   = map["upload"] ?: -1L,
            download = map["download"] ?: -1L,
            total    = map["total"] ?: -1L,
            expire   = map["expire"] ?: -1L,
        )
    }
}
