package com.palazik.vpn.service

import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.SplitTunnelMode

/** One Android app filter for every location and protocol. No per-profile rewrites. */
object AppTunnelPolicy {
    const val GOSUSLUGI_PACKAGE = "ru.rostel"

    data class Filter(val allowOnly: Boolean, val packages: List<String>)

    fun resolve(
        settings: AppSettings,
        ownPackage: String,
        isInstalled: (String) -> Boolean,
    ): Filter {
        val chosen = settings.bypassPackages.map(String::trim)
            .filter { it.isNotEmpty() && it != ownPackage }.distinct()
        val automatic = if (settings.bypassGosuslugi) setOf(GOSUSLUGI_PACKAGE) else emptySet()

        // Preserve the existing meaning of an empty selection: tunnel all other apps.
        if (settings.splitTunnelMode == SplitTunnelMode.ONLY && settings.bypassPackages.isNotEmpty()) {
            val allowed = chosen.filter { it !in automatic && isInstalled(it) }
            // An empty Android allow-list means ALL apps, not no apps. Never silently
            // broaden an include-only selection after removing Gosuslugi or stale apps.
            require(allowed.isNotEmpty()) {
                "Нет приложений для VPN. Выберите другое приложение или измените режим раздельного туннелирования."
            }
            return Filter(allowOnly = true, packages = allowed)
        }

        return Filter(
            allowOnly = false,
            packages = (listOf(ownPackage) + (chosen + automatic).filter(isInstalled)).distinct(),
        )
    }
}
