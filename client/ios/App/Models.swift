import Foundation

// ── Enums (mirror the Android model) ─────────────────────────────────────────

enum ProxyProtocol: String, Codable, CaseIterable {
    case aetherlinkx, vmess, vless, shadowsocks, trojan, hysteria2, wireguard, socks5, http, tuic, anytls
}

enum Transport: String, Codable, CaseIterable {
    case tcp, ws, grpc, xhttp, h2, quic
}

enum Security: String, Codable, CaseIterable {
    case none, tls, reality, xtls
}

enum RoutingMode: String, Codable, CaseIterable {
    case ruleBased, global, bypassLan
}

enum DomainStrategy: String, Codable, CaseIterable {
    case IPIfNonMatch, AsIs, IPOnDemand
}

// ── Profile ───────────────────────────────────────────────────────────────────

struct VpnProfile: Codable, Identifiable, Equatable {
    var id: String = UUID().uuidString
    var name: String = "Unnamed"
    var proto: ProxyProtocol = .vmess

    // common
    var address: String = ""
    var port: Int = 443
    var uuid: String = ""          // vmess/vless id, trojan/anytls password

    // transport
    var transport: Transport = .tcp
    var path: String = "/"
    var host: String = ""

    // security
    var security: Security = .tls
    var sni: String = ""
    var fingerprint: String = "chrome"
    var publicKey: String = ""     // reality / wireguard peer
    var shortId: String = ""
    var allowInsecure: Bool = false

    // vmess
    var vmessSecurity: String = "auto"

    // shadowsocks
    var ssMethod: String = "chacha20-ietf-poly1305"
    var ssPassword: String = ""

    // wireguard
    var wgPrivateKey: String = ""
    var wgPeerPublicKey: String = ""
    var wgPreSharedKey: String = ""
    var wgEndpoint: String = ""
    var wgDns: String = "1.1.1.1"
    var wgMtu: Int = 1280
    var wgReserved: String = ""

    // hysteria2
    var hystPassword: String = ""
    var hystObfs: String = ""
    var hystObfsPassword: String = ""

    // AetherLink X. JSON blobs intentionally retain unknown future fields.
    var alxSecret: String = ""
    var alxAllowInsecureTransport: Bool = false
    var alxTurboJson: String = "{}"
    var alxSecurityJson: String = "{}"
    var alxStealthJson: String = "{}"

    // per-profile overrides
    var muxEnabled: Bool = true
    var fragmentEnabled: Bool = false

    // metadata
    var subscriptionId: String? = nil
    var latencyMs: Int = -1
}

// ── Settings ──────────────────────────────────────────────────────────────────

struct AppSettings: Codable, Equatable {
    var dnsServers: [String] = ["8.8.8.8", "1.1.1.1"]
    var remoteDns: String = "https://1.1.1.1/dns-query"
    var directDns: String = "223.5.5.5"

    var blockAds: Bool = true
    var bypassChina: Bool = false
    var customDirectDomains: [String] = []
    var customBlockedDomains: [String] = []

    var enableIpv6: Bool = false
    var routingMode: RoutingMode = .ruleBased
    var domainStrategy: DomainStrategy = .IPIfNonMatch
    var enableFakeDns: Bool = false

    var fragmentPackets: String = "tlshello"
    var fragmentLength: String = "100-200"
    var fragmentInterval: String = "10-20"

    var subscriptionUserAgent: String = "AetherLinkX/0.1"
    var geoipUrl: String = ""
    var geositeUrl: String = ""

    // appearance
    var appTheme: String = "cyber"
    var darkMode: String = "system"
}

// ── Subscription ──────────────────────────────────────────────────────────────

struct Subscription: Codable, Identifiable, Equatable {
    var id: String = UUID().uuidString
    var name: String = "Subscription"
    var url: String = ""
    var lastUpdated: Double = 0
    var profileCount: Int = 0

    var uploadBytes: Int64 = -1
    var downloadBytes: Int64 = -1
    var totalBytes: Int64 = -1
    var expireEpochSec: Int64 = -1

    var usedBytes: Int64 {
        if uploadBytes < 0 && downloadBytes < 0 { return -1 }
        return max(uploadBytes, 0) + max(downloadBytes, 0)
    }
    var hasUsageInfo: Bool { usedBytes >= 0 || totalBytes >= 0 }
    var hasExpiry: Bool { expireEpochSec > 0 }
}
