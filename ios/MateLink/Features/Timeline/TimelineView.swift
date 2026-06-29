import SwiftUI

// MARK: - Timeline Event Model

struct TimelineEvent: Identifiable, Equatable {
    let id: String          // "drive_123" or "charge_456"
    let type: String        // "drive" or "charge"
    let start: Date
    let end: Date?
    let label: String       // "Drive to Work"
    let detail: String      // "Home to Office \u{00b7} 12.5 km"
    let metrics: String     // "12.5 km \u{00b7} 152 Wh/km \u{00b7} 22 min"

    var isLast: Bool = false // Controls whether the vertical line extends past this row

    static func == (lhs: TimelineEvent, rhs: TimelineEvent) -> Bool { lhs.id == rhs.id }
}

// MARK: - Date Helpers

private let iso8601Full: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
}()

private let iso8601NoFraction: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime]
    return f
}()

private func parseDate(_ raw: String) -> Date {
    if let d = iso8601Full.date(from: raw) { return d }
    if let d = iso8601NoFraction.date(from: raw) { return d }
    return Date(timeIntervalSince1970: 0)
}

private let timeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm"
    return f
}()

private let dateTimeFormatter: DateFormatter = {
    let f = DateFormatter()
    f.dateStyle = .short
    f.timeStyle = .short
    return f
}()

// MARK: - ViewModel

@MainActor
final class TimelineViewModel: ObservableObject {
    @Published var events: [TimelineEvent] = []
    @Published var isLoading = true

    func load(mock: MockAPI, carId: Int) async {
        isLoading = true
        defer { isLoading = false }

        let drives = await mock.getDrives(carId)
        let charges = await mock.getCharges(carId)

        var merged: [TimelineEvent] = []

        for d in drives {
            let start = parseDate(d.startDate)
            let end = parseDate(d.endDate)
            merged.append(TimelineEvent(
                id: "drive_\(d.id)",
                type: "drive",
                start: start,
                end: end,
                label: d.startAddress.isEmpty ? "Drive" : "Drive to \(shortAddress(d.endAddress))",
                detail: "\(d.startAddress) \u{2192} \(d.endAddress) \u{00b7} \(String(format: "%.1f", d.distanceKm)) km",
                metrics: "\(String(format: "%.1f", d.distanceKm)) km \u{00b7} \(d.efficiency) Wh/km \u{00b7} \(d.durationMin) min"
            ))
        }

        for c in charges {
            let start = parseDate(c.startDate)
            let end = c.endDate.map(parseDate)
            let typeLabel = c.chargeType.isEmpty ? "Charging" : "\(c.chargeType) Charging"
            let addr = c.address.isEmpty ? "Unknown" : shortAddress(c.address)
            merged.append(TimelineEvent(
                id: "charge_\(c.id)",
                type: "charge",
                start: start,
                end: end,
                label: typeLabel,
                detail: "\(addr) \u{00b7} \(String(format: "%.1f", c.chargeEnergyAdded)) kWh added",
                metrics: "+\(String(format: "%.1f", c.chargeEnergyAdded)) kWh \u{00b7} $\(String(format: "%.2f", c.cost))"
            ))
        }

        merged.sort { $0.start > $1.start }

        if !merged.isEmpty {
            merged[merged.count - 1].isLast = true
        }

        self.events = merged
    }
}

private func shortAddress(_ addr: String) -> String {
    let parts = addr.components(separatedBy: ", ")
    if parts.count >= 2 { return parts[0] }
    return addr.components(separatedBy: ",").first ?? addr
}

// MARK: - TimelineView

struct TimelineView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var vm = TimelineViewModel()

    var body: some View {
        Group {
            if vm.isLoading {
                ProgressView("Loading timeline\u{2026}")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if vm.events.isEmpty {
                ContentUnavailableView(
                    "No Events",
                    systemImage: "clock.badge.questionmark",
                    description: Text("Drive and charge events will appear here.")
                )
            } else {
                timelineContent
            }
        }
        .navigationTitle("Timeline")
        .navigationBarTitleDisplayMode(.large)
        .task { await vm.load(mock: state.mock, carId: state.currentCarId) }
    }

    // MARK: - Timeline Content

    private var timelineContent: some View {
        ScrollView {
            VStack(spacing: 0) {
                ForEach(vm.events) { event in
                    TimelineEventRow(event: event)
                }
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 16)
        }
    }
}

// MARK: - Event Row

struct TimelineEventRow: View {
    let event: TimelineEvent

    private var dotColor: Color {
        event.type == "drive" ? Color(red: 0.23, green: 0.51, blue: 0.96) : Color(red: 0.96, green: 0.62, blue: 0.04)
    }

    private var iconName: String {
        event.type == "drive" ? "car.fill" : "bolt.fill"
    }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            // Timeline bar
            timelineBar

            // Event card
            eventCard
                .padding(.leading, 12)
        }
        .frame(minHeight: 80)
    }

    // MARK: Timeline Bar (dot + line)

    private var timelineBar: some View {
        VStack(spacing: 0) {
            // Colored dot
            ZStack {
                Circle()
                    .fill(dotColor)
                    .frame(width: 18, height: 18)
                Circle()
                    .fill(.white)
                    .frame(width: 6, height: 6)
            }
            .padding(.top, 8)

            // Vertical connector line
            if !event.isLast {
                Rectangle()
                    .fill(Color(.systemGray5))
                    .frame(width: 2)
                    .frame(maxHeight: .infinity)
            }
        }
        .frame(width: 32)
    }

    // MARK: Event Card

    private var eventCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Top row: icon + label + time
            HStack {
                Image(systemName: iconName)
                    .font(.caption)
                    .foregroundColor(dotColor)
                Text(event.label)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Spacer()
                Text(formatTime(event.start))
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            // Detail line
            Text(event.detail)
                .font(.caption)
                .foregroundColor(.secondary)
                .lineLimit(2)

            // Metrics line
            Text(event.metrics)
                .font(.caption2)
                .fontWeight(.semibold)
                .foregroundColor(dotColor)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.vertical, 6)
    }

    // MARK: Helpers

    private func formatTime(_ date: Date) -> String {
        let now = Date()
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return timeFormatter.string(from: date)
        } else {
            return dateTimeFormatter.string(from: date)
        }
    }
}

// MARK: - Preview

#Preview {
    NavigationStack {
        TimelineView()
            .environmentObject(AppState())
    }
}
