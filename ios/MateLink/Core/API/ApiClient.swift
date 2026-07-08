import Foundation

enum ApiError: LocalizedError {
    case networkUnreachable(String), unauthorized, serverError(Int, String), timeout, decodingError(String), invalidResponse
    var errorDescription: String? {
        switch self {
        case .networkUnreachable(let msg): return "Cannot reach server: \(msg)"
        case .unauthorized: return "Invalid token (401)"
        case .serverError(let code, let body): return "Server error \(code): \(body)"
        case .timeout: return "Connection timeout"
        case .decodingError(let msg): return "Data error: \(msg)"
        case .invalidResponse: return "Invalid or empty response from server"
        }
    }
}

actor TeslaMateAPI {
    private let baseURL: String; private let token: String?
    private let session: URLSession

    init(baseURL: String, token: String?) {
        self.baseURL = baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL
        self.token = token
        if let error = UrlSecurity.validate(baseURL, token: token) {
            print("[UrlSecurity] \(error)")
        }
        let config = URLSessionConfiguration.default; config.timeoutIntervalForRequest = 10
        self.session = URLSession(configuration: config)
    }

    func fetch<T: Decodable>(_ path: String) async throws -> T {
        guard let url = URL(string: "\(baseURL)\(path)") else { throw ApiError.networkUnreachable("Invalid URL") }
        var req = URLRequest(url: url)
        if let token = token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        do {
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse else { throw ApiError.serverError(0, "Not HTTP") }
            guard (200...299).contains(http.statusCode) else { throw ApiError.serverError(http.statusCode, String(data: data, encoding: .utf8) ?? "") }
            do { return try JSONDecoder().decode(T.self, from: data) }
            catch { throw ApiError.decodingError(error.localizedDescription) }
        } catch let e as ApiError { throw e }
        catch let e as URLError where e.code == .timedOut { throw ApiError.timeout }
        catch { throw ApiError.networkUnreachable(error.localizedDescription) }
    }

    /// HTTP status‑only fetch (no decoding) — used for ping / readyz probes.
    func checkStatus(_ path: String) async throws {
        guard let url = URL(string: "\(baseURL)\(path)") else { throw ApiError.networkUnreachable("Invalid URL") }
        var req = URLRequest(url: url)
        if let token = token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        do {
            let (data, resp) = try await session.data(for: req)
            guard let http = resp as? HTTPURLResponse else { throw ApiError.serverError(0, "Not HTTP") }
            guard (200...299).contains(http.statusCode) else { throw ApiError.serverError(http.statusCode, String(data: data, encoding: .utf8) ?? "") }
        } catch let e as ApiError { throw e }
        catch let e as URLError where e.code == .timedOut { throw ApiError.timeout }
        catch { throw ApiError.networkUnreachable(error.localizedDescription) }
    }

    // MARK: - Single-resource endpoints

    func getCar(_ carId: Int) async throws -> Car {
        let resp: CarApiResponse = try await fetch("/api/v1/cars/\(carId)")
        guard let car = resp.data.cars.first(where: { $0.carId == carId }) ?? resp.data.cars.first else {
            throw ApiError.invalidResponse
        }
        return Car(from: car)
    }

    func getCurrentCharge(_ carId: Int) async throws -> Charge? {
        try await fetch("/api/v1/cars/\(carId)/charges/current")
    }

    func getChargeDetail(_ carId: Int, chargeId: Int) async throws -> Charge {
        return try await fetch("/api/v1/cars/\(carId)/charges/\(chargeId)")
    }

    func getDriveDetail(_ carId: Int, driveId: Int) async throws -> Drive {
        return try await fetch("/api/v1/cars/\(carId)/drives/\(driveId)")
    }

    func getBatteryHealth(_ carId: Int) async throws -> BatteryHealth {
        return try await fetch("/api/v1/cars/\(carId)/battery-health")
    }

    func getUpdates(_ carId: Int) async throws -> [UpdateItem] {
        return try await fetch("/api/v1/cars/\(carId)/updates")
    }

    func getGlobalSettings() async throws -> TeslamateUnits {
        let resp: GlobalSettingsResponse = try await fetch("/api/v1/globalsettings")
        return resp.data.settings.teslamateUnits
    }
}

