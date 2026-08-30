import Foundation

/// Persists profiles, subscriptions and settings in the shared App Group container, and
/// builds the active Xray config for the extension to run.
final class ProfileStore: ObservableObject {
    @Published var profiles: [VpnProfile] = []
    @Published var subscriptions: [Subscription] = []
    @Published var settings = AppSettings()
    @Published var activeId: String? = nil

    private let d = AppGroup.defaults

    init() { load() }

    func load() {
        if let data = d?.data(forKey: AppGroup.profilesKey),
           let v = try? JSONDecoder().decode([VpnProfile].self, from: data) { profiles = v }
        if let data = d?.data(forKey: AppGroup.subscriptionsKey),
           let v = try? JSONDecoder().decode([Subscription].self, from: data) { subscriptions = v }
        if let data = d?.data(forKey: AppGroup.settingsKey),
           let v = try? JSONDecoder().decode(AppSettings.self, from: data) { settings = v }
        activeId = d?.string(forKey: AppGroup.activeIdKey)
    }

    func save() {
        d?.set(try? JSONEncoder().encode(profiles), forKey: AppGroup.profilesKey)
        d?.set(try? JSONEncoder().encode(subscriptions), forKey: AppGroup.subscriptionsKey)
        d?.set(try? JSONEncoder().encode(settings), forKey: AppGroup.settingsKey)
        d?.set(activeId, forKey: AppGroup.activeIdKey)
    }

    var activeProfile: VpnProfile? { profiles.first { $0.id == activeId } }

    func add(_ p: VpnProfile) {
        profiles.append(p)
        if activeId == nil { activeId = p.id }
        save()
    }

    func remove(_ p: VpnProfile) {
        profiles.removeAll { $0.id == p.id }
        if activeId == p.id { activeId = profiles.first?.id }
        save()
    }

    func setActive(_ p: VpnProfile) { activeId = p.id; save() }

    func update(_ p: VpnProfile) {
        if let i = profiles.firstIndex(where: { $0.id == p.id }) { profiles[i] = p; save() }
    }

    func duplicate(_ p: VpnProfile) {
        var copy = p
        copy.id = UUID().uuidString
        copy.name = p.name + " Copy"
        copy.subscriptionId = nil
        copy.latencyMs = -1
        profiles.append(copy)
        save()
    }

    func setLatency(_ id: String, _ ms: Int) {
        if let i = profiles.firstIndex(where: { $0.id == id }) { profiles[i].latencyMs = ms; save() }
    }

    // ── Subscriptions ──────────────────────────────────────────────────────────

    func addSubscription(_ sub: Subscription) {
        subscriptions.append(sub)
        save()
    }

    func removeSubscription(_ sub: Subscription) {
        subscriptions.removeAll { $0.id == sub.id }
        profiles.removeAll { $0.subscriptionId == sub.id }
        if activeProfile == nil { activeId = profiles.first?.id }
        save()
    }

    /// Re-fetch a subscription: replace its profiles, refresh usage/expiry.
    func refresh(_ sub: Subscription) async {
        let result = await SubscriptionService.fetch(sub, settings: settings)
        guard !result.profiles.isEmpty else { return }
        await MainActor.run {
            // keep the active profile's id stable if it still exists by fingerprint
            profiles.removeAll { $0.subscriptionId == sub.id }
            let fresh = result.profiles.map { p -> VpnProfile in var c = p; c.subscriptionId = sub.id; return c }
            profiles.append(contentsOf: fresh)
            if let i = subscriptions.firstIndex(where: { $0.id == sub.id }) {
                subscriptions[i].profileCount = fresh.count
                subscriptions[i].lastUpdated = Date().timeIntervalSince1970
                if let u = result.usage {
                    subscriptions[i].uploadBytes = u.upload; subscriptions[i].downloadBytes = u.download
                    subscriptions[i].totalBytes = u.total; subscriptions[i].expireEpochSec = u.expire
                }
            }
            if activeProfile == nil { activeId = profiles.first?.id }
            save()
        }
    }

    func updateAll() async {
        for sub in subscriptions { await refresh(sub) }
    }

    /// Build the active profile's Xray config and write it where the extension reads it.
    /// Returns false if there is no active profile.
    @discardableResult
    func writeActiveConfig() -> Bool {
        guard let p = activeProfile else { return false }
        d?.set(XrayConfigBuilder.build(p, settings), forKey: AppGroup.activeConfigKey)
        return true
    }
}
