import SwiftUI

/// Color themes (mirrors the Android AppTheme palettes). On iOS a theme drives the app
/// accent/tint; AMOLED additionally forces a dark scheme.
enum AppTheme: String, CaseIterable, Identifiable {
    case cyber, ocean, forest, sunset, rose, violet, amoled, system
    var id: String { rawValue }

    /// App tint used for controls.
    var accent: Color {
        switch self {
        case .cyber:  return Color(red: 0.00, green: 0.90, blue: 1.00)
        case .ocean:  return Color(red: 0.00, green: 0.47, blue: 0.71)
        case .forest: return Color(red: 0.18, green: 0.42, blue: 0.31)
        case .sunset: return Color(red: 1.00, green: 0.48, blue: 0.10)
        case .rose:   return Color(red: 0.70, green: 0.00, blue: 0.36)
        case .violet: return Color(red: 0.42, green: 0.11, blue: 0.60)
        // AMOLED forces a pure-black scheme; a light accent stays readable on black.
        case .amoled: return Color(white: 0.85)
        case .system: return .blue
        }
    }

    /// Color shown in the picker swatch (AMOLED reads as black, matching its name).
    var swatch: Color {
        self == .amoled ? .black : accent
    }

    var label: String {
        switch self {
        case .cyber: return "Cyber"; case .ocean: return "Ocean"; case .forest: return "Forest"
        case .sunset: return "Sunset"; case .rose: return "Rose"; case .violet: return "Violet"
        case .amoled: return "AMOLED"; case .system: return "System"
        }
    }
}

enum DarkMode: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var label: String { rawValue.capitalized }
    var colorScheme: ColorScheme? {
        switch self { case .system: return nil; case .light: return .light; case .dark: return .dark }
    }
}
