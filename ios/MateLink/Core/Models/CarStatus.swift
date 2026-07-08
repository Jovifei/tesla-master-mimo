import Foundation

enum CarState: String, Codable { case online, offline, asleep, charging, driving }

// MARK: - Nested API containers (TeslaMate API v1.24+)

private struct BatteryDetails: Decodable {
    let batteryLevel: Int
    let usableBatteryLevel: Int
    let estBatteryRange: Double
    let ratedBatteryRange: Double
    let idealBatteryRange: Double

    private enum CodingKeys: String, CodingKey {
        case batteryLevel = "battery_level"
        case usableBatteryLevel = "usable_battery_level"
        case estBatteryRange = "est_battery_range"
        case ratedBatteryRange = "rated_battery_range"
        case idealBatteryRange = "ideal_battery_range"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        batteryLevel = (try? c.decode(Int.self, forKey: .batteryLevel)) ?? 0
        usableBatteryLevel = (try? c.decode(Int.self, forKey: .usableBatteryLevel)) ?? 0
        estBatteryRange = (try? c.decode(Double.self, forKey: .estBatteryRange)) ?? 0
        ratedBatteryRange = (try? c.decode(Double.self, forKey: .ratedBatteryRange)) ?? 0
        idealBatteryRange = (try? c.decode(Double.self, forKey: .idealBatteryRange)) ?? 0
    }
}

private struct ChargingDetails: Decodable {
    let pluggedIn: Bool
    let chargingState: String
    let chargeEnergyAdded: Double
    let chargeLimitSoc: Int
    let chargePortDoorOpen: Bool
    let chargerActualCurrent: Int
    let chargerPhases: Int
    let chargerPower: Double
    let chargerVoltage: Int
    let chargeCurrentRequest: Int
    let chargeCurrentRequestMax: Int
    let timeToFullCharge: Double

    /// True only while *actively* DC charging (charger_phases == 0).
    var isDcCharging: Bool { chargerPhases == 0 }

    private enum CodingKeys: String, CodingKey {
        case pluggedIn = "plugged_in"
        case chargingState = "charging_state"
        case chargeEnergyAdded = "charge_energy_added"
        case chargeLimitSoc = "charge_limit_soc"
        case chargePortDoorOpen = "charge_port_door_open"
        case chargerActualCurrent = "charger_actual_current"
        case chargerPhases = "charger_phases"
        case chargerPower = "charger_power"
        case chargerVoltage = "charger_voltage"
        case chargeCurrentRequest = "charge_current_request"
        case chargeCurrentRequestMax = "charge_current_request_max"
        case timeToFullCharge = "time_to_full_charge"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pluggedIn = (try? c.decode(Bool.self, forKey: .pluggedIn)) ?? false
        chargingState = (try? c.decode(String.self, forKey: .chargingState)) ?? "unknown"
        chargeEnergyAdded = (try? c.decode(Double.self, forKey: .chargeEnergyAdded)) ?? 0
        chargeLimitSoc = (try? c.decode(Int.self, forKey: .chargeLimitSoc)) ?? 0
        chargePortDoorOpen = (try? c.decode(Bool.self, forKey: .chargePortDoorOpen)) ?? false
        chargerActualCurrent = (try? c.decode(Int.self, forKey: .chargerActualCurrent)) ?? 0
        chargerPhases = (try? c.decode(Int.self, forKey: .chargerPhases)) ?? 0
        chargerPower = (try? c.decode(Double.self, forKey: .chargerPower)) ?? 0
        chargerVoltage = (try? c.decode(Int.self, forKey: .chargerVoltage)) ?? 0
        chargeCurrentRequest = (try? c.decode(Int.self, forKey: .chargeCurrentRequest)) ?? 0
        chargeCurrentRequestMax = (try? c.decode(Int.self, forKey: .chargeCurrentRequestMax)) ?? 0
        timeToFullCharge = (try? c.decode(Double.self, forKey: .timeToFullCharge)) ?? 0
    }
}

private struct ClimateDetails: Decodable {
    let isClimateOn: Bool
    let insideTemp: Double
    let outsideTemp: Double

    private enum CodingKeys: String, CodingKey {
        case isClimateOn = "is_climate_on"
        case insideTemp = "inside_temp"
        case outsideTemp = "outside_temp"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        isClimateOn = (try? c.decode(Bool.self, forKey: .isClimateOn)) ?? false
        insideTemp = (try? c.decode(Double.self, forKey: .insideTemp)) ?? 0
        outsideTemp = (try? c.decode(Double.self, forKey: .outsideTemp)) ?? 0
    }
}

