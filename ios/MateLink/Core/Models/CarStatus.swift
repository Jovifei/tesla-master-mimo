import Foundation

enum CarState: String, Codable { case online, offline, asleep, charging, driving }

struct CarStatus: Codable {
    let carId: Int; let state: CarState; let since: String; let healthy: Bool
    let odometer: Int; let batteryLevel: Int; let usableBatteryLevel: Int
    let usableBatteryRangeKm: Double; let idealBatteryRangeKm: Double
    let chargeEnergyAdded: Double; let chargeLimitSoc: Int
    let chargerPower: Double; let chargerActualCurrent: Int; let chargerVoltage: Int
    let chargePortDoorOpen: Bool; let timeToFullCharge: Double
    let insideTemp: Double; let outsideTemp: Double; let isClimateOn: Bool
    let locked: Bool; let sentryMode: Bool; let pluggedIn: Bool
    let tirePressureFrontLeft: Double; let tirePressureFrontRight: Double
    let tirePressureRearLeft: Double; let tirePressureRearRight: Double
    let latitude: Double; let longitude: Double; let elevation: Double
    let speed: Int; let power: Double; let heading: Int
    let shiftState: String?

    enum CodingKeys: String, CodingKey {
        case carId = "car_id"; case state, since, healthy
        case odometer; case batteryLevel = "battery_level"; case usableBatteryLevel = "usable_battery_level"
        case usableBatteryRangeKm = "usable_battery_range_km"; case idealBatteryRangeKm = "ideal_battery_range_km"
        case chargeEnergyAdded = "charge_energy_added"; case chargeLimitSoc = "charge_limit_soc"
        case chargerPower = "charger_power"; case chargerActualCurrent = "charger_actual_current"; case chargerVoltage = "charger_voltage"
        case chargePortDoorOpen = "charge_port_door_open"; case timeToFullCharge = "time_to_full_charge"
        case insideTemp = "inside_temp"; case outsideTemp = "outside_temp"; case isClimateOn = "is_climate_on"
        case locked; case sentryMode = "sentry_mode"; case pluggedIn = "plugged_in"
        case tirePressureFrontLeft = "tire_pressure_front_left"; case tirePressureFrontRight = "tire_pressure_front_right"
        case tirePressureRearLeft = "tire_pressure_rear_left"; case tirePressureRearRight = "tire_pressure_rear_right"
        case latitude, longitude, elevation, speed, power, heading; case shiftState = "shift_state"
    }
}

struct Drive: Codable, Identifiable {
    let id: Int; let carId: Int; let startDate: String; let endDate: String
    let distanceKm: Double; let durationMin: Int; let efficiency: Double
    let startAddress: String; let endAddress: String
    let startLatitude: Double; let startLongitude: Double
    let endLatitude: Double; let endLongitude: Double
    let startBatteryLevel: Int; let endBatteryLevel: Int
    let startIdealRangeKm: Double; let endIdealRangeKm: Double
    let outsideTempAvg: Double; let speedMax: Double; let powerMax: Double; let powerMin: Double
    let elevationGain: Double; let elevationLoss: Double

    /// 行程能耗 (kWh) = 距离(km) × 效率(Wh/km) / 1000
    var consumptionKwh: Double { distanceKm * efficiency / 1000.0 }

    enum CodingKeys: String, CodingKey {
        case id; case carId = "car_id"; case startDate = "start_date"; case endDate = "end_date"
        case distanceKm = "distance_km"; case durationMin = "duration_min"; case efficiency
        case startAddress = "start_address"; case endAddress = "end_address"
        case startLatitude = "start_latitude"; case startLongitude = "start_longitude"
        case endLatitude = "end_latitude"; case endLongitude = "end_longitude"
        case startBatteryLevel = "start_battery_level"; case endBatteryLevel = "end_battery_level"
        case startIdealRangeKm = "start_ideal_range_km"; case endIdealRangeKm = "end_ideal_range_km"
        case outsideTempAvg = "outside_temp_avg"; case speedMax = "speed_max"
        case powerMax = "power_max"; case powerMin = "power_min"
        case elevationGain = "elevation_gain"; case elevationLoss = "elevation_loss"
    }
}

struct Charge: Codable, Identifiable {
    let id: Int; let carId: Int; let startDate: String; let endDate: String?
    let chargeEnergyAdded: Double; let startBatteryLevel: Int; let endBatteryLevel: Int?
    let startIdealRangeKm: Double; let endIdealRangeKm: Double?
    let startRatedRangeKm: Double; let endRatedRangeKm: Double?
    let durationMin: Int; let cost: Double?; let address: String?
    let latitude: Double; let longitude: Double
    let chargingType: String; let powerMax: Double; let powerMin: Double
    let outsideTempAvg: Double

    enum CodingKeys: String, CodingKey {
        case id; case carId = "car_id"; case startDate = "start_date"; case endDate = "end_date"
        case chargeEnergyAdded = "charge_energy_added"
        case startBatteryLevel = "start_battery_level"; case endBatteryLevel = "end_battery_level"
        case startIdealRangeKm = "start_ideal_range_km"; case endIdealRangeKm = "end_ideal_range_km"
        case startRatedRangeKm = "start_rated_range_km"; case endRatedRangeKm = "end_rated_range_km"
        case durationMin = "duration_min"; case cost; case address
        case latitude, longitude; case chargingType = "charging_type"
        case powerMax = "power_max"; case powerMin = "power_min"
        case outsideTempAvg = "outside_temp_avg"
    }
}

struct BatteryHealthPoint: Codable, Identifiable {
    var id: String { date }
    let date: String
    let capacityKwh: Double
}

struct BatteryHealth: Codable {
    let carId: Int; let date: String; let batteryLevel: Int
    let ratedRangeKm: Double; let idealRangeKm: Double
    let odometer: Double; let outsideTemp: Double; let usableBatteryLevel: Int
    // 可选扩展字段：API/mock 未提供时为 nil，View 用默认值兜底
    let capacityDegradationPercent: Double?
    let originalCapacityKwh: Double?
    let currentCapacityKwh: Double?
    let history: [BatteryHealthPoint]?

    /// 里程 (km)，复用 odometer
    var mileageKm: Double { odometer }

    enum CodingKeys: String, CodingKey {
        case carId = "car_id"; case date; case batteryLevel = "battery_level"
        case ratedRangeKm = "rated_range_km"; case idealRangeKm = "ideal_range_km"
        case odometer; case outsideTemp = "outside_temp"; case usableBatteryLevel = "usable_battery_level"
        case capacityDegradationPercent = "capacity_degradation_percent"
        case originalCapacityKwh = "original_capacity_kwh"
        case currentCapacityKwh = "current_capacity_kwh"
        case history
    }
}

struct UpdateItem: Codable, Identifiable {
    let id: Int; let carId: Int; let startDate: String; let endDate: String; let version: String
    enum CodingKeys: String, CodingKey { case id; case carId = "car_id"; case startDate = "start_date"; case endDate = "end_date"; case version }
}
