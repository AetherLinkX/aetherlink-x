import Foundation

/// Builds the intermediate Xray JSON (DNS + outbounds + routing) from a profile and the
/// app settings. SwiftyXrayKit injects the TUN inbound, so we omit inbounds here.
/// Mirrors the Android XrayConfigBuilder.
enum XrayConfigBuilder {

    static func build(_ p: VpnProfile, _ s: AppSettings) -> String {
        var root: [String: Any] = [
            "log": ["loglevel": "warning"],
            "dns": dns(s),
            "outbounds": outbounds(p, s),
            "routing": routing(s),
            "stats": [:],
        ]
        if s.enableFakeDns {
            root["fakedns"] = [["ipPool": "198.18.0.0/15", "poolSize": 65535]]
        }
        let data = (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    // ── DNS ──────────────────────────────────────────────────────────────────

    private static func dns(_ s: AppSettings) -> [String: Any] {
        var servers: [Any] = []
        if s.enableFakeDns { servers.append("fakedns") }
        servers.append(["address": s.remoteDns, "domains": ["geosite:geolocation-!cn"]])
        servers.append(s.directDns)
        servers.append("localhost")
        return [
            "hosts": [
                "dns.google": ["8.8.8.8", "8.8.4.4"],
                "cloudflare-dns.com": ["1.1.1.1", "1.0.0.1"],
            ],
            "servers": servers,
        ]
    }

    // ── Outbounds ──────────────────────────────────────────────────────────────

    private static func outbounds(_ p: VpnProfile, _ s: AppSettings) -> [Any] {
        var list: [Any] = [proxyOutbound(p, s)]
        if p.fragmentEnabled {
            list.append([
                "tag": "fragment",
                "protocol": "freedom",
                "settings": ["domainStrategy": "AsIs", "fragment": [
                    "packets": s.fragmentPackets.isEmpty ? "tlshello" : s.fragmentPackets,
                    "length": s.fragmentLength.isEmpty ? "100-200" : s.fragmentLength,
                    "interval": s.fragmentInterval.isEmpty ? "10-20" : s.fragmentInterval,
                ]],
                "streamSettings": ["sockopt": ["tcpNoDelay": true]],
            ])
        }
        list.append(["tag": "direct", "protocol": "freedom",
                     "settings": ["domainStrategy": s.enableIpv6 ? "UseIP" : "UseIPv4"]])
        list.append(["tag": "block", "protocol": "blackhole",
                     "settings": ["response": ["type": "http"]]])
        return list
    }

    private static func proxyOutbound(_ p: VpnProfile, _ s: AppSettings) -> [String: Any] {
        var o: [String: Any] = ["tag": "proxy"]
        o["protocol"] = xrayProtocol(p.proto)
        o["settings"] = settings(p)

        let noStream: Set<ProxyProtocol> = [.hysteria2, .wireguard, .shadowsocks, .http]
        if !noStream.contains(p.proto) {
            var stream = streamSettings(p)
            if p.fragmentEnabled && p.transport != .quic {
                stream["sockopt"] = ["dialerProxy": "fragment"]
            }
            o["streamSettings"] = stream
        }

        let noMux: Set<ProxyProtocol> = [.aetherlinkx, .shadowsocks, .trojan, .anytls, .hysteria2, .wireguard, .tuic, .socks5, .http]
        let useMux = p.muxEnabled && !noMux.contains(p.proto)
            && p.transport != .xhttp && p.transport != .quic
            && p.security != .reality && p.security != .xtls
        o["mux"] = useMux ? ["enabled": true, "concurrency": 8] : ["enabled": false]
        return o
    }

    private static func xrayProtocol(_ p: ProxyProtocol) -> String {
        switch p {
        case .aetherlinkx: return "aetherlinkx"
        case .vmess: return "vmess"
        case .vless: return "vless"
        case .shadowsocks: return "shadowsocks"
        case .trojan: return "trojan"
        case .hysteria2: return "hysteria2"
        case .wireguard: return "wireguard"
        case .socks5: return "socks"
        case .http: return "http"
        case .tuic: return "tuic"
        case .anytls: return "anytls"
        }
    }

    private static func settings(_ p: VpnProfile) -> [String: Any] {
        switch p.proto {
        case .aetherlinkx:
            var result: [String: Any] = [
                "address": p.address,
                "port": p.port,
                "id": p.uuid,
                "secret": p.alxSecret,
                "allowInsecureTransport": p.alxAllowInsecureTransport,
            ]
            if let turbo = optionsObject(p.alxTurboJson) { result["turbo"] = turbo }
            if let security = optionsObject(p.alxSecurityJson) { result["security"] = security }
            if let stealth = optionsObject(p.alxStealthJson) { result["stealth"] = stealth }
            return result
        case .vless:
            var user: [String: Any] = ["id": p.uuid, "encryption": "none"]
            if p.transport == .tcp && (p.security == .reality || p.security == .xtls) {
                user["flow"] = "xtls-rprx-vision"
            }
            return ["vnext": [["address": p.address, "port": p.port, "users": [user]]]]
        case .vmess:
            return ["vnext": [["address": p.address, "port": p.port, "users":
                [["id": p.uuid, "alterId": 0, "security": p.vmessSecurity.isEmpty ? "auto" : p.vmessSecurity]]]]]
        case .shadowsocks:
            return ["servers": [["address": p.address, "port": p.port, "method": p.ssMethod, "password": p.ssPassword]]]
        case .trojan:
            return ["servers": [["address": p.address, "port": p.port, "password": p.uuid]]]
        case .anytls:
            return ["address": p.address, "port": p.port, "password": p.uuid]
        case .hysteria2:
            var server: [String: Any] = ["address": p.address, "port": p.port, "password": p.hystPassword]
            if !p.sni.isEmpty { server["tlsSettings"] = ["serverName": p.sni, "allowInsecure": p.allowInsecure] }
            if !p.hystObfs.isEmpty { server["obfs"] = ["type": p.hystObfs, "password": p.hystObfsPassword] }
            return ["servers": [server]]
        case .wireguard:
            var settings: [String: Any] = [
                "secretKey": p.wgPrivateKey,
                "address": [p.address.isEmpty ? "10.0.0.2/32" : p.address],
                "peers": [[
                    "publicKey": p.wgPeerPublicKey,
                    "allowedIPs": ["0.0.0.0/0", "::/0"],
                    "endpoint": p.wgEndpoint.isEmpty ? "\(p.address.components(separatedBy: "/").first ?? p.address):\(p.port)" : p.wgEndpoint,
                ]],
                "mtu": p.wgMtu,
                "domainStrategy": "ForceIP",
            ]
            if let bytes = reservedBytes(p.wgReserved) { settings["reserved"] = bytes }
            if !p.wgPreSharedKey.isEmpty, var peers = settings["peers"] as? [[String: Any]] {
                peers[0]["preSharedKey"] = p.wgPreSharedKey
                settings["peers"] = peers
            }
            return settings
        case .socks5, .http:
            var server: [String: Any] = ["address": p.address, "port": p.port]
            if p.uuid.contains(":") {
                server["users"] = [["user": p.uuid.components(separatedBy: ":").first ?? "",
                                    "pass": p.uuid.components(separatedBy: ":").dropFirst().joined(separator: ":")]]
            }
            return ["servers": [server]]
        case .tuic:
            return ["server": p.address, "port": p.port, "uuid": p.uuid, "password": p.ssPassword,
                    "congestionControl": "bbr", "udpRelayMode": "native", "zeroRttHandshake": false]
        }
    }

    private static func streamSettings(_ p: VpnProfile) -> [String: Any] {
        var s: [String: Any] = [:]
        let network: String = {
            switch p.transport {
            case .tcp: return "tcp"; case .ws: return "ws"; case .grpc: return "grpc"
            case .xhttp: return "xhttp"; case .h2: return "http"; case .quic: return "quic"
            }
        }()
        s["network"] = network

        switch p.transport {
        case .ws:
            var ws: [String: Any] = ["path": p.path.isEmpty ? "/" : p.path]
            if !p.host.isEmpty { ws["headers"] = ["Host": p.host] }
            s["wsSettings"] = ws
        case .grpc:
            s["grpcSettings"] = ["serviceName": p.path, "multiMode": false]
        case .h2:
            var h: [String: Any] = ["path": p.path.isEmpty ? "/" : p.path]
            if !p.host.isEmpty { h["host"] = [p.host] }
            s["httpSettings"] = h
        case .xhttp:
            s["xhttpSettings"] = ["path": p.path.isEmpty ? "/" : p.path,
                                  "host": p.host.isEmpty ? (p.sni.isEmpty ? p.address : p.sni) : p.host,
                                  "mode": "stream-one"]
        case .tcp, .quic:
            break
        }

        switch p.security {
        case .tls:
            s["security"] = "tls"
            var tls: [String: Any] = ["serverName": p.sni.isEmpty ? p.address : p.sni,
                                      "allowInsecure": p.allowInsecure]
            if !p.fingerprint.isEmpty { tls["fingerprint"] = p.fingerprint }
            s["tlsSettings"] = tls
        case .reality:
            s["security"] = "reality"
            s["realitySettings"] = ["serverName": p.sni.isEmpty ? p.address : p.sni,
                                    "fingerprint": p.fingerprint.isEmpty ? "chrome" : p.fingerprint,
                                    "shortId": p.shortId, "publicKey": p.publicKey, "spiderX": ""]
        case .xtls:
            s["security"] = "tls"
            s["tlsSettings"] = ["serverName": p.sni.isEmpty ? p.address : p.sni, "allowInsecure": p.allowInsecure]
        case .none:
            s["security"] = "none"
        }
        return s
    }

    // ── Routing ────────────────────────────────────────────────────────────────

    private static func routing(_ s: AppSettings) -> [String: Any] {
        let applyDirect = s.routingMode == .ruleBased
        let applyChina = s.routingMode == .ruleBased && s.bypassChina
        var rules: [[String: Any]] = []

        if !s.customBlockedDomains.isEmpty {
            rules.append(["type": "field", "outboundTag": "block", "domain": s.customBlockedDomains])
        }
        if s.blockAds {
            rules.append(["type": "field", "outboundTag": "block", "domain": ["geosite:category-ads-all"]])
        }
        if applyDirect && !s.customDirectDomains.isEmpty {
            rules.append(["type": "field", "outboundTag": "direct", "domain": s.customDirectDomains])
        }
        if applyChina {
            rules.append(["type": "field", "outboundTag": "direct", "domain": ["geosite:cn"]])
            rules.append(["type": "field", "outboundTag": "direct", "ip": ["geoip:cn"]])
        }
        if s.routingMode != .global {
            rules.append(["type": "field", "outboundTag": "direct",
                          "ip": ["geoip:private", "127.0.0.0/8", "10.0.0.0/8",
                                 "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"]])
        }
        rules.append(["type": "field", "outboundTag": "proxy", "network": "tcp,udp"])

        return ["domainStrategy": s.domainStrategy.rawValue, "domainMatcher": "hybrid", "rules": rules]
    }

    private static func reservedBytes(_ raw: String) -> [Int]? {
        let parts = raw.components(separatedBy: CharacterSet(charactersIn: ", "))
            .compactMap { Int($0) }.filter { (0...255).contains($0) }
        return parts.count == 3 ? parts : nil
    }

    private static func optionsObject(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              !object.isEmpty else { return nil }
        return object
    }
}