private struct TpmsDetails: Decodable {
    let tpmsPressureFl: Double
    let tpmsPressureFr: Double
    let tpmsPressureRl: Double
    let tpmsPressureRr: Double

    private enum CodingKeys: String, CodingKey {
        case tpmsPressureFl = "tpms_pressure_fl"
        case tpmsPressureFr = "tpms_pressure_fr"
        case tpmsPressureRl = "tpms_pressure_rl"
        case tpmsPressureRr = "tpms_pressure_rr"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tpmsPressureFl = (try? c.decode(Double.self, forKey: .tpmsPressureFl)) ?? 0
        tpmsPressureFr = (try? c.decode(Double.self, forKey: .tpmsPressureFr)) ?? 0
        tpmsPressureRl = (try? c.decode(Double.self, forKey: .tpmsPressureRl)) ?? 0
        tpmsPressureRr = (try? c.decode(Double.self, forKey: .tpmsPressureRr)) ?? 0
    }
}

private struct CarStatusContainer: Decodable {
    let healthy: Bool
    let locked: Bool
    let sentryMode: Bool
    let windowsOpen: Bool
    let doorsOpen: Bool
    let centerDisplayState: String

    private enum CodingKeys: String, CodingKey {
        case healthy
        case locked
        case sentryMode = "sentry_mode"
        case windowsOpen = "windows_open"
        case doorsOpen = "doors_open"
        case centerDisplayState = "center_display_state"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        healthy = (try? c.decode(Bool.self, forKey: .healthy)) ?? false
        locked = (try? c.decode(Bool.self, forKey: .locked)) ?? false
        sentryMode = (try? c.decode(Bool.self, forKey: .sentryMode)) ?? false
        windowsOpen = (try? c.decode(Bool.self, forKey: .windowsOpen)) ?? false
        doorsOpen = (try? c.decode(Bool.self, forKey: .doorsOpen)) ?? false
        centerDisplayState = (try? c.decode(String.self, forKey: .centerDisplayState)) ?? ""
    }
}

private struct CarGeodata: Decodable {
    let geofence: String
    let latitude: Double
    let longitude: Double

    private enum CodingKeys: String, CodingKey {
        case geofence, latitude, longitude
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        geofence = (try? c.decode(String.self, forKey: .geofence)) ?? ""
        latitude = (try? c.decode(Double.self, forKey: .latitude)) ?? 0
        longitude = (try? c.decode(Double.self, forKey: .longitude)) ?? 0
    }
}

private struct DrivingDetails: Decodable {
    let shiftState: String
    let power: Double
    let speed: Int
    let heading: Int
    let elevation: Double

    private enum CodingKeys: String, CodingKey {
        case shiftState = "shift_state"
        case power, speed, heading, elevation
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        shiftState = (try? c.decode(String.self, forKey: .shiftState)) ?? ""
        power = (try? c.decode(Double.self, forKey: .power)) ?? 0
        speed = (try? c.decode(Int.self, forKey: .speed)) ?? 0
        heading = (try? c.decode(Int.self, forKey: .heading)) ?? 0
        elevation = (try? c.decode(Double.self, forKey: .elevation)) ?? 0
    }
}

private struct OdometerDetails: Decodable {
    let odometer: Double

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        odometer = (try? c.decode(Double.self, forKey: .odometer)) ?? 0
    }

    private enum CodingKeys: String, CodingKey { case odometer }
}

private struct CarVersions: Decodable {
    let version: String
    let updateAvailable: Bool
    let updateVersion: String

    private enum CodingKeys: String, CodingKey {
        case version
        case updateAvailable = "update_available"
        case updateVersion = "update_version"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = (try? c.decode(String.self, forKey: .version)) ?? ""
        updateAvailable = (try? c.decode(Bool.self, forKey: .updateAvailable)) ?? false
        updateVersion = (try? c.decode(String.self, forKey: .updateVersion)) ?? ""
    }
}

// MARK: - CarStatus

