import Foundation

/// Decodes proxy share links into `VpnProfile`. Mirrors the Android ProfileCodec for the
/// protocols Xray supports on iOS.
enum ProfileCodec {

    static func decode(_ raw: String) -> VpnProfile? {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let scheme = s.components(separatedBy: "://").first?.lowercased() else { return nil }
        switch scheme {
        case "aetherlinkx": return decodeAetherLinkX(s)
        case "vmess":       return decodeVMess(s)
        case "vless":       return decodeURI(s, .vless)
        case "trojan":      return decodeURI(s, .trojan)
        case "ss":          return decodeShadowsocks(s)
        case "hysteria2", "hy2": return decodeHysteria2(s)
        case "wireguard", "wg":  return decodeWireguard(s)
        case "socks5", "socks":  return decodeUserHost(s, .socks5, defaultPort: 1080)
        case "tuic":        return decodeTuic(s)
        case "anytls":      return decodeURI(s, .anytls)
        case "xhttp":       return decodeXhttp(s)
        default:            return nil
        }
    }

    /// Decode a subscription body (newline list, possibly base64-wrapped).
    static func decodeSubscriptionBody(_ body: String) -> [VpnProfile] {
        let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
        if let decoded = base64Decode(trimmed) {
            let profiles = decoded.split(separator: "\n").compactMap { decode(String($0)) }
            if !profiles.isEmpty { return profiles }
        }
        return trimmed.split(separator: "\n").compactMap { decode(String($0)) }
    }

    // ── Encode (share link) ──────────────────────────────────────────────────

    static func encodeNative(_ p: VpnProfile) -> String {
        let frag = "#" + (p.name.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? p.name)
        switch p.proto {
        case .aetherlinkx:
            var q = "secret=\(enc(p.alxSecret))&allowInsecureTransport=\(p.alxAllowInsecureTransport ? "1" : "0")"
            q += "&type=\(p.transport.rawValue)&security=\(p.security.rawValue)&path=\(enc(p.path))&host=\(enc(p.host))&sni=\(enc(p.sni))&fp=\(enc(p.fingerprint))"
            q += "&turbo=\(encodeOptions(p.alxTurboJson))&alxSecurity=\(encodeOptions(p.alxSecurityJson))&stealth=\(encodeOptions(p.alxStealthJson))"
            if p.security == .reality { q += "&pbk=\(enc(p.publicKey))&sid=\(enc(p.shortId))" }
            return "aetherlinkx://\(p.uuid)@\(p.address):\(p.port)?\(q)\(frag)"
        case .vmess:
            let net: String = ["ws": "ws", "grpc": "grpc", "h2": "h2", "quic": "quic", "xhttp": "xhttp"][p.transport.rawValue] ?? "tcp"
            let tls = p.security == .none ? "" : p.security.rawValue
            let json: [String: Any] = [
                "v": "2", "ps": p.name, "add": p.address, "port": "\(p.port)", "id": p.uuid,
                "aid": "0", "scy": p.vmessSecurity, "net": net, "path": p.path, "host": p.host,
                "tls": tls, "sni": p.sni,
            ]
            let data = (try? JSONSerialization.data(withJSONObject: json)) ?? Data()
            return "vmess://" + data.base64EncodedString()
        case .vless, .anytls:
            var q = "type=\(p.transport.rawValue)&security=\(p.security.rawValue)&path=\(enc(p.path))&host=\(enc(p.host))&sni=\(enc(p.sni))&fp=\(p.fingerprint)"
            if p.security == .reality { q += "&pbk=\(p.publicKey)&sid=\(p.shortId)" }
            return "\(p.proto == .vless ? "vless" : "anytls")://\(p.uuid)@\(p.address):\(p.port)?\(q)\(frag)"
        case .trojan:
            return "trojan://\(p.uuid)@\(p.address):\(p.port)?security=\(p.security.rawValue)&sni=\(enc(p.sni))\(frag)"
        case .shadowsocks:
            let userInfo = Data("\(p.ssMethod):\(p.ssPassword)".utf8).base64EncodedString()
            return "ss://\(userInfo)@\(p.address):\(p.port)\(frag)"
        case .hysteria2:
            return "hysteria2://\(p.hystPassword)@\(p.address):\(p.port)?sni=\(enc(p.sni))\(frag)"
        case .tuic:
            return "tuic://\(p.uuid):\(p.ssPassword)@\(p.address):\(p.port)?sni=\(enc(p.sni))\(frag)"
        case .socks5:
            return "socks5://\(p.uuid)@\(p.address):\(p.port)\(frag)"
        case .http:
            return "httpproxy://\(p.uuid)@\(p.address):\(p.port)\(frag)"
        case .wireguard:
            return "wireguard://\(p.address)?publickey=\(enc(p.wgPeerPublicKey))&privatekey=\(enc(p.wgPrivateKey))&endpoint=\(enc(p.wgEndpoint))&reserved=\(enc(p.wgReserved))\(frag)"
        }
    }