struct GlobalSettingsResponse: Codable {
    let data: GlobalSettingsData
}
struct GlobalSettingsData: Codable {
    let settings: GlobalSettingsContainer
}
struct GlobalSettingsContainer: Codable {
    let teslamateUnits: TeslamateUnits
    enum CodingKeys: String, CodingKey {
        case teslamateUnits = "teslamate_units"
    }
}
struct TeslamateUnits: Codable {
    let unitOfLength: String?
    let unitOfPressure: String?
    let unitOfTemperature: String?
    enum CodingKeys: String, CodingKey {
        case unitOfLength = "unit_of_length"
        case unitOfPressure = "unit_of_pressure"
        case unitOfTemperature = "unit_of_temperature"
    }
}

// MARK: - Mock Client (for preview / development)
actor MockAPI {
    private let data: MockData
    init() { self.data = MockData.load() }

    func mockStatus(_ carId: Int) -> CarStatus {
        let base = data.status[String(carId)] ?? CarStatus(carId: carId, state: .offline, since: "", healthy: true, odometer: 0, batteryLevel: 0, usableBatteryLevel: 0, usableBatteryRangeKm: 0, idealBatteryRangeKm: 0, chargeEnergyAdded: 0, chargeLimitSoc: 0, chargerPower: 0, chargerActualCurrent: 0, chargerVoltage: 0, chargePortDoorOpen: false, timeToFullCharge: 0, insideTemp: 0, outsideTemp: 0, isClimateOn: false, locked: false, sentryMode: false, pluggedIn: false, tirePressureFrontLeft: 0, tirePressureFrontRight: 0, tirePressureRearLeft: 0, tirePressureRearRight: 0, latitude: 0, longitude: 0, elevation: 0, speed: 0, power: 0, heading: 0, shiftState: nil)
        let adjustedBattery = min(100, max(10, base.batteryLevel + Int.random(in: -1...1)))
        return base.withBatteryLevel(adjustedBattery)
    }

    func getCars() -> [CarRaw] { data.cars }
    func getCarStatus(_ carId: Int) -> CarStatus { data.status[String(carId)] ?? CarStatus(carId: carId, state: .offline, since: "", healthy: true, odometer: 0, batteryLevel: 0, usableBatteryLevel: 0, usableBatteryRangeKm: 0, idealBatteryRangeKm: 0, chargeEnergyAdded: 0, chargeLimitSoc: 0, chargerPower: 0, chargerActualCurrent: 0, chargerVoltage: 0, chargePortDoorOpen: false, timeToFullCharge: 0, insideTemp: 0, outsideTemp: 0, isClimateOn: false, locked: false, sentryMode: false, pluggedIn: false, tirePressureFrontLeft: 0, tirePressureFrontRight: 0, tirePressureRearLeft: 0, tirePressureRearRight: 0, latitude: 0, longitude: 0, elevation: 0, speed: 0, power: 0, heading: 0, shiftState: nil) }
    func getDrives(_ carId: Int) -> [Drive] { data.drives.filter { $0.carId == carId } }
    func getCharges(_ carId: Int) -> [Charge] { data.charges.filter { $0.carId == carId } }
    func getBatteryHealth(_ carId: Int) -> BatteryHealth { data.batteryHealth[String(carId)] ?? BatteryHealth(carId: carId, date: "", batteryLevel: 0, ratedRangeKm: 0, idealRangeKm: 0, odometer: 0, outsideTemp: 0, usableBatteryLevel: 0, capacityDegradationPercent: nil, originalCapacityKwh: nil, currentCapacityKwh: nil, history: nil) }
    func getUpdates(_ carId: Int) -> [UpdateItem] { data.updates.filter { $0.carId == carId } }
    /// Sentry events. Mock payload does not carry `car_id`, so all events are returned.
    /// Real iOS endpoint not yet wired (see TODO above); SentryHistoryView treats this as mock-only.
    func getSentryEvents(_ carId: Int) -> [SentryEvent] { data.sentryEvents }
}

class MockData: Decodable {
    let cars: [CarRaw]; let status: [String: CarStatus]; let drives: [Drive]; let charges: [Charge]
    let batteryHealth: [String: BatteryHealth]; let updates: [UpdateItem]
    let sentryEvents: [SentryEvent]

    static func load() -> MockData {
        guard let url = Bundle.main.url(forResource: "mock_data", withExtension: "json"),
              let d = try? Data(contentsOf: url) else { fatalError("mock_data.json missing") }
        return try! JSONDecoder().decode(MockData.self, from: d)
    }

