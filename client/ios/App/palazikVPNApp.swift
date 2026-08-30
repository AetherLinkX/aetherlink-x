import SwiftUI

@main
struct palazikVPNApp: App {
    @StateObject private var store = ProfileStore()
    @StateObject private var vpn = VPNManager()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(vpn)
                .tint(theme.accent)
                .preferredColorScheme(resolvedScheme)
        }
    }

    private var theme: AppTheme { AppTheme(rawValue: store.settings.appTheme) ?? .cyber }

    private var resolvedScheme: ColorScheme? {
        if theme == .amoled { return .dark }
        return (DarkMode(rawValue: store.settings.darkMode) ?? .system).colorScheme
    }
}
