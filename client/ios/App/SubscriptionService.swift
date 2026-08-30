import Foundation

/// Fetches a subscription URL and parses both the profile list and the optional
/// `Subscription-Userinfo` usage header. Mirrors the Android ProfileRepository fetch.
enum SubscriptionService {

    struct Usage { let upload: Int64; let download: Int64; let total: Int64; let expire: Int64 }
    struct Result { let profiles: [VpnProfile]; let usage: Usage? }

    static func fetch(_ sub: Subscription, settings: AppSettings) async -> Result {
        guard let url = URL(string: sub.url) else { return Result(profiles: [], usage: nil) }
        var req = URLRequest(url: url)
        req.setValue(settings.subscriptionUserAgent, forHTTPHeaderField: "User-Agent")
        do {
            let (data, response) = try await URLSession.shared.data(for: req)
            let body = String(data: data, encoding: .utf8) ?? ""
            let profiles = ProfileCodec.decodeSubscriptionBody(body)
            let header = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Subscription-Userinfo")
            return Result(profiles: profiles, usage: parseUsage(header))
        } catch {
            return Result(profiles: [], usage: nil)
        }
    }

    /// Parse "upload=…; download=…; total=…; expire=…" (bytes; expire in epoch seconds).
    private static func parseUsage(_ raw: String?) -> Usage? {
        guard let raw = raw, !raw.isEmpty else { return nil }
        var map: [String: Int64] = [:]
        for part in raw.split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1)
            if kv.count == 2, let v = Int64(kv[1].trimmingCharacters(in: .whitespaces)) {
                map[kv[0].trimmingCharacters(in: .whitespaces).lowercased()] = v
            }
        }
        if map.isEmpty { return nil }
        return Usage(upload: map["upload"] ?? -1, download: map["download"] ?? -1,
                     total: map["total"] ?? -1, expire: map["expire"] ?? -1)
    }
}
