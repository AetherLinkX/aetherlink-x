package com.palazik.vpn.ui.viewmodel

import com.palazik.vpn.AppDirs
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.model.*
import com.palazik.vpn.data.repository.ProfileRepository
import com.palazik.vpn.data.repository.SubscriptionAutoUpdater
import com.palazik.vpn.data.repository.UpdateInfo
import com.palazik.vpn.service.Autostart
import com.palazik.vpn.service.VpnController
import com.palazik.vpn.service.XrayConfigBuilder
import com.palazik.vpn.ui.i18n.AppLanguage
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiState(
    val vpnState: VpnState                = VpnState.DISCONNECTED,
    val activeProfile: VpnProfile?        = null,
    val profiles: List<VpnProfile>        = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val appTheme: AppTheme                = AppTheme.CYBER,
    val darkMode: DarkModePreference      = DarkModePreference.SYSTEM,
    val designSystem: DesignSystem        = DesignSystem.MD3,
    val language: AppLanguage             = AppLanguage.ENGLISH,
    val pingMode: PingMode                = PingMode.TCP,
    val settings: AppSettings             = AppSettings(),
    val lastError: String?                = null,
    val snackMessage: String?             = null,
    val snackActionLabel: String?         = null,
    val shareLink: String?                = null,
    val isUpdatingSubscriptions: Boolean  = false,
    val updatingSubscriptionIds: Set<String> = emptySet(),
    val checkingUpdate: Boolean           = false,
    val updateAvailable: UpdateInfo?      = null,
)

private const val KEY_THEME         = "app_theme"
private const val KEY_DARKMODE      = "dark_mode"
private const val KEY_DESIGN_SYSTEM = "design_system"
private const val KEY_LANGUAGE      = "app_language"

class MainViewModel {

    private val repo = ProfileRepository
    private val themePrefs = repo.themePrefs
    private var deletedProfile: VpnProfile? = null

    val viewModelScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // High-frequency values (update ~1×/sec) are exposed as dedicated flows instead of
    // living in the big UiState — so a traffic tick doesn't recompose every screen that
    // reads `ui`. Collect these only where they're shown.
    val bytesIn:        StateFlow<Long>         = VpnController.bytesIn
    val bytesOut:       StateFlow<Long>         = VpnController.bytesOut
    val connectedSince: StateFlow<Long>         = VpnController.connectedSince
    val diagnostics:    StateFlow<List<String>> = VpnController.diagnostics

    init {
        // Restore persisted theme on startup
        val savedTheme  = themePrefs.getString(KEY_THEME,         AppTheme.CYBER.name)
        val savedDark   = themePrefs.getString(KEY_DARKMODE,      DarkModePreference.SYSTEM.name)
        val savedDesign = themePrefs.getString(KEY_DESIGN_SYSTEM, DesignSystem.MD3.name)
        val savedLang   = themePrefs.getString(KEY_LANGUAGE,      AppLanguage.ENGLISH.name)
        _ui.update { it.copy(
            appTheme     = runCatching { AppTheme.valueOf(savedTheme ?: "") }.getOrDefault(AppTheme.CYBER),
            darkMode     = runCatching { DarkModePreference.valueOf(savedDark ?: "") }.getOrDefault(DarkModePreference.SYSTEM),
            designSystem = runCatching { DesignSystem.valueOf(savedDesign ?: "") }.getOrDefault(DesignSystem.MD3),
            language     = AppLanguage.fromName(savedLang),
        ) }

        // Mirror repo flows into UI
        viewModelScope.launch {
            repo.profiles.collect { profiles ->
                _ui.update { it.copy(
                    profiles      = profiles,
                    activeProfile = profiles.firstOrNull { p -> p.isActive },
                ) }
            }
        }
        viewModelScope.launch {
            repo.subscriptions.collect { subs ->
                _ui.update { it.copy(subscriptions = subs) }
            }
        }
        viewModelScope.launch {
            repo.pingMode.collect { mode ->
                _ui.update { it.copy(pingMode = mode) }
            }
        }
        viewModelScope.launch {
            repo.settings.collect { settings ->
                _ui.update { it.copy(settings = settings) }
            }
        }

        // Mirror VPN controller state
        viewModelScope.launch {
            VpnController.connectionState.collect { svcState ->
                _ui.update { it.copy(vpnState = when (svcState) {
                    VpnController.ServiceState.RUNNING  -> VpnState.CONNECTED
                    VpnController.ServiceState.STARTING -> VpnState.CONNECTING
                    VpnController.ServiceState.STOPPING -> VpnState.DISCONNECTING
                    VpnController.ServiceState.STOPPED  -> VpnState.DISCONNECTED
                    VpnController.ServiceState.ERROR    -> VpnState.ERROR
                }) }
            }
        }
        viewModelScope.launch { VpnController.lastError.collect { error -> _ui.update { it.copy(lastError = error) } } }

        SubscriptionAutoUpdater.sync(repo.settings.value)
        Autostart.sync(repo.settings.value.startOnBoot)
    }

