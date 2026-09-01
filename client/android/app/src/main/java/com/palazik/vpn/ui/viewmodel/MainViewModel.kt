package com.palazik.vpn.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.palazik.vpn.data.model.*
import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.repository.ProfileRepository
import com.palazik.vpn.data.repository.SubscriptionUpdateScheduler
import com.palazik.vpn.data.repository.UpdateInfo
import com.palazik.vpn.service.XrayConfigBuilder
import com.palazik.vpn.service.palazikVpnService
import com.palazik.vpn.data.model.DesignSystem
import com.palazik.vpn.ui.theme.AppTheme
import com.palazik.vpn.ui.theme.DarkModePreference
import com.palazik.vpn.ui.locale.AppLanguage
import com.palazik.vpn.ui.locale.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UiState(
    val vpnState: VpnState                = VpnState.DISCONNECTED,
    val activeProfile: VpnProfile?        = null,
    val profiles: List<VpnProfile>        = emptyList(),
    val subscriptions: List<Subscription> = emptyList(),
    val appTheme: AppTheme                = AppTheme.CYBER,
    val darkMode: DarkModePreference      = DarkModePreference.ALWAYS_DARK,
    val designSystem: DesignSystem        = DesignSystem.MD3,
    val language: AppLanguage             = AppLanguage.ENGLISH,
    val pingMode: PingMode                = PingMode.TCP,
    val settings: AppSettings             = AppSettings(),
    val installedApps: List<InstalledApp>  = emptyList(),
    val lastError: String?                = null,
    val snackMessage: String?             = null,
    val snackActionLabel: String?         = null,
    val shareLink: String?                = null,
    val isUpdatingSubscriptions: Boolean  = false,
    val updatingSubscriptionIds: Set<String> = emptySet(),
    val checkingUpdate: Boolean           = false,
    val updateAvailable: UpdateInfo?      = null,
)

