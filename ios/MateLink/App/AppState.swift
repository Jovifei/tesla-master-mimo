import SwiftUI
import Security

private enum KeychainHelper {
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
            kSecValueData as String: data
        ]
        SecItemAdd(attrs as CFDictionary, nil)
    }

    static func load(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
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

@MainActor
class AppState: ObservableObject {
    @Published var cars: [Car] = []
    @Published var currentCarId: Int = 1
    @Published var isMockMode: Bool = true
    @Published var isDarkMode: Bool = false
    @Published var onboardingDone: Bool = false
    @Published var serverURL: String = ""
    @Published var apiToken: String = ""
    @Published var selectedTab: Tab = .dashboard

    let mock = MockAPI()
    var real: TeslaMateAPI?

    enum Tab: String, CaseIterable { case dashboard, drives, charges, more
        var icon: String { switch self { case .dashboard: return "car.fill"; case .drives: return "road.lanes"; case .charges: return "bolt.fill"; case .more: return "ellipsis.circle" } }
        var label: String { switch self { case .dashboard: return "Vehicle"; case .drives: return "Drives"; case .charges: return "Charges"; case .more: return "More" } }
    }

    var currentCar: Car? { cars.first { $0.id == currentCarId } }
    var carAccent: Color { CarColor.from(currentCar?.color ?? "").accent }

    init() {
        apiToken = KeychainHelper.load("apiToken") ?? ""
        loadCars()
    }

    func loadCars() {
        Task {
            let raw = await mock.getCars(); cars = raw.map(Car.init(from:))
            if currentCarId == 1 && cars.count >= 2 { currentCarId = cars[0].id }
        }
    }

    func connect(url: String, token: String) async throws {
        let api = TeslaMateAPI(baseURL: url, token: token.isEmpty ? nil : token)
        let resp: CarApiResponse = try await api.fetch("/api/v1/cars")
        self.real = api; self.serverURL = url; self.apiToken = token; self.onboardingDone = true
        KeychainHelper.save(token, key: "apiToken")
        cars = resp.data.cars.map(Car.init(from:))
    }
}
