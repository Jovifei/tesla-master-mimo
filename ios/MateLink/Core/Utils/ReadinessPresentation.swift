import Foundation
import SwiftUI

/// Presentation mapping for data readiness items.
/// Mirrors Android `ReadinessPresentation.kt` exactly.
enum ReadinessItemStatus: Equatable {
    case available
    case collecting
    case waitingVehicle
    case unsupported
    case unknown

    init(_ raw: String) {
        switch raw.trimmingCharacters(in: .whitespaces).lowercased() {
        case "available": self = .available
        case "collecting": self = .collecting
        case "waiting_vehicle": self = .waitingVehicle
        case "unsupported": self = .unsupported
        default: self = .unknown
        }
    }

    var localizedLabel: String {
        switch self {
        case .available: return L10n.string("data_readiness_status_available")
        case .collecting: return L10n.string("data_readiness_status_collecting")
        case .waitingVehicle: return L10n.string("data_readiness_status_waiting_vehicle")
        case .unsupported: return L10n.string("data_readiness_status_unsupported")
        case .unknown: return L10n.string("data_readiness_status_unavailable")
        }
    }

    var color: Color {
        switch self {
        case .available: return MateColors.success
        case .collecting: return MateColors.charging
        case .waitingVehicle: return MateColors.warning
        case .unsupported, .unknown: return MateColors.muted
        }
    }
}

/// Ordered readiness keys displayed on the status page and dashboard intro
/// (mirrors Android `readinessKeys`).
enum ReadinessKeys {
    static let all = ["live_status", "location", "tpms", "drives", "charges", "battery_health"]

    static func titleKey(for key: String) -> String {
        switch key {
        case "live_status": return "data_readiness_item_live_status"
        case "location": return "data_readiness_item_location"
        case "tpms": return "data_readiness_item_tpms"
        case "drives": return "data_readiness_item_drives"
        case "charges": return "data_readiness_item_charges"
        case "battery_health": return "data_readiness_item_battery_health"
        default: return "data_readiness_title"
        }
    }
}

/// Action hint shown under a readiness row (mirrors Android `actionResFor`).
enum ReadinessActionHint {
    case wakeVehicle
    case keepVehicleConnected
    case notAvailable
    case retryLater
    case legacy

    static func forItem(_ item: DataReadinessItem) -> ReadinessActionHint? {
        switch item.action {
        case "wake_vehicle": return .wakeVehicle
        case "keep_vehicle_connected": return .keepVehicleConnected
        case "not_available": return .notAvailable
        case "retry_later": return .retryLater
        case "none":
            return item.messageKey == "data_readiness_legacy_compatibility" ? .legacy : nil
        default: return nil
        }
    }

    var localizedLabel: String {
        switch self {
        case .wakeVehicle: return L10n.string("data_readiness_action_wake_vehicle")
        case .keepVehicleConnected: return L10n.string("data_readiness_action_keep_vehicle_connected")
        case .notAvailable: return L10n.string("data_readiness_action_not_available")
        case .retryLater: return L10n.string("data_readiness_action_retry_later")
        case .legacy: return L10n.string("data_readiness_action_legacy")
        }
    }
}

/// Source label mapping (mirrors Android `readinessSourceLabelRes`).
func readinessSourceLabel(_ source: String) -> String {
    switch source.trimmingCharacters(in: .whitespaces).lowercased() {
    case "fleet_api": return L10n.string("data_readiness_source_fleet_api")
    case "mock_fixture": return L10n.string("data_readiness_source_mock")
    case "legacy_compatibility": return L10n.string("data_readiness_source_legacy")
    case "local_history": return L10n.string("data_readiness_source_local_history")
    default: return L10n.string("data_readiness_source_unavailable")
    }
}

/// Compact "status · last observed" value used by dashboard cards when the
/// underlying metric is missing (mirrors Android `readinessDashboardValue`).
func readinessDashboardValue(_ item: DataReadinessItem?) -> String {
    guard let item else { return L10n.string("data_readiness_status_waiting_vehicle") }
    let status = ReadinessItemStatus(item.status).localizedLabel
    guard let observed = item.lastObservedAt, !observed.isEmpty else { return status }
    return status + " · " + L10n.format("data_readiness_last_observed", observed)
}
