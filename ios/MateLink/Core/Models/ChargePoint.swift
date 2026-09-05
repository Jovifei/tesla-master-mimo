import Foundation

/// A single data point in a charge session's time-series curve.
/// Mirrors Android's `ChargePoint` in ChargeModels.kt.
struct ChargePoint: Codable, Identifiable {
    var id: String { date ?? UUID().uuidString }

    let date: String?
    let batteryLevel: Int?
    let chargeEnergyAdded: Double?
    let chargerDetails: ChargerDetails?
    let outsideTemp: Double?
    let batteryInfo: ChargeBatteryInfo?

    // Computed conveniences
    var chargerPower: Int? { chargerDetails?.chargerPower.map { Int($0) } }
    var chargerVoltage: Int? { chargerDetails?.chargerVoltage.map { Int($0) } }
    var chargerCurrent: Int? { chargerDetails?.chargerActualCurrent.map { Int($0) } }
    var isDcCharging: Bool { (chargerDetails?.chargerPhases ?? 1) == 0 }

    enum CodingKeys: String, CodingKey {
        case date
        case batteryLevel = "battery_level"
        case chargeEnergyAdded = "charge_energy_added"
        case chargerDetails = "charger_details"
        case outsideTemp = "outside_temp"
        case batteryInfo = "battery_info"
    }
}

struct ChargerDetails: Codable {
    let chargerPower: Double?
    let chargerVoltage: Double?
    let chargerActualCurrent: Double?
    let chargerPhases: Int?
    let fastChargerPresent: Bool?
    let fastChargerBrand: String?
    let fastChargerType: String?

    enum CodingKeys: String, CodingKey {
        case chargerPower = "charger_power"
        case chargerVoltage = "charger_voltage"
        case chargerActualCurrent = "charger_actual_current"
        case chargerPhases = "charger_phases"
        case fastChargerPresent = "fast_charger_present"
        case fastChargerBrand = "fast_charger_brand"
        case fastChargerType = "fast_charger_type"
    }
}

struct ChargeBatteryInfo: Codable {
    let idealBatteryRangeKm: Double?
    let ratedBatteryRangeKm: Double?
    let usableBatteryLevel: Int?

    enum CodingKeys: String, CodingKey {
        case idealBatteryRangeKm = "ideal_battery_range_km"
        case ratedBatteryRangeKm = "rated_battery_range_km"
        case usableBatteryLevel = "usable_battery_level"
    }
}
