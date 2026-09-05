import SwiftUI

// MARK: - Semantic Colors (Apple-native, auto-adapts to dark mode)

/// System-aligned semantic colors. Light/dark variants come for free via
/// SwiftUI's Color system. Car-specific accent colors live in CarColorPalette
/// and are injected via Environment.
enum MateColors {
    // Primary accent — iOS system tint defaults to blue, overridden by carPalette.accent
    static let accent = Color.accentColor

    // Semantic status colors (fixed, never change with dark mode)
    static let success  = Color(red: 0x04/255, green: 0x78/255, blue: 0x57/255)  // #047857
    static let warning  = Color(red: 0xB4/255, green: 0x53/255, blue: 0x09/255)  // #B45309
    static let error    = Color(red: 0xDC/255, green: 0x26/255, blue: 0x26/255)  // #DC2626

    // Charger colors
    static let acCharge = Color(red: 0x34/255, green: 0xC7/255, blue: 0x59/255)  // system green
    static let dcCharge = Color(red: 0xF5/255, green: 0x9E/255, blue: 0x0B/255)  // amber

    // Car state colors
    static let online    = Color(red: 0x05/255, green: 0x96/255, blue: 0x69/255) // #059669
    static let driving   = Color.accentColor
    static let charging  = Color(red: 0xF5/255, green: 0x9E/255, blue: 0x0B/255) // amber
    static let asleep    = Color.gray
    static let offline   = Color(red: 0x61/255, green: 0x61/255, blue: 0x61/255)

    // Separator (1px hairline)
    static let separator = Color(.separator)

    // Muted secondary text (Android SwissMuted: #737373 light / #B7BDC6 dark)
    static let muted = Color(UIColor { trait in
        trait.userInterfaceStyle == .dark
            ? UIColor(red: 0xB7/255, green: 0xBD/255, blue: 0xC6/255, alpha: 1)
            : UIColor(red: 0x73/255, green: 0x73/255, blue: 0x73/255, alpha: 1)
    })

    // Card / grouped background
    static let groupedBackground = Color(.systemGroupedBackground)
    static let secondaryGrouped   = Color(.secondarySystemGroupedBackground)
    static let tertiaryGrouped    = Color(.tertiarySystemGroupedBackground)
}

// MARK: - Car State Helpers

extension CarState {
    var color: Color {
        switch self {
        case .online:   return MateColors.online
        case .driving:  return MateColors.driving
        case .charging: return MateColors.charging
        case .asleep:   return MateColors.asleep
        case .offline:  return MateColors.offline
        }
    }

    var sfSymbol: String {
        switch self {
        case .online:   return "wifi"
        case .driving:  return "car.fill"
        case .charging: return "bolt.fill"
        case .asleep:   return "moon.zzz.fill"
        case .offline:  return "power"
        }
    }

    var localizedLabel: String {
        switch self {
        case .online:   return L10n.string("state.online")
        case .driving:  return L10n.string("state.driving")
        case .charging: return L10n.string("state.charging")
        case .asleep:   return L10n.string("state.asleep")
        case .offline:  return L10n.string("state.offline")
        }
    }
}

// MARK: - Typography (Apple-native + Inter/JetBrainsMono for special contexts)

/// Font system aligned with apple-design skill guidelines:
/// - Default to system font (SF Pro) for body/labels (has optical sizing built-in)
/// - Inter for card headings (matching Android's SwissSans)
/// - JetBrainsMono for numeric仪表 displays (matching Android's MetricMono)
enum MateFont {

    // MARK: System (SF Pro) — default for most UI

    static func body(_ size: CGFloat = 17) -> Font { .system(size: size, weight: .regular) }
    static func headline(_ size: CGFloat = 17) -> Font { .system(size: size, weight: .semibold) }
    static func title(_ size: CGFloat = 28) -> Font { .system(size: size, weight: .bold) }
    static func caption(_ size: CGFloat = 12) -> Font { .system(size: size, weight: .regular) }

    // MARK: Inter — card headings, section titles (matches Android SwissSans)

