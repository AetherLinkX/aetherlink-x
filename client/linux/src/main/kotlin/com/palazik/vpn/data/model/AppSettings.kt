package com.palazik.vpn.data.model

enum class DesignSystem { MIUIX, MD3 }

/** Switchable routing presets. RULE_BASED preserves the original rule set. */
enum class RoutingMode {
    RULE_BASED,    // ads/china/custom-direct rules + LAN direct + everything else proxy
    GLOBAL,        // force all traffic through proxy (LAN + DNS still direct)
    BYPASS_LAN,    // only LAN/private direct; ignore China/custom-direct bypass
}

/** xray `domainStrategy` for the routing matcher. */
enum class DomainStrategy { IPIfNonMatch, AsIs, IPOnDemand }

data class AppSettings(
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "223.5.5.5",
    val startOnBoot: Boolean = false,
    val autoUpdateSubscriptions: Boolean = true,
    val subscriptionUpdateIntervalHours: Long = 2L,
    val designSystem: DesignSystem = DesignSystem.MD3,

    // ── Linux connection mode ─────────────────────────────────────────────────
    // PROXY: xray exposes SOCKS5 :10808 / HTTP :10809 only (no root needed).
    // TUN:   full-device tunnel via tun2socks — replicates Android VpnService
    //        (asks for root via pkexec on connect).
    val tunMode: Boolean = false,
    // Apply the desktop system proxy (GNOME/KDE) automatically while connected
    // in proxy mode, and restore it on disconnect.
    val systemProxy: Boolean = true,

    // ── Subscriptions ──────────────────────────────────────────────────────────
    val subscriptionUserAgent: String = "AetherLinkX/0.1",

    // ── Custom geo files — override the bundled geoip.dat/geosite.dat ───────────
    val geoipUrl: String = "",
    val geositeUrl: String = "",

    // ── Routing ────────────────────────────────────────────────────────────────
    val blockAds: Boolean = true,
    val bypassChina: Boolean = false,           // route geosite:cn / geoip:cn direct
    val customDirectDomains: List<String> = emptyList(),
    val customBlockedDomains: List<String> = emptyList(),

    // ── Privacy / leak protection ──────────────────────────────────────────────
    val enableIpv6: Boolean = false,            // add IPv6 route to TUN / allow IPv6 dialling
    val lockdownMode: Boolean = false,          // TUN mode: keep blackhole routes while reconnecting

    // ── Routing & DNS (advanced) ─────────────────────────────────────────────────
    val routingMode: RoutingMode = RoutingMode.RULE_BASED,
    val domainStrategy: DomainStrategy = DomainStrategy.IPIfNonMatch,
    val enableFakeDns: Boolean = false,         // FakeDNS/FakeIP for faster, leak-free routing

    // ── Anti-DPI fragmentation — params are global; applied per-profile ─────────
    val fragmentPackets: String = "tlshello",   // tlshello | 1-3 | …
    val fragmentLength: String = "100-200",
    val fragmentInterval: String = "10-20",
)
