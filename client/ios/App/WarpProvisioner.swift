import Foundation
import CryptoKit

/// Registers a free Cloudflare WARP account and returns it as a WireGuard profile.
/// iOS generates the X25519 keypair with CryptoKit (no provider quirks like Android).
enum WarpProvisioner {

    private static let regURL = "https://api.cloudflareclient.com/v0a2158/reg"
    private static let clientVersion = "a-6.30-3596"

    static func register() async -> VpnProfile? {
        let priv = Curve25519.KeyAgreement.PrivateKey()
        let privB64 = priv.rawRepresentation.base64EncodedString()
        let pubB64 = priv.publicKey.rawRepresentation.base64EncodedString()

        let tos = ISO8601DateFormatter().string(from: Date())
        let payload: [String: Any] = [
            "key": pubB64, "install_id": "", "fcm_token": "",
            "tos": tos, "model": "PC", "serial_number": "", "locale": "en_US",
        ]
        guard let url = URL(string: regURL),
              let body = try? JSONSerialization.data(withJSONObject: payload) else { return nil }
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.httpBody = body
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue(clientVersion, forHTTPHeaderField: "CF-Client-Version")
        req.setValue("okhttp/4.12.0", forHTTPHeaderField: "User-Agent")

        do {
            let (data, _) = try await URLSession.shared.data(for: req)
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
            // config is at the top level on v0a2158, or under "result" on other versions.
            let config = (root["result"] as? [String: Any])?["config"] as? [String: Any]
                ?? root["config"] as? [String: Any]
            guard let config = config,
                  let peer = (config["peers"] as? [[String: Any]])?.first,
                  let peerKey = peer["public_key"] as? String else { return nil }

            let endpoint = (peer["endpoint"] as? [String: Any])?["host"] as? String
                ?? "engage.cloudflareclient.com:2408"
            let v4 = ((config["interface"] as? [String: Any])?["addresses"] as? [String: Any])?["v4"] as? String ?? "172.16.0.2"
            let reserved = reservedString(config["client_id"] as? String)

            var p = VpnProfile()
            p.name = "Cloudflare WARP"
            p.proto = .wireguard
            p.address = "\(v4)/32"
            p.port = Int(endpoint.components(separatedBy: ":").last ?? "2408") ?? 2408
            p.wgPrivateKey = privB64
            p.wgPeerPublicKey = peerKey
            p.wgEndpoint = endpoint
            p.wgDns = "1.1.1.1"
            p.wgMtu = 1280
            p.wgReserved = reserved
            return p
        } catch {
            return nil
        }
    }

    /// client_id is base64 of three bytes — WARP's WireGuard "reserved".
    private static func reservedString(_ clientId: String?) -> String {
        guard let id = clientId, let data = Data(base64Encoded: id), data.count >= 3 else { return "" }
        return data.prefix(3).map { String(Int($0)) }.joined(separator: ",")
    }
}
