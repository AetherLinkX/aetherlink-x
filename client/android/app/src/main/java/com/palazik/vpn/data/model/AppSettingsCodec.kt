package com.palazik.vpn.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for (de)serializing [AppSettings] to the "app_settings"
 * JSON blob stored in the "palazik_profiles" SharedPreferences.
 *
 * Previously this logic was duplicated across ProfileRepository, palazikVpnService,
 * palazikVPNApp and the workers — adding a field meant editing 4 places and was
 * a frequent source of "setting silently ignored" bugs. Keep it here only.
 */
object AppSettingsCodec {

    const val KEY = "app_settings"

    fun fromJson(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings()
        return runCatching {
            val o = JSONObject(raw)
            val d = AppSettings()
            AppSettings(
                dnsServers = o.optJSONArray("dnsServers")?.toStringList()
                    ?.filter { it.isNotBlank() }
                    ?.ifEmpty { d.dnsServers }
                    ?: d.dnsServers,
                remoteDns = o.optString("remoteDns", d.remoteDns).ifBlank { d.remoteDns },
                directDns = o.optString("directDns", d.directDns).ifBlank { d.directDns },
                bypassPackages = o.optJSONArray("bypassPackages")?.toStringList()
                    ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                splitTunnelMode = runCatching { SplitTunnelMode.valueOf(o.optString("splitTunnelMode", d.splitTunnelMode.name)) }
                    .getOrDefault(d.splitTunnelMode),
                startOnBoot = o.optBoolean("startOnBoot", d.startOnBoot),
                autoUpdateSubscriptions = o.optBoolean("autoUpdateSubscriptions", d.autoUpdateSubscriptions),
                subscriptionUpdateIntervalHours = o.optLong("subscriptionUpdateIntervalHours", d.subscriptionUpdateIntervalHours)
                    .coerceAtLeast(2L),
                subscriptionUserAgent = o.optString("subscriptionUserAgent", d.subscriptionUserAgent)
                    .ifBlank { d.subscriptionUserAgent },
                geoipUrl = o.optString("geoipUrl", d.geoipUrl),
                geositeUrl = o.optString("geositeUrl", d.geositeUrl),
                blockAds = o.optBoolean("blockAds", d.blockAds),
                bypassChina = o.optBoolean("bypassChina", d.bypassChina),
                customDirectDomains = o.optJSONArray("customDirectDomains")?.toStringList()
                    ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                customBlockedDomains = o.optJSONArray("customBlockedDomains")?.toStringList()
                    ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                enableIpv6 = o.optBoolean("enableIpv6", d.enableIpv6),
                lockdownMode = o.optBoolean("lockdownMode", d.lockdownMode),
                routingMode = runCatching { RoutingMode.valueOf(o.optString("routingMode", d.routingMode.name)) }
                    .getOrDefault(d.routingMode),
                domainStrategy = runCatching { DomainStrategy.valueOf(o.optString("domainStrategy", d.domainStrategy.name)) }
                    .getOrDefault(d.domainStrategy),
                enableFakeDns = o.optBoolean("enableFakeDns", d.enableFakeDns),
                fragmentPackets = o.optString("fragmentPackets", d.fragmentPackets).ifBlank { d.fragmentPackets },
                fragmentLength = o.optString("fragmentLength", d.fragmentLength).ifBlank { d.fragmentLength },
                fragmentInterval = o.optString("fragmentInterval", d.fragmentInterval).ifBlank { d.fragmentInterval },
            )
        }.getOrDefault(AppSettings())
    }

    fun toJson(s: AppSettings): String = JSONObject().apply {
        put("dnsServers", JSONArray().apply { s.dnsServers.forEach { put(it) } })
        put("remoteDns", s.remoteDns)
        put("directDns", s.directDns)
        put("bypassPackages", JSONArray().apply { s.bypassPackages.forEach { put(it) } })
        put("splitTunnelMode", s.splitTunnelMode.name)
        put("startOnBoot", s.startOnBoot)
        put("autoUpdateSubscriptions", s.autoUpdateSubscriptions)
        put("subscriptionUpdateIntervalHours", s.subscriptionUpdateIntervalHours)
        put("subscriptionUserAgent", s.subscriptionUserAgent)
        put("geoipUrl", s.geoipUrl)
        put("geositeUrl", s.geositeUrl)
        put("blockAds", s.blockAds)
        put("bypassChina", s.bypassChina)
        put("customDirectDomains", JSONArray().apply { s.customDirectDomains.forEach { put(it) } })
        put("customBlockedDomains", JSONArray().apply { s.customBlockedDomains.forEach { put(it) } })
        put("enableIpv6", s.enableIpv6)
        put("lockdownMode", s.lockdownMode)
        put("routingMode", s.routingMode.name)
        put("domainStrategy", s.domainStrategy.name)
        put("enableFakeDns", s.enableFakeDns)
        put("fragmentPackets", s.fragmentPackets)
        put("fragmentLength", s.fragmentLength)
        put("fragmentInterval", s.fragmentInterval)
    }.toString()

    private fun JSONArray.toStringList(): List<String> =
        buildList { for (i in 0 until length()) add(optString(i)) }
}
