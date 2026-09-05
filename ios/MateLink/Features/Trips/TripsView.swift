import SwiftUI

/// Long-trip list — Android `TripsViewModel` + `TripDetector`.
struct TripsView: View {
    @EnvironmentObject var state: AppState
    @State private var trips: [DetectedTrip] = []
    @State private var loading = true
    @State private var loadError: String?

    var body: some View {
        Group {
            if loading {
                ProgressView("Detecting trips…")
                    .frame(maxWidth: .infinity, minHeight: 300)
            } else if let loadError {
                EmptyStateView("Trips Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
            } else if trips.isEmpty {
                EmptyStateView(
                    "No Long Trips",
                    systemImage: MateIcons.trips,
                    message: "A trip needs at least 300 km, two drives, and one DC charge."
                )
            } else {
                List(trips) { trip in
                    VStack(alignment: .leading, spacing: 6) {
                        Text("\(trip.startAddress) → \(trip.endAddress)")
                            .font(.headline)
                            .lineLimit(2)
                        HStack {
                            Text(String(format: "%.0f km", trip.distanceKm))
                            Text("·")
                            Text("\(trip.drivingMin / 60)h \(trip.drivingMin % 60)m")
                            Text("·")
                            Text(String(format: "%.0f kWh DC", trip.energyCharged))
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        Text(String(trip.startDate.prefix(10)))
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 4)
                }
                .listStyle(.insetGrouped)
            }
        }
        .navigationTitle("Long Trips")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        loadError = nil
        do {
            let drives = try await state.loadAllDrives()
            let charges = try await state.loadAllCharges()
            let dc = charges.filter { $0.chargingType == "DC" }
            trips = TripDetector.detect(drives: drives, dcCharges: dc)
        } catch {
            loadError = error.localizedDescription
        }
        loading = false
    }
}