struct CarStatus: Codable {
    let carId: Int
    let state: CarState
    let since: String
    let healthy: Bool
    let odometer: Int
    let batteryLevel: Int
    let usableBatteryLevel: Int
    let usableBatteryRangeKm: Double
    let idealBatteryRangeKm: Double
    let chargeEnergyAdded: Double
    let chargeLimitSoc: Int
    let chargerPower: Double
    let chargerActualCurrent: Int
    let chargerVoltage: Int
    let chargePortDoorOpen: Bool
    let timeToFullCharge: Double
    let insideTemp: Double
    let outsideTemp: Double
    let isClimateOn: Bool
    let locked: Bool
    let sentryMode: Bool
    let pluggedIn: Bool
    let tirePressureFrontLeft: Double
    let tirePressureFrontRight: Double
    let tirePressureRearLeft: Double
    let tirePressureRearRight: Double
    let latitude: Double
    let longitude: Double
    let elevation: Double
    let speed: Int
    let power: Double
    let heading: Int
    let shiftState: String?
    let isDcCharging: Bool
    let version: String?

    /// Explicit memberwise init for mock/preview use (suppressed by custom init(from:)).
    init(
        carId: Int, state: CarState, since: String, healthy: Bool,
        odometer: Int, batteryLevel: Int, usableBatteryLevel: Int,
        usableBatteryRangeKm: Double, idealBatteryRangeKm: Double,
        chargeEnergyAdded: Double, chargeLimitSoc: Int,
        chargerPower: Double, chargerActualCurrent: Int, chargerVoltage: Int,
        chargePortDoorOpen: Bool, timeToFullCharge: Double,
        insideTemp: Double, outsideTemp: Double, isClimateOn: Bool,
        locked: Bool, sentryMode: Bool, pluggedIn: Bool,
        tirePressureFrontLeft: Double, tirePressureFrontRight: Double,
        tirePressureRearLeft: Double, tirePressureRearRight: Double,
        latitude: Double, longitude: Double, elevation: Double,
        speed: Int, power: Double, heading: Int,
        shiftState: String?, isDcCharging: Bool = false, version: String? = nil
    ) {
        self.carId = carId; self.state = state; self.since = since; self.healthy = healthy
        self.odometer = odometer; self.batteryLevel = batteryLevel; self.usableBatteryLevel = usableBatteryLevel
        self.usableBatteryRangeKm = usableBatteryRangeKm; self.idealBatteryRangeKm = idealBatteryRangeKm
        self.chargeEnergyAdded = chargeEnergyAdded; self.chargeLimitSoc = chargeLimitSoc
        self.chargerPower = chargerPower; self.chargerActualCurrent = chargerActualCurrent; self.chargerVoltage = chargerVoltage
        self.chargePortDoorOpen = chargePortDoorOpen; self.timeToFullCharge = timeToFullCharge
        self.insideTemp = insideTemp; self.outsideTemp = outsideTemp; self.isClimateOn = isClimateOn
        self.locked = locked; self.sentryMode = sentryMode; self.pluggedIn = pluggedIn
        self.tirePressureFrontLeft = tirePressureFrontLeft; self.tirePressureFrontRight = tirePressureFrontRight
        self.tirePressureRearLeft = tirePressureRearLeft; self.tirePressureRearRight = tirePressureRearRight
        self.latitude = latitude; self.longitude = longitude; self.elevation = elevation
        self.speed = speed; self.power = power; self.heading = heading
        self.shiftState = shiftState; self.isDcCharging = isDcCharging; self.version = version
    }

    private enum TopKeys: String, CodingKey {
        case carId = "car_id"
        case state, since
        case batteryDetails = "battery_details"
        case chargingDetails = "charging_details"
        case climateDetails = "climate_details"
        case tpmsDetails = "tpms_details"
        case carStatus = "car_status"
        case carGeodata = "car_geodata"
        case drivingDetails = "driving_details"
        case odometer
        case carVersions = "car_versions"
    }

