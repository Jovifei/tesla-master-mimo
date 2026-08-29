import SwiftUI

struct MoreView: View {
    @EnvironmentObject var state: AppState
    @Environment(\.carPalette) private var palette

    var body: some View {
        List {
            // MARK: - Vehicle
            Section(L10n.string("more.vehicle")) {
                NavigationLink(value: Route.currentCharge(carId: state.currentCarId)) {
                    Label("Current Charge", systemImage: MateIcons.charging)
                }
                NavigationLink(value: Route.battery(carId: state.currentCarId)) {
                    Label(L10n.string("battery_health.title"), systemImage: MateIcons.battery)
                }
                NavigationLink(value: Route.statistics(carId: state.currentCarId)) {
                    Label("Statistics", systemImage: MateIcons.statistics)
                }
                NavigationLink(value: Route.updates(carId: state.currentCarId)) {
                    Label("Software Updates", systemImage: MateIcons.updates)
                }
            }

            // MARK: - Analytics
            Section(L10n.string("more.analytics")) {
                NavigationLink(value: Route.mileage(carId: state.currentCarId)) {
                    Label("Mileage", systemImage: MateIcons.mileage)
                }
                NavigationLink(value: Route.efficiency(carId: state.currentCarId)) {
                    Label("Efficiency", systemImage: MateIcons.efficiency)
                }
                NavigationLink(value: Route.cost(carId: state.currentCarId)) {
                    Label("Charging Cost", systemImage: MateIcons.cost)
                }
                NavigationLink(value: Route.range(carId: state.currentCarId)) {
                    Label("Range Analysis", systemImage: MateIcons.range)
                }
                NavigationLink(value: Route.vampire(carId: state.currentCarId)) {
                    Label("Vampire Drain", systemImage: MateIcons.vampire)
                }
            }

            // MARK: - Reports
            Section(L10n.string("more.reports")) {
                NavigationLink(value: Route.annualReport(carId: state.currentCarId)) {
                    Label("Annual Report", systemImage: MateIcons.annualReport)
                }
                NavigationLink(value: Route.export(carId: state.currentCarId)) {
                    Label("Export Data", systemImage: MateIcons.export)
                }
            }

            // MARK: - History
            Section(L10n.string("more.history")) {
                NavigationLink(value: Route.timeline(carId: state.currentCarId)) {
                    Label("Timeline", systemImage: MateIcons.timeline)
                }
                NavigationLink(value: Route.sentryHistory(carId: state.currentCarId)) {
                    Label("Sentry History", systemImage: MateIcons.sentryHistory)
                }
            }

            // MARK: - System
            Section(L10n.string("more.system")) {
                NavigationLink(value: Route.settings) {
                    Label(L10n.string("settings.title"), systemImage: MateIcons.settings)
                }
                NavigationLink(value: Route.about) {
                    Label(L10n.string("about"), systemImage: MateIcons.about)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(L10n.string("nav.more"))
    }
}