    // ── VPN toggle ────────────────────────────────────────────────────────────

    fun connect() {
        val profile = _ui.value.activeProfile ?: run { snack("Select a profile first"); return }
        VpnController.activeProfile = profile
        VpnController.start(profile, _ui.value.settings)
    }

    fun disconnect() {
        VpnController.stop()
    }

    fun toggleVpn() {
        when (_ui.value.vpnState) {
            VpnState.CONNECTED, VpnState.CONNECTING -> disconnect()
            else -> connect()
        }
    }

    // ── Profile management ────────────────────────────────────────────────────

    fun importProfileFromLink(raw: String) {
        val profile = ProfileCodec.decode(raw)
        if (profile != null) {
            val errors = ProfileValidator.validate(profile)
            if (errors.isNotEmpty()) {
                snack(errors.first())
                return
            }
            repo.addProfile(profile)
            snack("Profile \"${profile.name}\" imported")
        } else {
            snack("Could not parse link")
        }
    }

    fun addManualProfile(profile: VpnProfile): Boolean {
        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            snack(errors.first())
            return false
        }
        repo.addProfile(profile)
        snack("Profile \"${profile.name}\" added")
        return true
    }

    /** Update an existing profile (used by the edit dialog). */
    fun updateProfile(profile: VpnProfile): Boolean {
        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            snack(errors.first())
            return false
        }
        repo.updateProfile(profile)
        if (VpnController.activeProfile?.id == profile.id) {
            VpnController.activeProfile = profile
        }
        snack("Profile \"${profile.name}\" updated")
        return true
    }

    fun removeProfile(id: String) {
        val profile = _ui.value.profiles.firstOrNull { it.id == id }
        deletedProfile = profile
        if (profile?.id == VpnController.activeProfile?.id) {
            disconnect()
            VpnController.activeProfile = null
        }
        repo.removeProfile(id)
        snack("Profile deleted", "Undo")
    }

    fun undoSnackAction() {
        deletedProfile?.let {
            repo.addProfile(it)
            snack("Profile restored")
        }
        deletedProfile = null
    }

    fun selectProfile(id: String) {
        if (_ui.value.vpnState != VpnState.DISCONNECTED && _ui.value.vpnState != VpnState.ERROR) {
            snack("Disconnect before switching profiles")
            return
        }
        repo.setActiveProfile(id)
        VpnController.activeProfile = repo.profiles.value.firstOrNull { it.id == id }
    }

    fun duplicateProfile(profile: VpnProfile) {
        val copy = profile.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${profile.name} Copy",
            isActive = false,
            latencyMs = -1L,
            addedAt = System.currentTimeMillis(),
        )
        repo.addProfile(copy)
        snack("Profile duplicated")
    }

    fun generateShareLink() {
        val profile = _ui.value.activeProfile ?: run { snack("No active profile"); return }
        _ui.update { it.copy(shareLink = ProfileCodec.encodePalazik(profile)) }
    }

    fun generateNativeLink(profile: VpnProfile) {
        _ui.update { it.copy(shareLink = ProfileCodec.encodeNative(profile)) }
    }

    fun generateJsonConfig(profile: VpnProfile) {
        _ui.update { it.copy(shareLink = XrayConfigBuilder.build(profile, _ui.value.settings)) }
    }

    fun clearShareLink() = _ui.update { it.copy(shareLink = null) }

    // ── Backup / restore ───────────────────────────────────────────────────────

    /** Serialize every profile as palazikvpn:// links for export to a file. */
    fun exportProfilesText(): String = repo.exportProfilesText()

    /** Import profiles from a backup file body; reports how many were added. */
    fun importProfilesText(body: String) {
        val added = repo.importProfilesText(body)
        syncServiceActiveProfile()
        snack(if (added > 0) "Imported $added profiles" else "No new profiles found")
    }

    // ── Ping ─────────────────────────────────────────────────────────────────

    fun pingProfile(profile: VpnProfile) {
        viewModelScope.launch {
            // HTTP/HEAD ping measures the ACTIVE tunnel end-to-end, so it only makes sense
            // for the currently active profile while connected. TCP works for any profile.
            if (_ui.value.pingMode != PingMode.TCP) {
                if (_ui.value.vpnState != VpnState.CONNECTED) {
                    snack("Connect VPN first for HTTP ping")
                    return@launch
                }
                if (profile.id != _ui.value.activeProfile?.id) {
                    snack("HTTP ping measures the active profile only — use TCP mode to test others")
                    return@launch
                }
            }
            snack("Pinging ${profile.name}…")
            val ms = repo.pingProfile(profile)
            snack(if (ms >= 0) "${profile.name}: ${ms}ms" else "${profile.name}: timeout")
        }
    }

    fun pingAll() {
        viewModelScope.launch {
            // pingProfiles always uses TCP (per-server) — no VPN required, no HTTP gate.
            snack("Pinging ${_ui.value.profiles.size} profiles…")
            repo.pingProfiles(_ui.value.profiles)
            snack("Ping complete")
        }
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    fun addSubscription(name: String, url: String) {
        val sub = Subscription(name = name, url = url)
        repo.addSubscription(sub)
        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            repo.updateSubscription(sub).fold(
                onSuccess = { count -> snack("Loaded $count profiles from \"$name\"") },
                onFailure = {
                    repo.removeSubscription(sub.id)
                    snack("Failed to fetch subscription")
                },
            )
            syncServiceActiveProfile()
            SubscriptionAutoUpdater.sync(_ui.value.settings)
            _ui.update { it.copy(isUpdatingSubscriptions = false) }
        }
    }

    fun removeSubscription(id: String) {
        if (_ui.value.profiles.any { it.subscriptionId == id && it.id == VpnController.activeProfile?.id }) {
            disconnect()
            VpnController.activeProfile = null
        }
        repo.removeSubscription(id)
    }

    fun updateSubscription(sub: Subscription) {
        viewModelScope.launch {
            _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds + sub.id) }
            snack("Updating \"${sub.name}\"…")
            repo.updateSubscription(sub).fold(
                onSuccess = { count ->
                    syncServiceActiveProfile()
                    snack("Updated: $count profiles")
                },
                onFailure = { snack("Update failed") },
            )
            _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds - sub.id) }
        }
    }

    fun chooseBestProfileForSubscription(sub: Subscription) {
        viewModelScope.launch {
            // Must be disconnected to switch the active profile. The comparison itself uses
            // TCP (per-server) so it works while disconnected.
            if (_ui.value.vpnState != VpnState.DISCONNECTED && _ui.value.vpnState != VpnState.ERROR) {
                snack("Disconnect before switching profiles")
                return@launch
            }
            val candidates = _ui.value.profiles.filter { it.subscriptionId == sub.id }
            if (candidates.isEmpty()) {
                snack("No profiles in \"${sub.name}\"")
                return@launch
            }
            _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds + sub.id) }
            try {
                snack("Testing \"${sub.name}\" profiles…")
                // Ping concurrently, then read fresh latencies off the updated list
                repo.pingProfiles(candidates)
                val best = repo.profiles.value
                    .filter { it.subscriptionId == sub.id && it.latencyMs >= 0 }
                    .minByOrNull { it.latencyMs }
                if (best != null) {
                    repo.setActiveProfile(best.id)
                    VpnController.activeProfile = repo.profiles.value.firstOrNull { it.id == best.id }
                    snack("Best profile selected: ${best.latencyMs}ms")
                } else {
                    snack("All profiles timed out")
                }
            } finally {
                _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds - sub.id) }
            }
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            snack("Updating all subscriptions…")
            val results = repo.updateAllSubscriptions()
            syncServiceActiveProfile()
            val failed = results.count { it.isFailure }
            val updated = results.sumOf { it.getOrDefault(0) }
            snack(if (failed == 0) "Updated $updated profiles" else "Updated $updated profiles, $failed failed")
            _ui.update { it.copy(isUpdatingSubscriptions = false) }
        }
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    fun setAppTheme(theme: AppTheme) {
        _ui.update { it.copy(appTheme = theme) }
        themePrefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun setDarkMode(pref: DarkModePreference) {
        _ui.update { it.copy(darkMode = pref) }
        themePrefs.edit().putString(KEY_DARKMODE, pref.name).apply()
    }

    fun setDesignSystem(design: DesignSystem) {
        _ui.update { it.copy(designSystem = design) }
        themePrefs.edit().putString(KEY_DESIGN_SYSTEM, design.name).apply()
    }

    /** Persist and apply the chosen UI language (takes effect immediately on desktop). */
    fun setLanguage(language: AppLanguage) {
        _ui.update { it.copy(language = language) }
        themePrefs.edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    // ── Ping mode ─────────────────────────────────────────────────────────────

    fun setPingMode(mode: PingMode) = repo.setPingMode(mode)

    fun updateAppSettings(settings: AppSettings) {
        repo.updateSettings(settings)
        SubscriptionAutoUpdater.sync(repo.settings.value)
        Autostart.sync(repo.settings.value.startOnBoot)
    }

    fun generateWarpProfile() {
        viewModelScope.launch {
            snack("Setting up Cloudflare WARP…")
            repo.provisionWarp().fold(
                onSuccess = { snack("WARP profile added") },
                onFailure = { snack(it.message ?: "WARP setup failed") },
            )
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _ui.update { it.copy(checkingUpdate = true) }
            repo.checkForUpdate(AppDirs.APP_VERSION).fold(
                onSuccess = { info ->
                    if (info != null) _ui.update { it.copy(updateAvailable = info) }
                    else snack("You're on the latest version")
                },
                onFailure = { snack("Update check failed") },
            )
            _ui.update { it.copy(checkingUpdate = false) }
        }
    }

    fun dismissUpdate() = _ui.update { it.copy(updateAvailable = null) }

    fun updateGeoFiles() {
        viewModelScope.launch {
            snack("Downloading geo files…")
            repo.updateGeoFiles().fold(
                onSuccess = { count -> snack("Updated $count geo file(s) — reconnect to apply") },
                onFailure = { snack(it.message ?: "Geo update failed") },
            )
        }
    }

    private fun syncServiceActiveProfile() {
        VpnController.activeProfile?.let { active ->
            VpnController.activeProfile = repo.profiles.value.firstOrNull { it.id == active.id }
                ?: repo.getActiveProfile()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun snack(msg: String, action: String? = null) =
        _ui.update { it.copy(snackMessage = msg, snackActionLabel = action) }

    fun showSnack(message: String) = snack(message)

    fun clearSnack() = _ui.update { it.copy(snackMessage = null, snackActionLabel = null) }
}