    init(from decoder: Decoder) throws {
        let top = try decoder.container(keyedBy: TopKeys.self)
        carId = try top.decode(Int.self, forKey: .carId)
        state = try top.decode(CarState.self, forKey: .state)
        since = (try? top.decode(String.self, forKey: .since)) ?? ""

        let battery = try top.decode(BatteryDetails.self, forKey: .batteryDetails)
        let charging = try top.decode(ChargingDetails.self, forKey: .chargingDetails)
        let climate = try top.decode(ClimateDetails.self, forKey: .climateDetails)
        let tpms = try top.decode(TpmsDetails.self, forKey: .tpmsDetails)
        let statusContainer = try top.decode(CarStatusContainer.self, forKey: .carStatus)
        let geodata = try top.decode(CarGeodata.self, forKey: .carGeodata)
        let driving = try top.decode(DrivingDetails.self, forKey: .drivingDetails)

        // Odometer is a top-level value in the API
        let odometerValue: Double = (try? top.decode(Double.self, forKey: .odometer)) ?? 0
        odometer = Int(odometerValue)

        // Battery
        batteryLevel = battery.batteryLevel
        usableBatteryLevel = battery.usableBatteryLevel
        usableBatteryRangeKm = battery.ratedBatteryRange
        idealBatteryRangeKm = battery.idealBatteryRange

        // Charging
        pluggedIn = charging.pluggedIn
        chargeEnergyAdded = charging.chargeEnergyAdded
        chargeLimitSoc = charging.chargeLimitSoc
        chargePortDoorOpen = charging.chargePortDoorOpen
        chargerActualCurrent = charging.chargerActualCurrent
        chargerPower = charging.chargerPower
        chargerVoltage = charging.chargerVoltage
        timeToFullCharge = charging.timeToFullCharge
        isDcCharging = charging.isDcCharging

        // Climate
        insideTemp = climate.insideTemp
        outsideTemp = climate.outsideTemp
        isClimateOn = climate.isClimateOn

        // Status container
        healthy = statusContainer.healthy
        locked = statusContainer.locked
        sentryMode = statusContainer.sentryMode

        // TPMS
        tirePressureFrontLeft = tpms.tpmsPressureFl
        tirePressureFrontRight = tpms.tpmsPressureFr
        tirePressureRearLeft = tpms.tpmsPressureRl
        tirePressureRearRight = tpms.tpmsPressureRr

        // Geodata
        latitude = geodata.latitude
        longitude = geodata.longitude

        // Driving
        shiftState = driving.shiftState.isEmpty ? nil : driving.shiftState
        speed = driving.speed
        power = driving.power
        heading = driving.heading
        elevation = driving.elevation

        // Versions (optional — may be absent)
        if let versions = try? top.decode(CarVersions.self, forKey: .carVersions) {
            version = versions.version.isEmpty ? nil : versions.version
        } else {
            version = nil
        }
    }

