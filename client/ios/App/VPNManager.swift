import Foundation
import NetworkExtension

/// Installs and controls the packet-tunnel via NETunnelProviderManager, and publishes
/// the live connection status to the UI.
@MainActor
final class VPNManager: ObservableObject {
    @Published var status: NEVPNStatus = .invalid
    private var manager: NETunnelProviderManager?

    init() {
        NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: nil, queue: .main
        ) { [weak self] note in
            if let conn = note.object as? NEVPNConnection {
                Task { @MainActor in self?.status = conn.status }
            }
        }
        Task { await load() }
    }

    func load() async {
        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = managers.first
        status = manager?.connection.status ?? .disconnected
    }

    private func makeManager() -> NETunnelProviderManager {
        let m = NETunnelProviderManager()
        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = AppGroup.tunnelBundleId
        proto.serverAddress = "AetherLink X"
        m.protocolConfiguration = proto
        m.localizedDescription = "AetherLink X"
        m.isEnabled = true
        return m
    }

    func connect() async throws {
        let m = manager ?? makeManager()
        m.isEnabled = true
        // NE requires save → reload before the configuration can be started.
        try await m.saveToPreferences()
        try await m.loadFromPreferences()
        manager = m
        try m.connection.startVPNTunnel()
    }

    func disconnect() {
        manager?.connection.stopVPNTunnel()
    }

    var isConnected: Bool { status == .connected }
    var isBusy: Bool {
        status == .connecting || status == .disconnecting || status == .reasserting
    }
}
