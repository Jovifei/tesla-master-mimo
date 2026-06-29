import Foundation

enum ApiError: LocalizedError {
    case networkUnreachable(String), unauthorized, serverError(Int, String), timeout, decodingError(String)
    var errorDescription: String? {
        switch self {
        case .networkUnreachable(let msg): return "Cannot reach server: \(msg)"
        case .unauthorized: return "Invalid token (401)"
        case .serverError(let code, let body): return "Server error \(code): \(body)"
        case .timeout: return "Connection timeout"
        case .decodingError(let msg): return "Data error: \(msg)"
        }
    }
}

// TODO(D6): iOS is missing 7 endpoints that Android has:
//   - GET /api/v1/cars/{carId}                (single car detail)
//   - GET /api/v1/cars/{carId}/charges/{chargeId}  (charge detail)
//   - GET /api/v1/cars/{carId}/charges/current     (current charge)
//   - GET /api/v1/cars/{carId}/drives/{driveId}    (drive detail)
//   - GET /api/v1/cars/{carId}/battery-health      (battery health)
//   - GET /api/v1/cars/{carId}/updates             (firmware updates)
//   - GET /api/v1/globalsettings                   (global settings)

actor TeslaMateAPI {
    private let baseURL: String; private let token: String?
    private let session: URLSession

    init(baseURL: String, token: String?) {
        self.baseURL = baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL
        self.token = token
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
}

// MARK: - Mock Client (for preview / development)
actor MockAPI {
    private let data: MockData
    init() { self.data = MockData.load() }

    func mockStatus(_ carId: Int) -> CarStatus {
        var s = data.status[String(carId)] ?? CarStatus(carId: carId, state: .offline, since: "", healthy: true, odometer: 0, batteryLevel: 0, usableBatteryLevel: 0, usableBatteryRangeKm: 0, idealBatteryRangeKm: 0, chargeEnergyAdded: 0, chargeLimitSoc: 0, chargerPower: 0, chargerActualCurrent: 0, chargerVoltage: 0, chargePortDoorOpen: false, timeToFullCharge: 0, insideTemp: 0, outsideTemp: 0, isClimateOn: false, locked: false, sentryMode: false, pluggedIn: false, tirePressureFrontLeft: 0, tirePressureFrontRight: 0, tirePressureRearLeft: 0, tirePressureRearRight: 0, latitude: 0, longitude: 0, elevation: 0, speed: 0, power: 0, heading: 0, shiftState: nil)
        s.batteryLevel = min(100, max(10, Int(s.batteryLevel) + (Int.random(in: -1...1))))
        return s
    }

    func getCars() -> [CarRaw] { data.cars }
    func getCarStatus(_ carId: Int) -> CarStatus { data.status[String(carId)] ?? CarStatus(carId: carId, state: .offline, since: "", healthy: true, odometer: 0, batteryLevel: 0, usableBatteryLevel: 0, usableBatteryRangeKm: 0, idealBatteryRangeKm: 0, chargeEnergyAdded: 0, chargeLimitSoc: 0, chargerPower: 0, chargerActualCurrent: 0, chargerVoltage: 0, chargePortDoorOpen: false, timeToFullCharge: 0, insideTemp: 0, outsideTemp: 0, isClimateOn: false, locked: false, sentryMode: false, pluggedIn: false, tirePressureFrontLeft: 0, tirePressureFrontRight: 0, tirePressureRearLeft: 0, tirePressureRearRight: 0, latitude: 0, longitude: 0, elevation: 0, speed: 0, power: 0, heading: 0, shiftState: nil) }
    func getDrives(_ carId: Int) -> [Drive] { data.drives.filter { $0.carId == carId } }
    func getCharges(_ carId: Int) -> [Charge] { data.charges.filter { $0.carId == carId } }
    func getBatteryHealth(_ carId: Int) -> BatteryHealth { data.batteryHealth[String(carId)] ?? BatteryHealth(carId: carId, date: "", batteryLevel: 0, ratedRangeKm: 0, idealRangeKm: 0, odometer: 0, outsideTemp: 0, usableBatteryLevel: 0, capacityDegradationPercent: nil, originalCapacityKwh: nil, currentCapacityKwh: nil, history: nil) }
    func getUpdates(_ carId: Int) -> [UpdateItem] { data.updates[String(carId)] ?? [] }
}

class MockData: Codable {
    let cars: [CarRaw]; let status: [String: CarStatus]; let drives: [Drive]; let charges: [Charge]
    let batteryHealth: [String: BatteryHealth]; let updates: [String: [UpdateItem]]

    static func load() -> MockData {
        guard let url = Bundle.main.url(forResource: "mock_data", withExtension: "json"),
              let d = try? Data(contentsOf: url) else { fatalError("mock_data.json missing") }
        return try! JSONDecoder().decode(MockData.self, from: d)
    }

    enum CodingKeys: String, CodingKey {
        case cars, status, drives, charges; case batteryHealth = "batteryHealth"; case updates
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