    func encode(to encoder: Encoder) throws {
        var top = encoder.container(keyedBy: TopKeys.self)
        try top.encode(carId, forKey: .carId)
        try top.encode(state, forKey: .state)
        try top.encode(since, forKey: .since)
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

    /// Memberwise init for Previews / mocks.
    init(
        id: Int, carId: Int, startDate: String, endDate: String,
        distanceKm: Double, durationMin: Int, efficiency: Double,
        startAddress: String, endAddress: String,
        startLatitude: Double, startLongitude: Double,
        endLatitude: Double, endLongitude: Double,
        startBatteryLevel: Int, endBatteryLevel: Int,
        startIdealRangeKm: Double, endIdealRangeKm: Double,
        outsideTempAvg: Double, speedMax: Double, powerMax: Double, powerMin: Double,
        elevationGain: Double, elevationLoss: Double
    ) {
        self.id = id; self.carId = carId; self.startDate = startDate; self.endDate = endDate
        self.distanceKm = distanceKm; self.durationMin = durationMin; self.efficiency = efficiency
        self.startAddress = startAddress; self.endAddress = endAddress
        self.startLatitude = startLatitude; self.startLongitude = startLongitude
        self.endLatitude = endLatitude; self.endLongitude = endLongitude
        self.startBatteryLevel = startBatteryLevel; self.endBatteryLevel = endBatteryLevel
        self.startIdealRangeKm = startIdealRangeKm; self.endIdealRangeKm = endIdealRangeKm
        self.outsideTempAvg = outsideTempAvg; self.speedMax = speedMax
        self.powerMax = powerMax; self.powerMin = powerMin
        self.elevationGain = elevationGain; self.elevationLoss = elevationLoss
    }

    private enum TopKeys: String, CodingKey {
        case id; case carId = "car_id"
        case startDate = "start_date"; case endDate = "end_date"
        case distanceKm = "distance_km"; case durationMin = "duration_min"; case efficiency
        case startAddress = "start_address"; case endAddress = "end_address"
        case startLatitude = "start_latitude"; case startLongitude = "start_longitude"
        case endLatitude = "end_latitude"; case endLongitude = "end_longitude"
        case outsideTempAvg = "outside_temp_avg"; case speedMax = "speed_max"
        case powerMax = "power_max"; case powerMin = "power_min"
        case elevationGain = "elevation_gain"; case elevationLoss = "elevation_loss"
        // Nested containers (TeslaMate API v1.24+)
        case odometerDetails = "odometer_details"
        case batteryDetails = "battery_details"
        case rangeRated = "range_rated"
    }

    init(from decoder: Decoder) throws {
        let top = try decoder.container(keyedBy: TopKeys.self)
        id = try top.decode(Int.self, forKey: .id)
        carId = try top.decode(Int.self, forKey: .carId)
        startDate = (try? top.decode(String.self, forKey: .startDate)) ?? ""
        endDate = (try? top.decode(String.self, forKey: .endDate)) ?? ""
        durationMin = (try? top.decode(Int.self, forKey: .durationMin)) ?? 0
        efficiency = (try? top.decode(Double.self, forKey: .efficiency)) ?? 0
        startAddress = (try? top.decode(String.self, forKey: .startAddress)) ?? ""
        endAddress = (try? top.decode(String.self, forKey: .endAddress)) ?? ""
        startLatitude = (try? top.decode(Double.self, forKey: .startLatitude)) ?? 0
        startLongitude = (try? top.decode(Double.self, forKey: .startLongitude)) ?? 0
        endLatitude = (try? top.decode(Double.self, forKey: .endLatitude)) ?? 0
        endLongitude = (try? top.decode(Double.self, forKey: .endLongitude)) ?? 0
        outsideTempAvg = (try? top.decode(Double.self, forKey: .outsideTempAvg)) ?? 0
        speedMax = (try? top.decode(Double.self, forKey: .speedMax)) ?? 0
        powerMax = (try? top.decode(Double.self, forKey: .powerMax)) ?? 0
        powerMin = (try? top.decode(Double.self, forKey: .powerMin)) ?? 0
        elevationGain = (try? top.decode(Double.self, forKey: .elevationGain)) ?? 0
        elevationLoss = (try? top.decode(Double.self, forKey: .elevationLoss)) ?? 0

        // Nested: odometer_details.distance
        if let odometerDetails = try? top.decode(DriveOdometerDetails.self, forKey: .odometerDetails) {
            distanceKm = odometerDetails.distance
        } else {
            distanceKm = (try? top.decode(Double.self, forKey: .distanceKm)) ?? 0
        }

        // Nested: battery_details.start/end_battery_level
        if let batteryDetails = try? top.decode(DriveBatteryDetails.self, forKey: .batteryDetails) {
            startBatteryLevel = batteryDetails.startBatteryLevel
            endBatteryLevel = batteryDetails.endBatteryLevel
        } else {
            startBatteryLevel = (try? top.decode(Int.self, forKey: .startBatteryLevel)) ?? 0
            endBatteryLevel = (try? top.decode(Int.self, forKey: .endBatteryLevel)) ?? 0
        }

        // Nested: range_rated.start/end range
        if let rangeDetails = try? top.decode(DriveRangeDetails.self, forKey: .rangeRated) {
            startIdealRangeKm = rangeDetails.startRange
            endIdealRangeKm = rangeDetails.endRange
        } else {
            startIdealRangeKm = (try? top.decode(Double.self, forKey: .startIdealRangeKm)) ?? 0
            endIdealRangeKm = (try? top.decode(Double.self, forKey: .endIdealRangeKm)) ?? 0
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: TopKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(carId, forKey: .carId)
        try c.encode(startDate, forKey: .startDate)
        try c.encode(endDate, forKey: .endDate)
        try c.encode(distanceKm, forKey: .distanceKm)
        try c.encode(durationMin, forKey: .durationMin)
        try c.encode(efficiency, forKey: .efficiency)
        try c.encode(startAddress, forKey: .startAddress)
        try c.encode(endAddress, forKey: .endAddress)
        try c.encode(startBatteryLevel, forKey: .startBatteryLevel)
        try c.encode(endBatteryLevel, forKey: .endBatteryLevel)
        try c.encode(startIdealRangeKm, forKey: .startIdealRangeKm)
        try c.encode(endIdealRangeKm, forKey: .endIdealRangeKm)
        try c.encode(outsideTempAvg, forKey: .outsideTempAvg)
        try c.encode(speedMax, forKey: .speedMax)
        try c.encode(powerMax, forKey: .powerMax)
        try c.encode(powerMin, forKey: .powerMin)
        try c.encode(elevationGain, forKey: .elevationGain)
        try c.encode(elevationLoss, forKey: .elevationLoss)
        try c.encode(startLatitude, forKey: .startLatitude)
        try c.encode(startLongitude, forKey: .startLongitude)
        try c.encode(endLatitude, forKey: .endLatitude)
        try c.encode(endLongitude, forKey: .endLongitude)
    }
}

// MARK: - Drive nested containers (TeslaMate API v1.24+)

private struct DriveOdometerDetails: Decodable {
    let distance: Double
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        distance = (try? c.decode(Double.self, forKey: .distance)) ?? 0
    }
    private enum CodingKeys: String, CodingKey { case distance }
}

private struct DriveBatteryDetails: Decodable {
    let startBatteryLevel: Int
    let endBatteryLevel: Int
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        startBatteryLevel = (try? c.decode(Int.self, forKey: .startBatteryLevel)) ?? 0
        endBatteryLevel = (try? c.decode(Int.self, forKey: .endBatteryLevel)) ?? 0
    }
    private enum CodingKeys: String, CodingKey {
        case startBatteryLevel = "start_battery_level"
        case endBatteryLevel = "end_battery_level"
    }
}

