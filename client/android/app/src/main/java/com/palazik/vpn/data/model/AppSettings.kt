package com.palazik.vpn.data.model

enum class DesignSystem { MIUIX, MD3 }

/** Switchable routing presets (#2). RULE_BASED preserves the original rule set. */
enum class RoutingMode {
    RULE_BASED,    // ads/china/custom-direct rules + LAN direct + everything else proxy
    GLOBAL,        // force all traffic through proxy (LAN + DNS still direct)
    BYPASS_LAN,    // only LAN/private direct; ignore China/custom-direct bypass
}

/** xray `domainStrategy` for the routing matcher (#7). */
enum class DomainStrategy { IPIfNonMatch, AsIs, IPOnDemand }

/**
 * How the selected app list is interpreted:
 *  - BYPASS: the listed apps skip the tunnel, everything else goes through it.
 *  - ONLY:   only the listed apps go through the tunnel, everything else is direct.
 */
enum class SplitTunnelMode { BYPASS, ONLY }

data class AppSettings(
    val dnsServers: List<String> = listOf("8.8.8.8", "1.1.1.1"),
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val directDns: String = "223.5.5.5",
    val bypassPackages: List<String> = emptyList(),
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.BYPASS,
    val startOnBoot: Boolean = false,
    val autoUpdateSubscriptions: Boolean = true,
    val subscriptionUpdateIntervalHours: Long = 2L,
    val designSystem: DesignSystem = DesignSystem.MD3,

    // ── Subscriptions ──────────────────────────────────────────────────────────
    val subscriptionUserAgent: String = "AetherLinkX/0.1",

    // ── Custom geo files (#6) — override the bundled geoip.dat/geosite.dat ───────
    val geoipUrl: String = "",
    val geositeUrl: String = "",

    // ── Routing ────────────────────────────────────────────────────────────────
    val blockAds: Boolean = true,
    val bypassChina: Boolean = false,           // route geosite:cn / geoip:cn direct
    val customDirectDomains: List<String> = emptyList(),
    val customBlockedDomains: List<String> = emptyList(),

    // ── Privacy / leak protection ──────────────────────────────────────────────
    val enableIpv6: Boolean = false,            // add IPv6 address + ::/0 route to TUN
    val lockdownMode: Boolean = false,          // block traffic while VPN handler not ready (kill switch)

    // ── Routing & DNS (advanced) ─────────────────────────────────────────────────
    val routingMode: RoutingMode = RoutingMode.RULE_BASED,        // #2 switchable presets
    val domainStrategy: DomainStrategy = DomainStrategy.IPIfNonMatch, // #7 routing match strategy
    val enableFakeDns: Boolean = false,         // #6 FakeDNS/FakeIP for faster, leak-free routing

    // ── Anti-DPI fragmentation (#23) — params are global; applied per-profile (#22) ──
    val fragmentPackets: String = "tlshello",   // tlshello | 1-3 | …
    val fragmentLength: String = "100-200",
    val fragmentInterval: String = "10-20",
)
