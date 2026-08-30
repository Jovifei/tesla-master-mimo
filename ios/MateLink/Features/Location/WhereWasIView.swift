import SwiftUI

struct WhereWasIView: View {
    @EnvironmentObject var state: AppState
    @State private var activity: String = "Unknown"
    @State private var detail: String = ""
    @State private var loading = true
    @State private var loadError: String?

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, minHeight: 300)
            } else if let loadError {
                EmptyStateView("Location Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
            } else {
                List {
                    Section("Now") {
                        LabeledContent("Activity", value: activity)
                        Text(detail)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
        .navigationTitle("Where Was I?")
        .task { await load() }
        .refreshable { await load() }
    }

    private func load() async {
        loading = true
        loadError = nil
        let now = Date()
        do {
            if state.isMockMode {
                let status = await state.mock.mockStatus(state.currentCarId)
                apply(status: status, drives: await state.mock.getDrives(state.currentCarId),
                      charges: await state.mock.getCharges(state.currentCarId), now: now)
            } else if let api = state.real {
                let status: CarStatus = try await api.fetch("/api/v1/cars/\(state.currentCarId)/status")
                let drives = try await api.getAllDrives(carId: state.currentCarId)
                let charges = try await api.getAllCharges(carId: state.currentCarId)
                apply(status: status, drives: drives, charges: charges, now: now)
            } else {
                loadError = "No TeslaMate instance is configured."
            }
        } catch {
            loadError = error.localizedDescription
        }
        loading = false
    }

    private func apply(status: CarStatus, drives: [Drive], charges: [Charge], now: Date) {
        if let drive = drives.first(where: { isActive($0.startDate, $0.endDate, now: now) }) {
            activity = "Driving"
            detail = "\(drive.startAddress) → \(drive.endAddress)"
            return
        }
        if let charge = charges.first(where: { isActive($0.startDate, $0.endDate, now: now) }) {
            activity = "Charging"
            detail = charge.address ?? "Unknown location"
            return
        }
        if status.isCharging {
            activity = "Charging"
            detail = status.geofence.isEmpty ? "Plugged in" : status.geofence
            return
        }
        activity = "Parked"
        detail = status.geofence.isEmpty
            ? String(format: "%.5f, %.5f", status.latitude, status.longitude)
            : status.geofence
    }

    private func isActive(_ start: String, _ end: String?, now: Date) -> Bool {
        guard let startDate = HistoryDateFilter.parseISO(start) else { return false }
        let endDate = end.flatMap(HistoryDateFilter.parseISO) ?? now.addingTimeInterval(3600)
        return now >= startDate && now <= endDate
    }
}
