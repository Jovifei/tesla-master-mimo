import SwiftUI

/// Type-safe navigation routes mirroring Android's `Screen` sealed interface
/// in NavGraph.kt. Each case maps to exactly one SwiftUI view.
///
/// Tabs (top-level): dashboard, drives, charges, more
/// Detail routes (pushed via NavigationStack): all others
enum Route: Hashable {
    // MARK: - Tabs (managed by TabView, listed here for reference)
    // case dashboard, drives, charges, more  — handled by TabView.selection

    // MARK: - Drive Detail
    case driveDetail(carId: Int, driveId: Int)

    // MARK: - Charge Detail
    case chargeDetail(carId: Int, chargeId: Int)
    case currentCharge(carId: Int)

    // MARK: - Vehicle
    case battery(carId: Int)
    case mileage(carId: Int)
    case updates(carId: Int)
    case statistics(carId: Int)

    // MARK: - Analytics
    case efficiency(carId: Int)
    case cost(carId: Int)
    case range(carId: Int)
    case vampire(carId: Int)
    case timeline(carId: Int)

    // MARK: - History
    case sentryHistory(carId: Int)

    // MARK: - Reports
    case annualReport(carId: Int)
    case export(carId: Int)

    // MARK: - Trips
    case trips(carId: Int)
    case tripDetail(carId: Int, tripStartDate: String)
    case createTrip(carId: Int)

    // MARK: - TPMS
    case tpmsTrend(carId: Int)

    // MARK: - Countries
    case countriesVisited(carId: Int)
    case regionsVisited(carId: Int, countryCode: String, countryName: String)

    // MARK: - Location
    case whereWasI(carId: Int, timestamp: String)

    // MARK: - System
    case settings
    case about
    case tariffConfig
    case tpmsSettings

    // MARK: - Readiness
    case dataReadiness(carId: Int)
}

// MARK: - NavigationLink Convenience

extension View {
    /// Create a NavigationLink from any Route.
    func navigationRoute(_ route: Route, @ViewBuilder destination: @escaping () -> some View) -> some View {
        NavigationLink(value: route) {
            destination()
        }
    }
}
