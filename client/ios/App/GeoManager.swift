import Foundation

/// Downloads custom geoip.dat / geosite.dat into the shared App Group container so the
/// tunnel extension can read them instead of the bundled copies. Mirrors the Android
/// "update geo files" action.
enum GeoManager {
    static var sharedGeoDir: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: AppGroup.id)?
            .appendingPathComponent("geo", isDirectory: true)
    }

    /// Both URLs must be set (the tunnel needs both files in one directory).
    static func update(_ settings: AppSettings) async -> Bool {
        guard !settings.geoipUrl.isEmpty, !settings.geositeUrl.isEmpty,
              let dir = sharedGeoDir else { return false }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try await download(settings.geoipUrl, to: dir.appendingPathComponent("geoip.dat"))
            try await download(settings.geositeUrl, to: dir.appendingPathComponent("geosite.dat"))
            return true
        } catch {
            return false
        }
    }

    private static func download(_ urlString: String, to dest: URL) async throws {
        guard let url = URL(string: urlString) else { throw URLError(.badURL) }
        let (data, resp) = try await URLSession.shared.data(from: url)
        guard (resp as? HTTPURLResponse)?.statusCode == 200, !data.isEmpty else {
            throw URLError(.badServerResponse)
        }
        try data.write(to: dest, options: .atomic)
    }
}
