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
        onboardingDone = UserDefaults.standard.bool(forKey: AppStateStorageKeys.onboardingDone)
        let savedMockMode = UserDefaults.standard.object(forKey: AppStateStorageKeys.mockMode) as? Bool
        if onboardingDone && !serverURL.isEmpty {
            real = TeslaMateAPI(baseURL: serverURL, token: apiToken.isEmpty ? nil : apiToken)
            isMockMode = savedMockMode ?? false
        } else {
            isMockMode = true
        }
        loadCars()
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
}