    private enum CodingKeys: String, CodingKey {
        case cars
        case status
        case statuses
        case drives
        case charges
        case batteryHealthDict = "batteryHealth"
        case batteryHealthArray = "battery_health"
        case updates = "software_updates"
        case sentryEvents = "sentry_events"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        cars = try container.decode([CarRaw].self, forKey: .cars)
        status =
            try container.decodeIfPresent([String: CarStatus].self, forKey: .status)
            ?? container.decode([String: CarStatus].self, forKey: .statuses)
        drives = try container.decode([Drive].self, forKey: .drives)
        charges = try container.decode([Charge].self, forKey: .charges)

        if let dict = try container.decodeIfPresent([String: BatteryHealth].self, forKey: .batteryHealthDict) {
            batteryHealth = dict
        } else {
            let entries = try container.decodeIfPresent([BatteryHealth].self, forKey: .batteryHealthArray) ?? []
            batteryHealth = Dictionary(uniqueKeysWithValues: entries.map { (String($0.carId), $0) })
        }

        updates = try container.decodeIfPresent([UpdateItem].self, forKey: .updates) ?? []
        sentryEvents = try container.decodeIfPresent([SentryEvent].self, forKey: .sentryEvents) ?? []
    }
}

private extension CarStatus {
    func withBatteryLevel(_ newBatteryLevel: Int) -> CarStatus {
        CarStatus(
            carId: carId,
            state: state,
            since: since,
            healthy: healthy,
            odometer: odometer,
            batteryLevel: newBatteryLevel,
            usableBatteryLevel: usableBatteryLevel,
            usableBatteryRangeKm: usableBatteryRangeKm,
            idealBatteryRangeKm: idealBatteryRangeKm,
            chargeEnergyAdded: chargeEnergyAdded,
            chargeLimitSoc: chargeLimitSoc,
            chargerPower: chargerPower,
            chargerActualCurrent: chargerActualCurrent,
            chargerVoltage: chargerVoltage,
            chargePortDoorOpen: chargePortDoorOpen,
            timeToFullCharge: timeToFullCharge,
            insideTemp: insideTemp,
            outsideTemp: outsideTemp,
            isClimateOn: isClimateOn,
            locked: locked,
            sentryMode: sentryMode,
            pluggedIn: pluggedIn,
            tirePressureFrontLeft: tirePressureFrontLeft,
            tirePressureFrontRight: tirePressureFrontRight,
            tirePressureRearLeft: tirePressureRearLeft,
            tirePressureRearRight: tirePressureRearRight,
            latitude: latitude,
            longitude: longitude,
            elevation: elevation,
            speed: speed,
            power: power,
            heading: heading,
            shiftState: shiftState
        )
    }
}

// MARK: - JSON‑file cache (F‑015)
actor APICache {
    static let shared = APICache()
    private let cachesDir: URL

    init() {
        let fm = FileManager.default
        cachesDir = fm.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MateLinkCache", isDirectory: true)
        try? fm.createDirectory(at: cachesDir, withIntermediateDirectories: true)
    }

    private func url(for key: String) -> URL {
        cachesDir.appendingPathComponent("\(key).json")
    }

    func write<T: Encodable>(_ value: T, key: String) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url(for: key), options: .atomic)
    }

    func read<T: Decodable>(_ type: T.Type, key: String, ttl: TimeInterval = 86400) -> T? {
        let u = url(for: key)
        guard let data = try? Data(contentsOf: u) else { return nil }
        // Check TTL — return nil if cache is too old
        if let attrs = try? FileManager.default.attributesOfItem(atPath: u.path),
           let modDate = attrs[.modificationDate] as? Date,
           Date().timeIntervalSince(modDate) > ttl { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}

extension TeslaMateAPI {
    func cacheDrives(_ drives: [Drive], carId: Int) async {
        await APICache.shared.write(drives, key: "cache_drives_\(carId)")
    }
    func getCachedDrives(carId: Int) async -> [Drive]? {
        await APICache.shared.read([Drive].self, key: "cache_drives_\(carId)")
    }
    func cacheCharges(_ charges: [Charge], carId: Int) async {
        await APICache.shared.write(charges, key: "cache_charges_\(carId)")
    }
    func getCachedCharges(carId: Int) async -> [Charge]? {
        await APICache.shared.read([Charge].self, key: "cache_charges_\(carId)")
    }
}