    static func inter(_ weight: Font.Weight = .regular, size: CGFloat = 17) -> Font {
        let name: String
        switch weight {
        case .medium:    name = "Inter-Medium"
        case .semibold:  name = "Inter-SemiBold"
        case .bold:      name = "Inter-Bold"
        default:         name = "Inter-Regular"
        }
        return .custom(name, size: size)
    }

    // MARK: JetBrainsMono — numeric仪表, battery %, speed, power (matches Android MetricMono)

    static func mono(_ weight: Font.Weight = .regular, size: CGFloat = 34) -> Font {
        let name: String
        switch weight {
        case .medium:  name = "JetBrainsMono-Medium"
        default:       name = "JetBrainsMono-Regular"
        }
        return .custom(name, size: size).monospacedDigit()
    }
}

// MARK: - SF Symbols Icon Map (mirrors Android's Material Icons)

/// Centralized SF Symbol names for all app icons, matching Android's CustomIcons.kt
enum MateIcons {
    // Tab bar
    static let dashboard = "car.fill"
    static let drives     = "road.lanes"
    static let charges    = "bolt.fill"
    static let more       = "ellipsis.circle"

    // Car state
    static let online     = "wifi"
    static let driving    = "car.fill"
    static let charging   = "bolt.fill"
    static let asleep     = "moon.zzz.fill"
    static let offline    = "power"

    // Vehicle controls
    static let lock       = "lock.fill"
    static let unlock     = "lock.open.fill"
    static let plug       = "bolt.circle.fill"
    static let climate    = "thermometer.medium"
    static let sentry     = "shield.lefthalf.filled"
    static let door       = "door.left.hand.open"

    // Analytics
    static let battery    = "battery.100.bolt"
    static let mileage    = "map"
    static let timeline   = "clock"
    static let efficiency = "chart.xyaxis.line"
    static let cost       = "dollarsign.circle"
    static let vampire    = "moon.zzz"
    static let range      = "gauge"
    static let heatmap    = "calendar"
    static let destinations = "mappin.circle"
    static let location     = "location.fill"
    static let statistics  = "chart.bar"
    static let trips       = "airplane"
    static let tpms        = "circle.circle"
    static let countries   = "globe"
    static let regions     = "map.fill"

    // Reports
    static let annualReport = "doc.text"
    static let export       = "square.and.arrow.up"
    static let vehicle3d    = "car.2"

    // System
    static let settings     = "gear"
    static let about        = "info.circle"
    static let updates      = "arrow.triangle.2.circlepath"
    static let sentryHistory = "shield.lefthalf.filled"

    // Actions
    static let refresh      = "arrow.clockwise"
    static let filter       = "line.3.horizontal.decrease.circle"
    static let search       = "magnifyingglass"
    static let close        = "xmark.circle.fill"
    static let check        = "checkmark.circle.fill"
    static let chevronRight = "chevron.right"
    static let chevronDown  = "chevron.down"
    static let externalLink = "arrow.up.right.square"
}

// MARK: - Car State Color Helper (backwards compat with legacy code)

/// Backwards-compatible wrapper so existing views using `StateColor.forState()`
/// still compile without changes during the transition.
enum StateColor {
    static func forState(_ state: CarState) -> Color { state.color }
    static func label(_ state: CarState) -> String { state.localizedLabel }
}

// MARK: - Legacy Compat (temporary, migrate views away from these)

/// These will be removed once all views are migrated to MateColors / MateFont.
enum StitchColors {
    static let primary  = Color(.label)
    static let online   = MateColors.online
    static let charging = MateColors.charging
    static let warning  = MateColors.warning
    static let outline  = Color(.separator)
    static let surface  = Color(.systemBackground)
}

struct StitchFont {
    static func inter(_ weight: Font.Weight = .regular, size: CGFloat) -> Font {
        MateFont.inter(weight, size: size)
    }
    static func jetBrainsMono(_ weight: Font.Weight = .regular, size: CGFloat) -> Font {
        MateFont.mono(weight, size: size)
    }
}
