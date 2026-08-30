import NetworkExtension
import SwiftyXrayKit

/// Runs Xray-core inside the packet tunnel. SwiftyXrayKit's XrayBridge wires the
/// NEPacketTunnelFlow directly to Xray's gVisor TUN inbound — no SOCKS5/tun2socks layer.
class PacketTunnelProvider: NEPacketTunnelProvider {

    private var bridge: XrayBridge?

    override func startTunnel(options: [String: NSObject]?,
                              completionHandler: @escaping (Error?) -> Void) {
        guard let json = AppGroup.defaults?.string(forKey: AppGroup.activeConfigKey),
              !json.isEmpty else {
            completionHandler(error("No active profile"))
            return
        }

        setTunnelNetworkSettings(makeSettings()) { [weak self] err in
            guard let self = self else { return }
            if let err = err { completionHandler(err); return }
            do {
                let bridge = XrayBridge(packetFlow: self.packetFlow)
                // The app builds the intermediate config (outbounds + routing + dns); the
                // kit injects the TUN inbound.
                try bridge.start(
                    config: .json(json),
                    dataDir: self.geoDir(),
                    finalConfigPath: self.finalConfigURL()
                )
                self.bridge = bridge
                completionHandler(nil)
            } catch {
                completionHandler(error)
            }
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason,
                             completionHandler: @escaping () -> Void) {
        bridge?.stop()
        bridge = nil
        completionHandler()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private func makeSettings() -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4 = NEIPv4Settings(addresses: ["198.18.0.1"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4
        settings.dnsSettings = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8"])
        settings.mtu = 1500
        return settings
    }

    /// Prefer user-downloaded geo files in the shared container; fall back to the geo
    /// files bundled in this extension's Resources.
    private func geoDir() -> URL {
        let fm = FileManager.default
        if let shared = fm.containerURL(forSecurityApplicationGroupIdentifier: AppGroup.id)?
            .appendingPathComponent("geo", isDirectory: true),
           fm.fileExists(atPath: shared.appendingPathComponent("geoip.dat").path),
           fm.fileExists(atPath: shared.appendingPathComponent("geosite.dat").path) {
            return shared
        }
        return Bundle.main.resourceURL ?? Bundle.main.bundleURL
    }

    private func finalConfigURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("xray-config.json")
    }

    private func error(_ message: String) -> NSError {
        NSError(domain: "com.palazik.vpn", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
