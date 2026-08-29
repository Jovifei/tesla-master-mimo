import Foundation

/// A single position sample in a drive's trajectory time-series.
/// Mirrors Android's `DrivePosition` in DriveModels.kt.
struct DrivePosition: Codable, Identifiable {
    var id: String { date ?? UUID().uuidString }

    let date: String?
    let latitude: Double?
    let longitude: Double?
    let speed: Int?
    let power: Int?
    let batteryLevel: Int?
    let elevation: Int?
    let climateInfo: DriveClimateInfo?
    let batteryInfo: DrivePositionBatteryInfo?

    // Computed conveniences
    var insideTemp: Double? { climateInfo?.insideTemp }
    var outsideTemp: Double? { climateInfo?.outsideTemp }
    var isClimateOn: Bool { climateInfo?.isClimateOn == true }
    var isBatteryHeaterOn: Bool { batteryInfo?.batteryHeater == true }

    enum CodingKeys: String, CodingKey {
        case date, latitude, longitude, speed, power, elevation
        case batteryLevel = "battery_level"
        case climateInfo = "climate_info"
        case batteryInfo = "battery_info"
    }
}

struct DriveClimateInfo: Codable {
    let insideTemp: Double?
    let outsideTemp: Double?
    let isClimateOn: Bool?
    let fanStatus: Int?
    let driverTempSetting: Double?
    let passengerTempSetting: Double?

    enum CodingKeys: String, CodingKey {
        case insideTemp = "inside_temp"
        case outsideTemp = "outside_temp"
        case isClimateOn = "is_climate_on"
        case fanStatus = "fan_status"
        case driverTempSetting = "driver_temp_setting"
        case passengerTempSetting = "passenger_temp_setting"
    }
}

struct DrivePositionBatteryInfo: Codable {
    let batteryHeater: Bool?
    let batteryHeaterOn: Bool?
    let batteryHeaterNoPower: Bool?

    enum CodingKeys: String, CodingKey {
        case batteryHeater = "battery_heater"
        case batteryHeaterOn = "battery_heater_on"
        case batteryHeaterNoPower = "battery_heater_no_power"
    }
}