    private static func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }

    // ── Per-protocol decoders ────────────────────────────────────────────────

    private static func decodeAetherLinkX(_ raw: String) -> VpnProfile? {
        guard let c = URLComponents(string: raw) else { return nil }
        let q = params(c)
        var p = VpnProfile()
        p.name = c.fragment?.removingPercentEncoding ?? "AetherLink X"
        p.proto = .aetherlinkx
        p.address = c.host ?? ""
        p.port = c.port ?? 443
        p.uuid = c.user ?? ""
        let type = (q["type"] ?? "tcp").lowercased()
        p.transport = ["raw": .tcp, "http": .h2, "splithttp": .xhttp][type]
            ?? Transport(rawValue: type) ?? .tcp
        p.path = q["path"] ?? "/"
        p.host = q["host"] ?? ""
        p.security = securityFrom(q["security"], default: .none)
        p.sni = q["sni"] ?? ""
        p.fingerprint = q["fp"].flatMap { $0.isEmpty ? nil : $0 } ?? "chrome"
        p.publicKey = q["pbk"] ?? q["password"] ?? ""
        p.shortId = q["sid"] ?? ""
        p.alxSecret = q["secret"] ?? ""
        p.alxAllowInsecureTransport = q["allowInsecureTransport"] == "1" || q["allowInsecureTransport"] == "true"
        p.alxTurboJson = decodeOptions(q["turbo"])
        p.alxSecurityJson = decodeOptions(q["alxSecurity"])
        p.alxStealthJson = decodeOptions(q["stealth"])
        return p
    }

    private static func decodeVMess(_ raw: String) -> VpnProfile? {
        let b64 = String(raw.dropFirst("vmess://".count))
        guard let data = base64DecodeData(b64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        func str(_ k: String) -> String { (json[k] as? String) ?? (json[k].map { "\($0)" } ?? "") }
        let net = str("net")
        let transport: Transport = [
            "ws": .ws, "grpc": .grpc, "h2": .h2, "quic": .quic, "xhttp": .xhttp
        ][net] ?? .tcp
        let security: Security = ["tls": .tls, "xtls": .xtls, "reality": .reality][str("tls")] ?? .none
        var p = VpnProfile()
        p.name = str("ps").isEmpty ? "VMess" : str("ps")
        p.proto = .vmess
        p.address = str("add")
        p.port = Int(str("port")) ?? 443
        p.uuid = str("id")
        p.transport = transport
        p.path = str("path").isEmpty ? "/" : str("path")
        p.host = str("host")
        p.security = security
        p.sni = str("sni")
        p.vmessSecurity = str("scy").isEmpty ? "auto" : str("scy")
        return p
    }

    private static func decodeURI(_ raw: String, _ proto: ProxyProtocol) -> VpnProfile? {
        guard let c = URLComponents(string: raw) else { return nil }
        let q = params(c)
        var p = VpnProfile()
        p.name = (c.fragment?.removingPercentEncoding) ?? proto.rawValue.uppercased()
        p.proto = proto
        p.address = c.host ?? ""
        p.port = c.port ?? 443
        p.uuid = c.user ?? ""
        p.transport = Transport(rawValue: (q["type"] ?? "tcp").lowercased()) ?? .tcp
        p.path = q["path"] ?? "/"
        p.host = q["host"] ?? ""
        p.security = securityFrom(q["security"], default: proto == .trojan || proto == .anytls ? .tls : .none)
        p.sni = q["sni"] ?? ""
        p.fingerprint = q["fp"].flatMap { $0.isEmpty ? nil : $0 } ?? "chrome"
        p.publicKey = q["pbk"] ?? ""
        p.shortId = q["sid"] ?? ""
        p.allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1"
        return p
    }

    private static func decodeXhttp(_ raw: String) -> VpnProfile? {
        guard var p = decodeURI(raw.replacingOccurrences(of: "xhttp://", with: "vless://"), .vless) else { return nil }
        p.transport = .xhttp
        if p.name == "VLESS" { p.name = "XHTTP" }
        return p
    }

    private static func decodeShadowsocks(_ raw: String) -> VpnProfile? {
        let fragment = raw.components(separatedBy: "#").count > 1
            ? (raw.components(separatedBy: "#").last?.removingPercentEncoding ?? "Shadowsocks") : "Shadowsocks"
        let body = String(raw.dropFirst("ss://".count)).components(separatedBy: "#").first ?? ""
        var userInfo = "", hostPort = ""
        if body.contains("@") {
            let parts = body.components(separatedBy: "@")
            userInfo = base64Decode(parts[0]) ?? parts[0]
            hostPort = parts.dropFirst().joined(separator: "@")
        } else if let decoded = base64Decode(body.components(separatedBy: "?").first ?? body) {
            let parts = decoded.components(separatedBy: "@")
            userInfo = parts.first ?? ""
            hostPort = parts.dropFirst().joined(separator: "@")
        }
        let method = userInfo.components(separatedBy: ":").first ?? ""
        let pwd = userInfo.components(separatedBy: ":").dropFirst().joined(separator: ":")
        let host = hostPort.components(separatedBy: ":").first ?? ""
        let port = Int(hostPort.components(separatedBy: ":").last ?? "") ?? 8388
        var p = VpnProfile()
        p.name = fragment; p.proto = .shadowsocks
        p.address = host; p.port = port
        p.ssMethod = method; p.ssPassword = pwd
        p.security = .none; p.transport = .tcp
        return p
    }

    private static func decodeHysteria2(_ raw: String) -> VpnProfile? {
        guard let c = URLComponents(string: raw) else { return nil }
        let q = params(c)
        var p = VpnProfile()
        p.name = c.fragment?.removingPercentEncoding ?? "Hysteria2"
        p.proto = .hysteria2
        p.address = c.host ?? ""; p.port = c.port ?? 443
        p.hystPassword = c.user ?? ""
        p.sni = q["sni"] ?? ""
        p.hystObfs = q["obfs"] ?? ""
        p.hystObfsPassword = q["obfs-password"] ?? ""
        return p
    }

    private static func decodeWireguard(_ raw: String) -> VpnProfile? {
        guard let c = URLComponents(string: raw) else { return nil }
        let q = params(c)
        var p = VpnProfile()
        p.name = c.fragment?.removingPercentEncoding ?? "WireGuard"
        p.proto = .wireguard
        p.address = q["address"] ?? q["localaddress"] ?? "10.0.0.2/32"
        p.port = c.port ?? 51820
        p.wgPrivateKey = q["privatekey"] ?? ""
        p.wgPeerPublicKey = q["publickey"] ?? ""
        p.wgPreSharedKey = q["presharedkey"] ?? ""
        p.wgEndpoint = q["endpoint"] ?? (c.host.map { "\($0):\(c.port ?? 51820)" } ?? "")
        p.wgDns = q["dns"] ?? "1.1.1.1"
        p.wgMtu = Int(q["mtu"] ?? "") ?? 1280
        p.wgReserved = normalizeReserved(q["reserved"] ?? "")
        return p
    }

    private static func decodeTuic(_ raw: String) -> VpnProfile? {
        guard let c = URLComponents(string: raw) else { return nil }
        let q = params(c)
        var p = VpnProfile()
        p.name = c.fragment?.removingPercentEncoding ?? "TUIC"
        p.proto = .tuic
        p.address = c.host ?? ""; p.port = c.port ?? 443
        p.uuid = c.user?.components(separatedBy: ":").first ?? ""
        let pwd = c.user?.components(separatedBy: ":").dropFirst().joined(separator: ":") ?? ""
        p.ssPassword = pwd.isEmpty ? (q["password"] ?? "") : pwd
        p.security = .tls
        p.sni = q["sni"] ?? ""
        return p
    }

    private static func decodeUserHost(_ raw: String, _ proto: ProxyProtocol, defaultPort: Int) -> VpnProfile? {
        guard let c = URLComponents(string: raw) else { return nil }
        var p = VpnProfile()
        p.name = c.fragment?.removingPercentEncoding ?? proto.rawValue.uppercased()
        p.proto = proto
        p.address = c.host ?? ""; p.port = c.port ?? defaultPort
        p.uuid = c.user ?? ""
        return p
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static func params(_ c: URLComponents) -> [String: String] {
        var d: [String: String] = [:]
        c.queryItems?.forEach { d[$0.name] = $0.value ?? "" }
        return d
    }

    private static func securityFrom(_ s: String?, default def: Security) -> Security {
        switch s?.lowercased() {
        case "tls": return .tls
        case "reality": return .reality
        case "xtls": return .xtls
        case "none": return .none
        default: return def
        }
    }

    private static func normalizeReserved(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespaces)
        if t.isEmpty { return "" }
        if t.contains(",") || t.contains(" ") {
            let ints = t.components(separatedBy: CharacterSet(charactersIn: ", "))
                .compactMap { Int($0) }.filter { (0...255).contains($0) }
            return ints.count == 3 ? ints.map(String.init).joined(separator: ",") : ""
        }
        if let data = base64DecodeData(t), data.count == 3 {
            return data.map { String(Int($0)) }.joined(separator: ",")
        }
        return ""
    }

    private static func base64Decode(_ s: String) -> String? {
        base64DecodeData(s).flatMap { String(data: $0, encoding: .utf8) }
    }

    private static func base64DecodeData(_ s: String) -> Data? {
        var str = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let rem = str.count % 4
        if rem > 0 { str += String(repeating: "=", count: 4 - rem) }
        return Data(base64Encoded: str)
    }

    private static func decodeOptions(_ encoded: String?) -> String {
        guard let encoded, let data = base64DecodeData(encoded),
              let object = try? JSONSerialization.jsonObject(with: data),
              let normalized = try? JSONSerialization.data(withJSONObject: object),
              let result = String(data: normalized, encoding: .utf8) else { return "{}" }
        return result
    }

    private static func encodeOptions(_ json: String) -> String {
        let data = json.data(using: .utf8) ?? Data("{}".utf8)
        return data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
