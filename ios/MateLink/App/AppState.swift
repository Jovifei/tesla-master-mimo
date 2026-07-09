import SwiftUI
import Security

private enum KeychainHelper {
    // 仅本机、解锁时可访问，且不通过 iCloud 钥匙串同步，防止 token 跨设备泄露
    private static let accessibility = kSecAttrAccessibleWhenUnlockedThisDeviceOnly

    static func save(_ value: String, key: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
        let attrs: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: accessibility
        ]
        SecItemAdd(attrs as CFDictionary, nil)
    }

    static func load(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecAttrAccessible as String: accessibility
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(_ key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}

private enum AppStateStorageKeys {
    static let onboardingDone = "matelink.onboardingDone"
    static let serverURL = "matelink.serverURL"
    static let mockMode = "matelink.isMockMode"
    static let instances = "matelink.instances"
    static let activeInstanceID = "matelink.activeInstanceID"
}

struct TeslaMateInstance: Identifiable, Codable, Equatable {
    var id: String
    var name: String
    var serverURL: String
    var carId: Int

    init(id: String = UUID().uuidString, name: String, serverURL: String, carId: Int = 1) {
        self.id = id
        self.name = name
        self.serverURL = serverURL
        self.carId = carId
    }
}

@MainActor
class AppState: ObservableObject {
    @Published var cars: [Car] = []
    @Published var currentCarId: Int = 1
    @Published var isMockMode: Bool = true {
        didSet { UserDefaults.standard.set(isMockMode, forKey: AppStateStorageKeys.mockMode) }
    }
    @Published var isDarkMode: Bool = false
    @Published var onboardingDone: Bool = false {
        didSet { UserDefaults.standard.set(onboardingDone, forKey: AppStateStorageKeys.onboardingDone) }
    }
    @Published var serverURL: String = "" {
        didSet { UserDefaults.standard.set(serverURL, forKey: AppStateStorageKeys.serverURL) }
    }
    @Published var apiToken: String = ""
    @Published var instances: [TeslaMateInstance] = [] {
        didSet { saveInstances() }
    }
    @Published var activeInstanceID: String? = nil {
        didSet { UserDefaults.standard.set(activeInstanceID, forKey: AppStateStorageKeys.activeInstanceID) }
    }
    @Published var selectedTab: Tab = .dashboard

    let mock = MockAPI()
    var real: TeslaMateAPI?

    enum Tab: String, CaseIterable { case dashboard, drives, charges, more
        var icon: String { switch self { case .dashboard: return "car.fill"; case .drives: return "road.lanes"; case .charges: return "bolt.fill"; case .more: return "ellipsis.circle" } }
        var labelKey: String {
            switch self {
            case .dashboard: return "nav.dashboard"
            case .drives: return "nav.drives"
            case .charges: return "nav.charges"
            case .more: return "nav.more"
            }
        }

        var label: String { L10n.string(labelKey) }
    }

    var currentCar: Car? { cars.first { $0.id == currentCarId } }
    var carAccent: Color { CarColor.from(currentCar?.color ?? "").accent }

    init() {
        apiToken = KeychainHelper.load("apiToken") ?? ""
        serverURL = UserDefaults.standard.string(forKey: AppStateStorageKeys.serverURL) ?? ""
        instances = AppState.loadInstances()
        activeInstanceID = UserDefaults.standard.string(forKey: AppStateStorageKeys.activeInstanceID)
        onboardingDone = UserDefaults.standard.bool(forKey: AppStateStorageKeys.onboardingDone)
        let savedMockMode = UserDefaults.standard.object(forKey: AppStateStorageKeys.mockMode) as? Bool
        if onboardingDone && !serverURL.isEmpty {
            ensureLegacyInstance()
            real = TeslaMateAPI(baseURL: serverURL, token: apiToken.isEmpty ? nil : apiToken)
            isMockMode = savedMockMode ?? false
        } else {
            isMockMode = true
        }
        loadCars()
    }

    private static func loadInstances() -> [TeslaMateInstance] {
        guard let data = UserDefaults.standard.data(forKey: AppStateStorageKeys.instances),
              let decoded = try? JSONDecoder().decode([TeslaMateInstance].self, from: data) else {
            return []
        }
        return decoded
    }

    private func saveInstances() {
        guard let data = try? JSONEncoder().encode(instances) else { return }
        UserDefaults.standard.set(data, forKey: AppStateStorageKeys.instances)
    }

    private func tokenKey(for instanceID: String) -> String {
        "apiToken.\(instanceID)"
    }

    private func ensureLegacyInstance() {
        guard instances.isEmpty, !serverURL.isEmpty else { return }
        let instance = TeslaMateInstance(name: "TeslaMate", serverURL: serverURL, carId: currentCarId)
        instances = [instance]
        activeInstanceID = instance.id
        if !apiToken.isEmpty {
            KeychainHelper.save(apiToken, key: tokenKey(for: instance.id))
        }
    }

    func loadCars() {
        Task {
            if !isMockMode, let api = real {
                do {
                    let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
                    cars = resp.data.cars.map(Car.init(from:))
                    if !cars.contains(where: { $0.id == currentCarId }), let firstCar = cars.first {
                        currentCarId = firstCar.id
                    }
                    return
                } catch {
                    cars = []
                    return
                }
            }

            let raw = await mock.getCars()
            cars = raw.map(Car.init(from:))
            if !cars.contains(where: { $0.id == currentCarId }), let firstCar = cars.first {
                currentCarId = firstCar.id
            }
        }
    }

    func connect(url: String, token: String) async throws {
        let api = TeslaMateAPI(baseURL: url, token: token.isEmpty ? nil : token)
        let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
        let firstCarId = resp.data.cars.first.map { Car(from: $0) }?.id ?? currentCarId
        let instance = TeslaMateInstance(
            id: activeInstanceID ?? UUID().uuidString,
            name: instances.first(where: { $0.id == activeInstanceID })?.name ?? "TeslaMate",
            serverURL: url,
            carId: firstCarId
        )
        upsertInstance(instance, token: token)
        activeInstanceID = instance.id
        self.real = api
        self.isMockMode = false
        self.serverURL = url
        self.apiToken = token
        self.onboardingDone = true
        if token.isEmpty {
            KeychainHelper.delete("apiToken")
        } else {
            KeychainHelper.save(token, key: "apiToken")
        }
        cars = resp.data.cars.map(Car.init(from:))
        if !cars.contains(where: { $0.id == currentCarId }), let firstCar = cars.first {
            currentCarId = firstCar.id
        }
    }

    func saveInstance(id: String?, name: String, url: String, token: String) async throws {
        let normalizedURL = url.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let api = TeslaMateAPI(baseURL: normalizedURL, token: token.isEmpty ? nil : token)
        let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
        let cars = resp.data.cars.map(Car.init(from:))
        let instance = TeslaMateInstance(
            id: id ?? UUID().uuidString,
            name: name.isEmpty ? "TeslaMate" : name,
            serverURL: normalizedURL,
            carId: cars.first?.id ?? 1
        )
        upsertInstance(instance, token: token)
        try await switchInstance(instance.id)
    }

    func switchInstance(_ id: String) async throws {
        guard let instance = instances.first(where: { $0.id == id }) else { return }
        let token = KeychainHelper.load(tokenKey(for: id)) ?? ""
        let api = TeslaMateAPI(baseURL: instance.serverURL, token: token.isEmpty ? nil : token)
        let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
        activeInstanceID = id
        real = api
        isMockMode = false
        serverURL = instance.serverURL
        apiToken = token
        onboardingDone = true
        cars = resp.data.cars.map(Car.init(from:))
        currentCarId = instance.carId
        if !cars.contains(where: { $0.id == currentCarId }), let firstCar = cars.first {
            currentCarId = firstCar.id
        }
    }

    func deleteInstance(_ id: String) {
        KeychainHelper.delete(tokenKey(for: id))
        instances.removeAll { $0.id == id }
        if activeInstanceID == id {
            activeInstanceID = instances.first?.id
            if let nextID = activeInstanceID {
                Task { try? await switchInstance(nextID) }
            } else {
                real = nil
                serverURL = ""
                apiToken = ""
                isMockMode = true
                onboardingDone = false
                loadCars()
            }
        }
    }

    private func upsertInstance(_ instance: TeslaMateInstance, token: String) {
        if let index = instances.firstIndex(where: { $0.id == instance.id }) {
            instances[index] = instance
        } else {
            instances.append(instance)
        }
        if token.isEmpty {
            KeychainHelper.delete(tokenKey(for: instance.id))
        } else {
            KeychainHelper.save(token, key: tokenKey(for: instance.id))
        }
    }
}
