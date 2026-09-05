import Foundation

/// Presentation state machine for Fleet Telemetry pairing.
/// Mirrors Android `TelemetryPairingPresentation.kt` exactly.
enum TelemetrySetupPresentation: Equatable {
    case pairingRequired
    case waitingVehicle
    case permissionRequired
    case billingBlocked
    case telemetryError
    case telemetryNotConfigured
    case collecting
    case available

    var localizedLabel: String {
        switch self {
        case .pairingRequired: return L10n.string("telemetry_setup_pairing_required")
        case .waitingVehicle: return L10n.string("telemetry_setup_waiting_vehicle")
        case .permissionRequired: return L10n.string("telemetry_setup_permission_required")
        case .billingBlocked: return L10n.string("telemetry_setup_billing_blocked")
        case .telemetryError: return L10n.string("telemetry_setup_error")
        case .telemetryNotConfigured: return L10n.string("telemetry_setup_not_configured")
        case .collecting: return L10n.string("telemetry_setup_collecting")
        case .available: return L10n.string("telemetry_setup_available")
        }
    }
}

enum TelemetryConfigSyncPresentation: Equatable {
    case synced
    case pending
    case unknown

    var localizedLabel: String {
        switch self {
        case .synced: return L10n.string("telemetry_config_synced")
        case .pending: return L10n.string("telemetry_config_pending")
        case .unknown: return L10n.string("telemetry_config_unknown")
        }
    }
}

enum TelemetryConfigureActionPresentation: Equatable {
    case none
    case configure
}

func telemetrySetupPresentation(_ status: String?, configSynced: Bool?) -> TelemetrySetupPresentation {
    let normalized = status?.trimmingCharacters(in: .whitespaces).lowercased()
    switch normalized {
    case "pairing_required": return .pairingRequired
    case "permission_required": return .permissionRequired
    case "billing_blocked": return .billingBlocked
    case "telemetry_not_configured": return .telemetryNotConfigured
    case "telemetry_error": return .telemetryError
    case "waiting_vehicle": return .waitingVehicle
    default:
        if configSynced == false { return .waitingVehicle }
        if normalized == "collecting" { return .collecting }
        if normalized == "available" && configSynced == true { return .available }
        if normalized == "available" { return .waitingVehicle }
        return .telemetryError
    }
}

func telemetryConfigSyncPresentation(_ configSynced: Bool?) -> TelemetryConfigSyncPresentation {
    switch configSynced {
    case true: return .synced
    case false: return .pending
    case nil: return .unknown
    }
}

/// Configuration remains an explicit user action after a pending status or polling timeout.
func telemetryConfigureActionPresentation(_ status: String?, configSynced: Bool?) -> TelemetryConfigureActionPresentation {
    if configSynced == true { return .none }
    switch status?.trimmingCharacters(in: .whitespaces).lowercased() {
    case "pairing_required", "waiting_vehicle", "collecting", "available":
        return .configure
    default:
        return .none
    }
}

/// Accept the exact Tesla virtual-key URL shape and reject redirects or alternate hosts.
/// Mirrors Android `officialTeslaVirtualKeyUrlOrNull`.
func officialTeslaVirtualKeyUrlOrNull(_ candidate: String?) -> String? {
    guard let raw = candidate?.trimmingCharacters(in: .whitespaces), !raw.isEmpty,
          let url = URL(string: raw), let host = url.host?.lowercased() else { return nil }

    guard url.scheme?.lowercased() == "https" else { return nil }
    guard ["tesla.com", "www.tesla.com"].contains(host) else { return nil }
    guard url.user == nil, url.port == nil, url.query == nil, url.fragment == nil else { return nil }

    let path = url.path
    // ^/_ak/[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$
    let pattern = "^/_ak/[A-Za-z0-9](?:[A-Za-z0-9.\\-]{0,251}[A-Za-z0-9])?$"
    guard path.range(of: pattern, options: .regularExpression) != nil else { return nil }
    return url.absoluteString
}

/// Polling policy for telemetry configuration (5s interval, 30s window).
/// Mirrors Android `TelemetryPollingPolicy`.
struct TelemetryPollingPolicy {
    static let pollIntervalMs: UInt64 = 5_000
    static let maximumWindowMs: UInt64 = 30_000

    func shouldContinue(elapsedMs: UInt64, generation: UInt64, currentGeneration: UInt64, pageIsActive: Bool) -> Bool {
        pageIsActive
            && generation == currentGeneration
            && elapsedMs <= Self.maximumWindowMs
    }
}
