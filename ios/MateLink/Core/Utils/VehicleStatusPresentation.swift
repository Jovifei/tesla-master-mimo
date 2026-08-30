import Foundation

/// Mirrors Android's `VehicleStatusPresentation.kt` — vehicle opening detection.
enum VehicleOpening: String, CaseIterable {
    case doors, windows, frunk, trunk

    var label: String {
        switch self {
        case .doors: return "Doors"
        case .windows: return "Windows"
        case .frunk: return "Frunk"
        case .trunk: return "Trunk"
        }
    }
}

enum VehicleStatusPresentation {
    static func openVehicleOpenings(_ status: CarStatus) -> Set<VehicleOpening> {
        var openings = Set<VehicleOpening>()
        if status.doorsOpen { openings.insert(.doors) }
        if status.windowsOpen { openings.insert(.windows) }
        if status.frunkOpen { openings.insert(.frunk) }
        if status.trunkOpen { openings.insert(.trunk) }
        return openings
    }

    static func shouldShowOpeningPanel(_ status: CarStatus) -> Bool {
        !openVehicleOpenings(status).isEmpty
    }

    static func openingWarningText(_ status: CarStatus) -> String {
        let labels = openVehicleOpenings(status).map(\.label)
        guard !labels.isEmpty else { return "" }
        return "\(labels.joined(separator: ", ")) open — please check the vehicle"
    }
}
