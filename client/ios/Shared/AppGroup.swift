import Foundation

/// Identifiers shared between the app and the packet-tunnel extension.
/// Compiled into BOTH targets (see `Shared` in each target's sources in project.yml).
enum AppGroup {
    static let id = "group.io.aetherlinkx.client"
    static let tunnelBundleId = "io.aetherlinkx.client.PacketTunnel"

    static let profilesKey = "profiles"
    static let subscriptionsKey = "subscriptions"
    static let settingsKey = "settings"
    static let activeIdKey = "activeProfileId"
    /// The built intermediate Xray JSON for the active profile — read by the extension on start.
    static let activeConfigKey = "activeConfigJson"

    static var defaults: UserDefaults? { UserDefaults(suiteName: id) }
}