private struct DriveRangeDetails: Decodable {
    let startRange: Double
    let endRange: Double
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        startRange = (try? c.decode(Double.self, forKey: .startRange)) ?? 0
        endRange = (try? c.decode(Double.self, forKey: .endRange)) ?? 0
    }
    private enum CodingKeys: String, CodingKey {
        case startRange = "start_range"
        case endRange = "end_range"
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

    /// Memberwise init for Previews / mocks.
    init(
        id: Int, carId: Int, startDate: String, endDate: String? = nil,
        chargeEnergyAdded: Double, startBatteryLevel: Int, endBatteryLevel: Int? = nil,
        startIdealRangeKm: Double, endIdealRangeKm: Double? = nil,
        startRatedRangeKm: Double, endRatedRangeKm: Double? = nil,
        durationMin: Int, cost: Double? = nil, address: String? = nil,
        latitude: Double, longitude: Double,
        chargingType: String, powerMax: Double, powerMin: Double,
        outsideTempAvg: Double
    ) {
        self.id = id; self.carId = carId; self.startDate = startDate; self.endDate = endDate
        self.chargeEnergyAdded = chargeEnergyAdded; self.startBatteryLevel = startBatteryLevel; self.endBatteryLevel = endBatteryLevel
        self.startIdealRangeKm = startIdealRangeKm; self.endIdealRangeKm = endIdealRangeKm
        self.startRatedRangeKm = startRatedRangeKm; self.endRatedRangeKm = endRatedRangeKm
        self.durationMin = durationMin; self.cost = cost; self.address = address
        self.latitude = latitude; self.longitude = longitude
        self.chargingType = chargingType; self.powerMax = powerMax; self.powerMin = powerMin
        self.outsideTempAvg = outsideTempAvg
    }

    private enum TopKeys: String, CodingKey {
        case id; case carId = "car_id"
        case startDate = "start_date"; case endDate = "end_date"
        case chargeEnergyAdded = "charge_energy_added"
        case durationMin = "duration_min"; case cost; case address
        case latitude, longitude
        case powerMax = "power_max"; case powerMin = "power_min"
        case outsideTempAvg = "outside_temp_avg"
        // Nested containers (TeslaMate API v1.24+)
        case batteryDetails = "battery_details"
        case rangeRated = "range_rated"
        case rangeIdeal = "range_ideal"
        case chargerPhases = "charger_phases"
        case chargingType = "charging_type"
    }

    init(from decoder: Decoder) throws {
        let top = try decoder.container(keyedBy: TopKeys.self)
        id = try top.decode(Int.self, forKey: .id)
        carId = try top.decode(Int.self, forKey: .carId)
        startDate = (try? top.decode(String.self, forKey: .startDate)) ?? ""
        endDate = try? top.decode(String.self, forKey: .endDate)
        chargeEnergyAdded = (try? top.decode(Double.self, forKey: .chargeEnergyAdded)) ?? 0
        durationMin = (try? top.decode(Int.self, forKey: .durationMin)) ?? 0
        cost = try? top.decode(Double.self, forKey: .cost)
        address = try? top.decode(String.self, forKey: .address)
        latitude = (try? top.decode(Double.self, forKey: .latitude)) ?? 0
        longitude = (try? top.decode(Double.self, forKey: .longitude)) ?? 0
        powerMax = (try? top.decode(Double.self, forKey: .powerMax)) ?? 0
        powerMin = (try? top.decode(Double.self, forKey: .powerMin)) ?? 0
        outsideTempAvg = (try? top.decode(Double.self, forKey: .outsideTempAvg)) ?? 0

        // Nested: battery_details
        if let batteryDetails = try? top.decode(ChargeBatteryDetails.self, forKey: .batteryDetails) {
            startBatteryLevel = batteryDetails.startBatteryLevel
            endBatteryLevel = batteryDetails.endBatteryLevel
        } else {
            startBatteryLevel = (try? top.decode(Int.self, forKey: .startBatteryLevel)) ?? 0
            endBatteryLevel = try? top.decode(Int.self, forKey: .endBatteryLevel)
        }

        // Nested: range_rated → start/end rated range
        if let rangeRated = try? top.decode(ChargeRangeDetails.self, forKey: .rangeRated) {
            startRatedRangeKm = rangeRated.startRange
            endRatedRangeKm = rangeRated.endRange
        } else {
            startRatedRangeKm = (try? top.decode(Double.self, forKey: .startRatedRangeKm)) ?? 0
            endRatedRangeKm = try? top.decode(Double.self, forKey: .endRatedRangeKm)
        }

        // Nested: range_ideal → start/end ideal range
        if let rangeIdeal = try? top.decode(ChargeRangeDetails.self, forKey: .rangeIdeal) {
            startIdealRangeKm = rangeIdeal.startRange
            endIdealRangeKm = rangeIdeal.endRange
        } else {
            startIdealRangeKm = (try? top.decode(Double.self, forKey: .startIdealRangeKm)) ?? 0
            endIdealRangeKm = try? top.decode(Double.self, forKey: .endIdealRangeKm)
        }

        // Try explicit charging_type first, fallback to charger_phases derivation
        if let explicitType = try? top.decode(String.self, forKey: .chargingType) {
            chargingType = explicitType
        } else {
            let phases = (try? top.decode(Int.self, forKey: .chargerPhases)) ?? -1
            chargingType = (phases == 0) ? "DC" : "AC"
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: TopKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(carId, forKey: .carId)
        try c.encode(startDate, forKey: .startDate)
        try c.encodeIfPresent(endDate, forKey: .endDate)
        try c.encode(chargeEnergyAdded, forKey: .chargeEnergyAdded)
        try c.encode(startBatteryLevel, forKey: .startBatteryLevel)
        try c.encodeIfPresent(endBatteryLevel, forKey: .endBatteryLevel)
        try c.encode(startIdealRangeKm, forKey: .startIdealRangeKm)
        try c.encodeIfPresent(endIdealRangeKm, forKey: .endIdealRangeKm)
        try c.encode(startRatedRangeKm, forKey: .startRatedRangeKm)
        try c.encodeIfPresent(endRatedRangeKm, forKey: .endRatedRangeKm)
        try c.encode(durationMin, forKey: .durationMin)
        try c.encodeIfPresent(cost, forKey: .cost)
        try c.encodeIfPresent(address, forKey: .address)
        try c.encode(latitude, forKey: .latitude)
        try c.encode(longitude, forKey: .longitude)
        try c.encode(chargingType, forKey: .chargingType)
        try c.encode(powerMax, forKey: .powerMax)
        try c.encode(powerMin, forKey: .powerMin)
        try c.encode(outsideTempAvg, forKey: .outsideTempAvg)
    }
}

// MARK: - Charge nested containers (TeslaMate API v1.24+)

private struct ChargeBatteryDetails: Decodable {
    let startBatteryLevel: Int
    let endBatteryLevel: Int?
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        startBatteryLevel = (try? c.decode(Int.self, forKey: .startBatteryLevel)) ?? 0
        endBatteryLevel = try? c.decode(Int.self, forKey: .endBatteryLevel)
    }
    private enum CodingKeys: String, CodingKey {
        case startBatteryLevel = "start_battery_level"
        case endBatteryLevel = "end_battery_level"
    }
}