private const val THEME_PREFS       = "palazik_theme"
private const val KEY_THEME         = "app_theme"
private const val KEY_DARKMODE      = "dark_mode"
private const val KEY_DESIGN_SYSTEM = "design_system"

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: ProfileRepository,
) : ViewModel() {

    private val themePrefs = context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
    private var deletedProfile: VpnProfile? = null

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    // High-frequency values (update ~1×/sec) are exposed as dedicated flows instead of
    // living in the big UiState — so a traffic tick doesn't recompose every screen that
    // reads `ui`. Collect these only where they're shown.
    val bytesIn:        StateFlow<Long>         = palazikVpnService.bytesIn
    val bytesOut:       StateFlow<Long>         = palazikVpnService.bytesOut
    val connectedSince: StateFlow<Long>         = palazikVpnService.connectedSince
    val diagnostics:    StateFlow<List<String>> = palazikVpnService.diagnostics

    init {
        // Restore persisted theme on startup
        val savedTheme  = themePrefs.getString(KEY_THEME,         AppTheme.CYBER.name)
        val savedDark   = themePrefs.getString(KEY_DARKMODE,      DarkModePreference.ALWAYS_DARK.name)
        val savedDesign = themePrefs.getString(KEY_DESIGN_SYSTEM, DesignSystem.MD3.name)
        _ui.update { it.copy(
            appTheme     = runCatching { AppTheme.valueOf(savedTheme ?: "") }.getOrDefault(AppTheme.CYBER),
            darkMode     = runCatching { DarkModePreference.valueOf(savedDark ?: "") }.getOrDefault(DarkModePreference.ALWAYS_DARK),
            designSystem = runCatching { DesignSystem.valueOf(savedDesign ?: "") }.getOrDefault(DesignSystem.MD3),
            language     = LocaleHelper.savedLanguage(context),
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

        // Mirror VPN service state
        viewModelScope.launch {
            palazikVpnService.connectionState.collect { svcState ->
                _ui.update { it.copy(vpnState = when (svcState) {
                    palazikVpnService.ServiceState.RUNNING  -> VpnState.CONNECTED
                    palazikVpnService.ServiceState.STARTING -> VpnState.CONNECTING
                    palazikVpnService.ServiceState.STOPPING -> VpnState.DISCONNECTING
                    palazikVpnService.ServiceState.STOPPED  -> VpnState.DISCONNECTED
                    palazikVpnService.ServiceState.ERROR    -> VpnState.ERROR
                }) }
            }
        }
        viewModelScope.launch { palazikVpnService.lastError.collect { error -> _ui.update { it.copy(lastError = error) } } }
        loadInstalledApps()
    }

    // ── VPN toggle ────────────────────────────────────────────────────────────

    fun prepareVpn(): Intent? = VpnService.prepare(context)

    fun connect() {
        val profile = _ui.value.activeProfile ?: run { snack("Сначала выберите профиль"); return }
        // Pass full profile object to service companion — the service needs it to build xray config
        palazikVpnService.activeProfile = profile
        context.startForegroundService(
            Intent(context, palazikVpnService::class.java).apply {
                action = palazikVpnService.ACTION_START
                putExtra(palazikVpnService.EXTRA_PROFILE, profile.id)
            }
        )
    }

    fun disconnect() {
        context.startService(
            Intent(context, palazikVpnService::class.java).apply {
                action = palazikVpnService.ACTION_STOP
            }
        )
    }

    fun toggleVpn(permLauncher: ActivityResultLauncher<Intent>) {
        when (_ui.value.vpnState) {
            VpnState.CONNECTED, VpnState.CONNECTING -> disconnect()
            else -> {
                val prepare = prepareVpn()
                if (prepare != null) permLauncher.launch(prepare) else connect()
            }
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
            snack("Профиль «${profile.name}» импортирован")
        } else {
            snack("Не удалось распознать ссылку")
        }
    }

    /** One entry point for clipboard, QR, deep links, share links and backup bodies. */
    fun importFromText(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) {
            snack("Вставьте ссылку или конфигурацию")
            return
        }
        val uri = runCatching { android.net.Uri.parse(text) }.getOrNull()
        if (uri?.scheme.equals("http", true) || uri?.scheme.equals("https", true)) {
            importSubscriptionFromUrl(text)
            return
        }
        ProfileCodec.decode(text)?.let {
            importProfileFromLink(text)
            return
        }
        val profiles = ProfileCodec.decodeSubscriptionBody(text)
            .filter { ProfileValidator.validate(it).isEmpty() }
        if (profiles.isEmpty()) {
            snack("Формат не поддерживается. Проверьте ссылку или QR-код")
            return
        }
        profiles.forEach(repo::addProfile)
        syncServiceActiveProfile()
        snack("Импортировано профилей: ${profiles.size}")
    }

    fun addManualProfile(profile: VpnProfile): Boolean {
        val errors = ProfileValidator.validate(profile)
        if (errors.isNotEmpty()) {
            snack(errors.first())
            return false
        }
        repo.addProfile(profile)
        snack("Профиль «${profile.name}» добавлен")
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
        if (palazikVpnService.activeProfile?.id == profile.id) {
            palazikVpnService.activeProfile = profile
        }
        snack("Профиль «${profile.name}» обновлён")
        return true
    }

    fun removeProfile(id: String) {
        val profile = _ui.value.profiles.firstOrNull { it.id == id }
        deletedProfile = profile
        if (profile?.id == palazikVpnService.activeProfile?.id) {
            disconnect()
            palazikVpnService.activeProfile = null
        }
        repo.removeProfile(id)
        snack("Профиль удалён", "Отменить")
    }

    fun undoSnackAction() {
        deletedProfile?.let {
            repo.addProfile(it)
            snack("Профиль восстановлен")
        }
        deletedProfile = null
    }

    fun selectProfile(id: String) {
        val selected = repo.profiles.value.firstOrNull { it.id == id } ?: return
        if (_ui.value.activeProfile?.id == id) return
        if (_ui.value.vpnState in listOf(VpnState.CONNECTING, VpnState.DISCONNECTING)) {
            snack("Дождитесь завершения текущего переключения")
            return
        }
        repo.setActiveProfile(id)
        if (_ui.value.vpnState == VpnState.CONNECTED) {
            // Keep the service's activeProfile pointing at the currently running
            // outbound until ACTION_SWITCH has fully torn it down.  Replacing this
            // field here made switchVpn() think the requested profile was already
            // running and silently skip the switch.
            context.startService(
                Intent(context, palazikVpnService::class.java).apply {
                    action = palazikVpnService.ACTION_SWITCH
                    putExtra(palazikVpnService.EXTRA_PROFILE, id)
                }
            )
            snack("Переключаемся на «${selected.name}»…")
        } else {
            palazikVpnService.activeProfile = selected.copy(isActive = true)
        }
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
        snack("Копия профиля создана")
    }

    fun generateShareLink() {
        val profile = _ui.value.activeProfile ?: run { snack("Нет активного профиля"); return }
        _ui.update { it.copy(shareLink = ProfileCodec.encodePalazik(profile)) }
    }

    fun generateNativeLink(profile: VpnProfile) {
        _ui.update { it.copy(shareLink = ProfileCodec.encodeNative(profile)) }
    }

    fun generateJsonConfig(profile: VpnProfile) {
        _ui.update { it.copy(shareLink = XrayConfigBuilder.build(profile, _ui.value.settings)) }
    }

    fun jsonConfigFor(profileId: String): String? =
        _ui.value.profiles.firstOrNull { it.id == profileId }
            ?.let { XrayConfigBuilder.build(it, _ui.value.settings) }

    fun clearShareLink() = _ui.update { it.copy(shareLink = null) }

    // ── Backup / restore ───────────────────────────────────────────────────────

    /** Serialize every profile as alxclient:// links for export to a file. */
    fun exportProfilesText(): String = repo.exportProfilesText()

    /** Import profiles from a backup file body; reports how many were added. */
    fun importProfilesText(body: String) {
        val added = repo.importProfilesText(body)
        syncServiceActiveProfile()
        snack(if (added > 0) "Импортировано профилей: $added" else "Новые профили не найдены")
    }

    // ── Ping ─────────────────────────────────────────────────────────────────

    fun pingProfile(profile: VpnProfile) {
        viewModelScope.launch {
            // HTTP/HEAD ping measures the ACTIVE tunnel end-to-end, so it only makes sense
            // for the currently active profile while connected. TCP works for any profile.
            if (_ui.value.pingMode in listOf(PingMode.AETHERLINK, PingMode.HTTP_GET, PingMode.HTTP_HEAD)) {
                if (_ui.value.vpnState != VpnState.CONNECTED) {
                    snack("Для HTTP-пинга сначала подключите VPN")
                    return@launch
                }
                if (profile.id != _ui.value.activeProfile?.id) {
                    snack("Проверка через туннель доступна только для активного профиля")
                    return@launch
                }
            }
            snack("Проверяем ${profile.name}…")
            val ms = repo.pingProfile(profile)
            snack(if (ms >= 0) "${profile.name}: ${ms} мс" else "${profile.name}: нет ответа")
        }
    }

    fun pingAll() {
        viewModelScope.launch {
            // pingProfiles always uses TCP (per-server) — no VPN required, no HTTP gate.
            snack("Проверяем профили: ${_ui.value.profiles.size}…")
            repo.pingProfiles(_ui.value.profiles)
            snack("Проверка завершена")
        }
    }

    fun pingSubscription(subscriptionId: String) {
        viewModelScope.launch {
            val profiles = _ui.value.profiles.filter { it.subscriptionId == subscriptionId }
            if (profiles.isEmpty()) {
                snack("В этой подписке пока нет доступных локаций")
                return@launch
            }
            snack("Проверяем локации: ${profiles.size}…")
            repo.pingProfiles(profiles)
            snack("Проверка всех локаций завершена")
        }
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    /**
     * Import an HTTPS subscription opened as an Android App Link. Existing URLs are
     * refreshed instead of duplicated; the first successfully parsed profile becomes
     * active as required by one-tap subscription import.
     */
    fun importSubscriptionFromUrl(rawUrl: String) {
        val normalizedUrl = normalizeSubscriptionUrl(rawUrl)
        val uri = runCatching { android.net.Uri.parse(normalizedUrl) }.getOrNull()
        if ((uri?.scheme != "https" && uri?.scheme != "http") || uri.host.isNullOrBlank()) {
            snack("Нужна HTTP- или HTTPS-ссылка на подписку")
            return
        }
        val existing = repo.subscriptions.value.firstOrNull {
            normalizeSubscriptionUrl(it.url) == normalizedUrl
        }
        val name = existing?.name ?: uri.host.orEmpty() + (uri.lastPathSegment?.let { " · $it" } ?: "")
        val sub = existing ?: Subscription(name = name, url = normalizedUrl).also(repo::addSubscription)

        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            repo.updateSubscription(sub).fold(
                onSuccess = { count ->
                    val imported = repo.profiles.value.firstOrNull { it.subscriptionId == sub.id }
                    if (imported != null) {
                        if (_ui.value.vpnState == VpnState.CONNECTED || _ui.value.vpnState == VpnState.CONNECTING) {
                            disconnect()
                        }
                        repo.setActiveProfile(imported.id)
                        palazikVpnService.activeProfile = imported.copy(isActive = true)
                    }
                    snack(if (count > 0) "Импортировано и активировано профилей: $count" else "Подписка добавлена, но провайдер пока не выдал доступных локаций")
                },
                onFailure = {
                    snack("Подписка сохранена, но её ответ пока не удалось распознать")
                },
            )
            SubscriptionUpdateScheduler.sync(context, repo.settings.value)
            _ui.update { it.copy(isUpdatingSubscriptions = false) }
        }
    }

    fun addSubscription(name: String, url: String) {
        val normalizedUrl = normalizeSubscriptionUrl(url)
        val existing = repo.subscriptions.value.firstOrNull {
            normalizeSubscriptionUrl(it.url) == normalizedUrl
        }
        val sub = existing ?: Subscription(name = name, url = normalizedUrl).also(repo::addSubscription)
        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            repo.updateSubscription(sub).fold(
                onSuccess = { count ->
                    val imported = repo.profiles.value.firstOrNull { it.subscriptionId == sub.id }
                    if (imported != null) {
                        if (_ui.value.vpnState == VpnState.CONNECTED || _ui.value.vpnState == VpnState.CONNECTING) {
                            disconnect()
                        }
                        repo.setActiveProfile(imported.id)
                        palazikVpnService.activeProfile = imported.copy(isActive = true)
                    }
                    snack(if (count > 0) "Из «$name» загружено и активировано профилей: $count" else "Подписка сохранена. Доступных локаций пока нет")
                },
                onFailure = {
                    snack("Подписка сохранена, но её ответ пока не удалось распознать")
                },
            )
            syncServiceActiveProfile()
            SubscriptionUpdateScheduler.sync(context, _ui.value.settings)
            _ui.update { it.copy(isUpdatingSubscriptions = false) }
        }
    }

    private fun normalizeSubscriptionUrl(raw: String): String {
        val trimmed = raw.trim().replace("\u200B", "")
        val uri = runCatching { android.net.Uri.parse(trimmed) }.getOrNull() ?: return trimmed
        val normalizedPath = uri.path.orEmpty().let { path ->
            if (path.length > 1) path.trimEnd('/') else path
        }
        return uri.buildUpon().path(normalizedPath).fragment(null).build().toString()
    }

    fun removeSubscription(id: String) {
        if (_ui.value.profiles.any { it.subscriptionId == id && it.id == palazikVpnService.activeProfile?.id }) {
            disconnect()
            palazikVpnService.activeProfile = null
        }
        repo.removeSubscription(id)
        snack("Профиль подписки полностью удалён")
    }

    fun updateSubscription(sub: Subscription) {
        viewModelScope.launch {
            _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds + sub.id) }
            snack("Обновляем «${sub.name}»…")
            repo.updateSubscription(sub).fold(
                onSuccess = { count ->
                    syncServiceActiveProfile()
                    snack(if (count > 0) "Обновлено профилей: $count" else "Подписка обновлена, но доступных локаций пока нет")
                },
                onFailure = { snack("Обновление не удалось") },
            )
            _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds - sub.id) }
        }
    }

    fun chooseBestProfileForSubscription(sub: Subscription) {
        viewModelScope.launch {
            // Must be disconnected to switch the active profile. The comparison itself uses
            // TCP (per-server) so it works while disconnected — BUG FIX: the old code also
            // required the VPN to be CONNECTED for HTTP modes, which can never both hold, so
            // this path always returned early.
            if (_ui.value.vpnState != VpnState.DISCONNECTED && _ui.value.vpnState != VpnState.ERROR) {
                snack("Отключите VPN перед сменой профиля")
                return@launch
            }
            val candidates = _ui.value.profiles.filter { it.subscriptionId == sub.id }
            if (candidates.isEmpty()) {
                snack("В «${sub.name}» нет профилей")
                return@launch
            }
            _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds + sub.id) }
            try {
                snack("Проверяем профили «${sub.name}»…")
                // Ping concurrently, then read fresh latencies off the updated list
                repo.pingProfiles(candidates)
                val best = repo.profiles.value
                    .filter { it.subscriptionId == sub.id && it.latencyMs >= 0 }
                    .minByOrNull { it.latencyMs }
                if (best != null) {
                    repo.setActiveProfile(best.id)
                    palazikVpnService.activeProfile = repo.profiles.value.firstOrNull { it.id == best.id }
                    snack("Выбран лучший профиль: ${best.latencyMs} мс")
                } else {
                    snack("Все профили не ответили")
                }
            } finally {
                _ui.update { it.copy(updatingSubscriptionIds = it.updatingSubscriptionIds - sub.id) }
            }
        }
    }

    fun updateAllSubscriptions() {
        viewModelScope.launch {
            _ui.update { it.copy(isUpdatingSubscriptions = true) }
            snack("Обновляем все подписки…")
            val results = repo.updateAllSubscriptions()
            syncServiceActiveProfile()
            val failed = results.count { it.isFailure }
            val updated = results.sumOf { it.getOrDefault(0) }
            snack(if (failed == 0) "Обновлено профилей: $updated" else "Обновлено: $updated, ошибок: $failed")
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

    /**
     * Persist the chosen UI language. The caller is responsible for recreating the
     * Activity so the new locale takes effect (resources are bound at attach time).
     */
    fun setLanguage(language: AppLanguage) {
        _ui.update { it.copy(language = language) }
        LocaleHelper.persistLanguage(context, language)
    }

    // ── Ping mode ─────────────────────────────────────────────────────────────

    fun setPingMode(mode: PingMode) = repo.setPingMode(mode)

    fun updateAppSettings(settings: AppSettings) {
        repo.updateSettings(settings)
        SubscriptionUpdateScheduler.sync(context, repo.settings.value)
    }

    fun generateWarpProfile() {
        viewModelScope.launch {
            snack("Настраиваем Cloudflare WARP…")
            repo.provisionWarp().fold(
                onSuccess = { snack("Профиль WARP добавлен") },
                onFailure = { snack(it.message ?: "Не удалось настроить WARP") },
            )
        }
    }

    fun checkForUpdate() {
        viewModelScope.launch {
            _ui.update { it.copy(checkingUpdate = true) }
            val current = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
            repo.checkForUpdate(current).fold(
                onSuccess = { info ->
                    if (info != null) _ui.update { it.copy(updateAvailable = info) }
                    else snack("У вас актуальная версия")
                },
                onFailure = { snack("Не удалось проверить обновления") },
            )
            _ui.update { it.copy(checkingUpdate = false) }
        }
    }

    fun dismissUpdate() = _ui.update { it.copy(updateAvailable = null) }

    fun updateGeoFiles() {
        viewModelScope.launch {
            snack("Загружаем гео-файлы…")
            repo.updateGeoFiles().fold(
                onSuccess = { count -> snack("Обновлено гео-файлов: $count. Переподключите VPN") },
                onFailure = { snack(it.message ?: "Не удалось обновить гео-файлы") },
            )
        }
    }

    private fun syncServiceActiveProfile() {
        palazikVpnService.activeProfile?.let { active ->
            palazikVpnService.activeProfile = repo.profiles.value.firstOrNull { it.id == active.id }
                ?: repo.getActiveProfile()
        }
    }

    private fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val self = context.packageName

            // Gather candidates from both sources and dedupe by package before touching
            // loadLabel() — that call hits the PackageManager once per app and is the slow
            // part, so we don't want to pay it twice for apps that show up in both lists.
            // getInstalledApplications covers everything when QUERY_ALL_PACKAGES is granted;
            // the launcher query is the fallback (visible via the <queries> manifest entry).
            val candidates = LinkedHashMap<String, ApplicationInfo>()

            runCatching { pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS) }
                .getOrDefault(emptyList())
                .forEach { app ->
                    if (app.enabled && app.packageName != self) candidates.putIfAbsent(app.packageName, app)
                }

            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .forEach { resolve ->
                    val app = resolve.activityInfo?.applicationInfo ?: return@forEach
                    if (app.packageName != self) candidates.putIfAbsent(app.packageName, app)
                }

            val apps = candidates.values
                .map { app -> InstalledApp(app.loadLabel(pm).toString().ifBlank { app.packageName }, app.packageName) }
                .sortedBy { it.label.lowercase() }

            _ui.update { it.copy(installedApps = apps) }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun snack(msg: String, action: String? = null) =
        _ui.update { it.copy(snackMessage = msg, snackActionLabel = action) }

    fun showSnack(message: String) = snack(message)

    fun clearSnack() = _ui.update { it.copy(snackMessage = null, snackActionLabel = null) }
}
