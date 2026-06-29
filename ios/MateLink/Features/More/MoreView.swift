import SwiftUI

struct MoreView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        NavigationStack {
            List {
                Section("Vehicle") {
                    NavigationLink { BatteryHealthView() } label: {
                        Label("Battery Health", systemImage: "battery.100.bolt")
                    }
                    NavigationLink { StatisticsView() } label: {
                        Label("Statistics", systemImage: "chart.bar")
                    }
                    NavigationLink { UpdatesView() } label: {
                        Label("Software Updates", systemImage: "arrow.triangle.2.circlepath")
                    }
                }

                Section("Analytics") {
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
                }

                Section {
                    NavigationLink { SettingsView() } label: {
                        Label("Settings", systemImage: "gear")
                    }
                }
            }
            .navigationTitle("More")
        }
    }
}
