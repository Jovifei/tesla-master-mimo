import SwiftUI

// MARK: - Font Helpers

struct StitchFont {
    static func inter(_ weight: Font.Weight = .regular, size: CGFloat) -> Font {
        let name: String
        switch weight {
        case .medium: name = "Inter-Medium"
        case .semibold: name = "Inter-SemiBold"
        case .bold: name = "Inter-Bold"
        default: name = "Inter-Regular"
        }
        return .custom(name, size: size)
    }

    static func jetBrainsMono(_ weight: Font.Weight = .regular, size: CGFloat) -> Font {
        let name: String
        switch weight {
        case .medium: name = "JetBrainsMono-Medium"
        default: name = "JetBrainsMono-Regular"
        }
        return .custom(name, size: size)
    }
}

struct StitchColors {
    static let primary = Color(red: 0x17/255, green: 0x17/255, blue: 0x17/255)      // #171717
    static let accent = Color(red: 0xA1/255, green: 0x62/255, blue: 0x07/255)       // #A16207
    static let online = Color(red: 0x05/255, green: 0x96/255, blue: 0x69/255)       // #059669
    static let charging = Color(red: 0xF5/255, green: 0x9E/255, blue: 0x0B/255)     // #F59E0B
    static let warning = Color(red: 0xDC/255, green: 0x26/255, blue: 0x26/255)      // #DC2626
    static let outline = Color(red: 0xE5/255, green: 0xE5/255, blue: 0xE5/255)      // #E5E5E5
    static let surface = Color.white
}

enum CarColor: String, CaseIterable {
    case deepBlue, redMultiCoat, pearlWhite, midnightSilver, solidBlack, stealthGrey
    var accent: Color {
        switch self {
        case .deepBlue: return Color(hex: "1E3A8A")
        case .redMultiCoat: return Color(hex: "B91C1C")
        case .pearlWhite, .midnightSilver, .stealthGrey: return Color(hex: "4B5563")
        case .solidBlack: return Color(hex: "18181B")
        }
    }
    static func from(_ colorName: String) -> CarColor {
        switch colorName.lowercased() {
        case "deepblue": return .deepBlue; case "redmulticoat": return .redMultiCoat
        case "pearlwhite": return .pearlWhite; case "midnightsilver": return .midnightSilver
        case "solidblack": return .solidBlack; case "stealthgrey": return .stealthGrey
        default: return .midnightSilver
        }
    }
}

enum StateColor {
    static func forState(_ state: CarState) -> Color {
        switch state { case .online: return StitchColors.online; case .driving: return StitchColors.primary; case .charging: return StitchColors.charging; case .asleep: return .gray; case .offline: return Color(hex: "616161") }
    }
    static func label(_ state: CarState) -> String {
        switch state { case .online: return "Online"; case .driving: return "Driving"; case .charging: return "Charging"; case .asleep: return "Asleep"; case .offline: return "Offline" }
    }
}

extension Color {
    init(hex: String) {
        let s = hex.trimmingCharacters(in: .alphanumerics.inverted)
        let v = UInt64(Int(s, radix: 16) ?? 0)
        self.init(red: Double((v>>16)&0xFF)/255, green: Double((v>>8)&0xFF)/255, blue: Double(v&0xFF)/255)
    }
}