private struct ChargeRangeDetails: Decodable {
    let startRange: Double
    let endRange: Double?
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        startRange = (try? c.decode(Double.self, forKey: .startRange)) ?? 0
        endRange = try? c.decode(Double.self, forKey: .endRange)
    }
    private enum CodingKeys: String, CodingKey {
        case startRange = "start_range"
        case endRange = "end_range"
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

        // TeslaMate API v1.24+ alternate keys
        case maxRange = "max_range"
        case currentRange = "current_range"
        case healthPercentage = "battery_health_percentage"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        carId = (try? c.decode(Int.self, forKey: .carId)) ?? 0
        date = (try? c.decode(String.self, forKey: .date)) ?? ""
        batteryLevel = (try? c.decode(Int.self, forKey: .batteryLevel)) ?? 0
        odometer = (try? c.decode(Double.self, forKey: .odometer)) ?? 0
        outsideTemp = (try? c.decode(Double.self, forKey: .outsideTemp)) ?? 0
        usableBatteryLevel = (try? c.decode(Int.self, forKey: .usableBatteryLevel)) ?? 0
        capacityDegradationPercent = try? c.decode(Double.self, forKey: .capacityDegradationPercent)
        originalCapacityKwh = try? c.decode(Double.self, forKey: .originalCapacityKwh)
        currentCapacityKwh = try? c.decode(Double.self, forKey: .currentCapacityKwh)
        history = try? c.decode([BatteryHealthPoint].self, forKey: .history)

        // TeslaMate API v1.24+: max_range → ratedRangeKm, current_range → idealRangeKm
        if let maxRange = try? c.decode(Double.self, forKey: .maxRange) {
            ratedRangeKm = maxRange
        } else {
            ratedRangeKm = (try? c.decode(Double.self, forKey: .ratedRangeKm)) ?? 0
        }
        if let currentRange = try? c.decode(Double.self, forKey: .currentRange) {
            idealRangeKm = currentRange
        } else {
            idealRangeKm = (try? c.decode(Double.self, forKey: .idealRangeKm)) ?? 0
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(carId, forKey: .carId)
        try c.encode(date, forKey: .date)
        try c.encode(batteryLevel, forKey: .batteryLevel)
        try c.encode(ratedRangeKm, forKey: .ratedRangeKm)
        try c.encode(idealRangeKm, forKey: .idealRangeKm)
        try c.encode(odometer, forKey: .odometer)
        try c.encode(outsideTemp, forKey: .outsideTemp)
        try c.encode(usableBatteryLevel, forKey: .usableBatteryLevel)
        try c.encodeIfPresent(capacityDegradationPercent, forKey: .capacityDegradationPercent)
        try c.encodeIfPresent(originalCapacityKwh, forKey: .originalCapacityKwh)
        try c.encodeIfPresent(currentCapacityKwh, forKey: .currentCapacityKwh)
        try c.encodeIfPresent(history, forKey: .history)
    }
}

