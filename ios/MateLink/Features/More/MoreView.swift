import SwiftUI

struct MoreView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        NavigationStack {
            List {
                Section("Vehicle") {
                    NavigationLink { CurrentChargeView() } label: {
                        Label("Current Charge", systemImage: "bolt.circle")
                    }
                    NavigationLink { BatteryHealthView() } label: {
                        Label(L10n.string("battery_health.title"), systemImage: "battery.100.bolt")
                    }
                    NavigationLink { StatisticsView() } label: {
                        Label("Statistics", systemImage: "chart.bar")
                    }
                    NavigationLink { UpdatesView() } label: {
                        Label("Software Updates", systemImage: "arrow.triangle.2.circlepath")
                    }
                }

                Section("Analytics") {
                    NavigationLink { MileageView() } label: {
                        Label("Mileage", systemImage: "map")
                    }
                    NavigationLink { HeatmapView() } label: {
                        Label("Activity Heatmap", systemImage: "calendar")
                    }
                    NavigationLink { EfficiencyView() } label: {
                        Label("Efficiency Curve", systemImage: "chart.xyaxis.line")
                    }
                    NavigationLink { DestinationsView() } label: {
                        Label("Top Destinations", systemImage: "mappin.circle")
                    }
                    NavigationLink { RangeView() } label: {
                        Label("Range Analysis", systemImage: "gauge")
                    }
                }

                Section("Reports") {
                    NavigationLink { AnnualReportPDFView() } label: {
                        Label("Annual Report", systemImage: "doc.text")
                    }
                    NavigationLink { ExportView() } label: {
                        Label("Export Data", systemImage: "square.and.arrow.up")
                    }
                    NavigationLink { Vehicle3DView() } label: {
                        Label("3D Vehicle Preview", systemImage: "car.2")
                    }
                }

                Section("History") {
                    NavigationLink { TimelineView() } label: {
                        Label("Timeline", systemImage: "clock")
                    }
                    NavigationLink { VampireView() } label: {
                        Label("Vampire Drain", systemImage: "moon.zzz")
                    }
                    NavigationLink { CostView() } label: {
                        Label("Charging Cost", systemImage: "dollarsign.circle")
                    }
                    NavigationLink { SentryHistoryView() } label: {
                        Label("Sentry History", systemImage: "shield.lefthalf.filled")
                    }
                }

                Section("System") {
                    NavigationLink { SettingsView() } label: {
                        Label(L10n.string("settings.title"), systemImage: "gear")
                    }
                    NavigationLink { AboutView() } label: {
                        Label(L10n.string("about"), systemImage: "info.circle")
                    }
                }
            }
            .navigationTitle(L10n.string("nav.more"))
        }
    }
}
