import SwiftUI

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
        switch state { case .online: return .green; case .driving: return .blue; case .charging: return .orange; case .asleep: return .gray; case .offline: return Color(hex: "616161") }
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
