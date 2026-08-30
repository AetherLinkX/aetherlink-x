package com.palazik.vpn.ui.i18n

import androidx.compose.runtime.compositionLocalOf

/** App UI language. The app always forces one of these (no system auto-detect). */
enum class AppLanguage(val tag: String) {
    ENGLISH("en"),
    RUSSIAN("ru");

    companion object {
        /** English is the default language. */
        fun fromName(name: String?): AppLanguage =
            entries.firstOrNull { it.name == name } ?: ENGLISH
    }
}

/**
 * Desktop replacement for the Android string resources (values/strings.xml +
 * values-ru/strings.xml). Same keys, same translations.
 */
data class Strings(
    val connect: String,
    val disconnect: String,
    val connecting: String,

    val navHome: String,
    val navProfiles: String,
    val navSubs: String,
    val navSettings: String,

    val settingsTitle: String,
    val groupAppearance: String,
    val groupConnection: String,
    val groupProfilesData: String,
    val groupSystem: String,

    val settingsStyle: String,
    val settingsStyleSummary: String,
    val settingsLanguage: String,
    val settingsLanguageSummary: String,
    val settingsConnection: String,
    val settingsConnectionSummary: String,
    val settingsDns: String,
    val settingsDnsSummary: String,
    val settingsRouting: String,
    val settingsRoutingSummary: String,
    val settingsGeo: String,
    val settingsGeoSummary: String,
    val settingsSubscriptions: String,
    val settingsSubscriptionsSummary: String,
    val settingsBackup: String,
    val settingsBackupSummary: String,
    val settingsStartup: String,
    val settingsStartupSummary: String,
    val settingsDiagnostics: String,
    val settingsDiagnosticsSummary: String,
    val settingsAbout: String,
    val settingsAboutSummary: String,
)

val EnglishStrings = Strings(
    connect = "Connect",
    disconnect = "Disconnect",
    connecting = "Connecting…",
    navHome = "Home",
    navProfiles = "Profiles",
    navSubs = "Subs",
    navSettings = "Settings",
    settingsTitle = "Settings",
    groupAppearance = "Appearance",
    groupConnection = "Connection",
    groupProfilesData = "Profiles & data",
    groupSystem = "System",
    settingsStyle = "Style",
    settingsStyleSummary = "Design system, dark mode, color theme",
    settingsLanguage = "Language",
    settingsLanguageSummary = "App display language",
    settingsConnection = "Connection",
    settingsConnectionSummary = "Connection mode and ping test mode",
    settingsDns = "DNS",
    settingsDnsSummary = "VPN, remote and direct DNS",
    settingsRouting = "Routing & Privacy",
    settingsRoutingSummary = "Ad block, bypass China, IPv6, kill switch",
    settingsGeo = "Geo files",
    settingsGeoSummary = "Update geoip / geosite from a URL",
    settingsSubscriptions = "Subscriptions",
    settingsSubscriptionsSummary = "Auto-update and User-Agent",
    settingsBackup = "Backup",
    settingsBackupSummary = "Export / import profiles",
    settingsStartup = "Startup",
    settingsStartupSummary = "Auto-connect on login",
    settingsDiagnostics = "Diagnostics",
    settingsDiagnosticsSummary = "Connection log",
    settingsAbout = "About",
    settingsAboutSummary = "Version and info",
)

val RussianStrings = Strings(
    connect = "Подключить",
    disconnect = "Отключить",
    connecting = "Подключение…",
    navHome = "Главная",
    navProfiles = "Профили",
    navSubs = "Подписки",
    navSettings = "Настройки",
    settingsTitle = "Настройки",
    groupAppearance = "Оформление",
    groupConnection = "Подключение",
    groupProfilesData = "Профили и данные",
    groupSystem = "Система",
    settingsStyle = "Стиль",
    settingsStyleSummary = "Дизайн, тёмная тема, цветовая схема",
    settingsLanguage = "Язык",
    settingsLanguageSummary = "Язык интерфейса приложения",
    settingsConnection = "Подключение",
    settingsConnectionSummary = "Режим подключения и проверки пинга",
    settingsDns = "DNS",
    settingsDnsSummary = "VPN, удалённый и прямой DNS",
    settingsRouting = "Маршрутизация и приватность",
    settingsRoutingSummary = "Блокировка рекламы, обход Китая, IPv6, аварийный выключатель",
    settingsGeo = "Гео-файлы",
    settingsGeoSummary = "Обновить geoip / geosite по ссылке",
    settingsSubscriptions = "Подписки",
    settingsSubscriptionsSummary = "Автообновление и User-Agent",
    settingsBackup = "Резервная копия",
    settingsBackupSummary = "Экспорт / импорт профилей",
    settingsStartup = "Запуск",
    settingsStartupSummary = "Автоподключение при входе в систему",
    settingsDiagnostics = "Диагностика",
    settingsDiagnosticsSummary = "Журнал подключения",
    settingsAbout = "О приложении",
    settingsAboutSummary = "Версия и информация",
)

fun stringsFor(language: AppLanguage): Strings = when (language) {
    AppLanguage.ENGLISH -> EnglishStrings
    AppLanguage.RUSSIAN -> RussianStrings
}

val LocalStrings = compositionLocalOf { EnglishStrings }