struct UpdateItem: Codable, Identifiable {
    let id: Int; let carId: Int; let startDate: String; let endDate: String; let version: String
    enum CodingKeys: String, CodingKey { case id; case carId = "car_id"; case startDate = "start_date"; case endDate = "end_date"; case version }
}

/// Sentry mode alert event (mock `sentry_events` array).
/// iOS API does not yet expose a real sentry endpoint; this models the mock payload
/// so SentryHistoryView can render a list/detail shell.
struct SentryEvent: Codable, Identifiable {
    let id: Int
    let startDate: String
    let endDate: String?
    let latitude: Double
    let longitude: Double
    let address: String?

    enum CodingKeys: String, CodingKey {
        case id
        case startDate = "start_date"
        case endDate = "end_date"
        case latitude, longitude, address
    }
}

// MARK: - Charge compat aliases
//
// Several consumers (ChargeListView / ChargeDetailView / CostView / TimelineView) were
// written against an older/Android-leaning field set that the iOS `Charge` model does
// not currently store: `chargeType`, `chargeEnergyUsed`, `fastChargerBrand`,
// `fastChargerType`. Rather than touch every call site, expose read-only compat props
// here so the consumers compile against the real `chargingType`/optional `cost`/
// `address` storage. When the iOS API gains the real fields, swap these out.
extension Charge {
    /// Alias for the stored `chargingType` (JSON `charging_type`).
    var chargeType: String { chargingType }

    /// Real `charge_energy_used` is not yet exposed by the iOS API/mock.
    /// Returns 0 to signal "unknown"; views render "—" when 0.
    var chargeEnergyUsed: Double { 0 }

    /// Fast-charger metadata is not yet modeled on iOS; nil-safe stubs for view compat.
    var fastChargerBrand: String? { nil }
    var fastChargerType: String? { nil }
}
