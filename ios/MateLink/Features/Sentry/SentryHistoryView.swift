import SwiftUI

/// L1 "Sentry History" destination — Stitch-aligned list/detail shell.
/// Reached from MoreView. Uses mock `sentry_events` (real iOS endpoint not yet wired).
/// Scope: summary + day-grouped alert rows + per-event detail. No backend completion.
struct SentryHistoryView: View {
    @EnvironmentObject var state: AppState
    @State private var events: [SentryEvent] = []
    @State private var loading = true
    @State private var endpointUnavailable = false

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading...").padding(40)
                } else if endpointUnavailable {
                    EmptyStateView(
                        "Sentry Endpoint Unavailable",
                        systemImage: "shield.slash",
                        message: "iOS real-data sentry history is not wired yet. Switch to Mock Mode to preview the shell."
                    )
                } else if events.isEmpty {
                    EmptyStateView(
                        "No Sentry Events",
                        systemImage: "shield.slash",
                        message: "Sentry alerts will appear here when triggered."
                    )
                } else {
                    listContent
                }
            }
            .navigationTitle("Sentry History")
            .navigationBarTitleDisplayMode(.large)
            .task { await load() }
        }
    }

    private var listContent: some View {
        List {
            Section {
                summaryCard
            }
            ForEach(dayGroups, id: \.label) { group in
                Section(group.label) {
                    ForEach(group.events) { ev in
                        NavigationLink(destination: SentryEventDetailView(event: ev)) {
                            SentryEventRow(event: ev)
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
    }

    // MARK: - Summary

    private var summaryCard: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(events.count)")
                    .font(.title).bold()
                Text("Total alerts").font(.caption).foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(mostRecentText)
                    .font(.subheadline).fontWeight(.medium)
                Text("Most recent").font(.caption).foregroundColor(.secondary)
            }
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .padding(.horizontal)
        .padding(.vertical, 4)
    }

    private var mostRecentText: String {
        guard let latest = events.sorted(by: { $0.startDate > $1.startDate }).first,
              let date = SentryHistoryView.parseDate(latest.startDate) else { return "—" }
        let fmt = DateFormatter()
        fmt.dateStyle = .medium
        fmt.timeStyle = .short
        return fmt.string(from: date)
    }

    // MARK: - Day grouping

    struct DayGroup {
        let label: String
        let events: [SentryEvent]
    }

    private var dayGroups: [DayGroup] {
        let cal = Calendar.current
        let sorted = events.sorted { $0.startDate > $1.startDate }
        var buckets: [(String, [SentryEvent])] = []
        for ev in sorted {
            let key = String(ev.startDate.prefix(10))
            let label: String
            if let d = SentryHistoryView.parseDate(ev.startDate) {
                if cal.isDateInToday(d) { label = "Today" }
                else if cal.isDateInYesterday(d) { label = "Yesterday" }
                else {
                    let fmt = DateFormatter()
                    fmt.dateStyle = .medium
                    label = fmt.string(from: d)
                }
            } else {
                label = key
            }
            if let idx = buckets.firstIndex(where: { $0.0 == label }) {
                buckets[idx].1.append(ev)
            } else {
                buckets.append((label, [ev]))
            }
        }
        return buckets.map { DayGroup(label: $0.0, events: $0.1) }
    }

    // MARK: - Load

    func load() async {
        loading = true
        endpointUnavailable = false
        let carId = state.currentCarId
        if state.isMockMode {
            events = await state.mock.getSentryEvents(carId)
        } else if let api = state.real {
            _ = api
            events = []
            endpointUnavailable = true
        }
        loading = false
    }

    // MARK: - Date helper

    static func parseDate(_ raw: String) -> Date? {
        let full = ISO8601DateFormatter()
        full.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = full.date(from: raw) { return d }
        let basic = ISO8601DateFormatter()
        basic.formatOptions = [.withInternetDateTime]
        return basic.date(from: raw)
    }
}

// MARK: - Event Row

private struct SentryEventRow: View {
    let event: SentryEvent

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .stroke(Color.secondary.opacity(0.35), lineWidth: 2)
                Circle()
                    .fill(Color.red)
                    .frame(width: 8, height: 8)
            }
            .frame(width: 16, height: 16)

            VStack(alignment: .leading, spacing: 2) {
                Text(event.address.flatMap { $0.isEmpty ? nil : $0 } ?? "Sentry alert")
                    .font(.subheadline)
                    .lineLimit(2)
            }
            Spacer()
            Text(timeText)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 4)
    }

    private var timeText: String {
        guard let d = SentryHistoryView.parseDate(event.startDate) else { return "" }
        let fmt = DateFormatter()
        fmt.timeStyle = .short
        return fmt.string(from: d)
    }
}

// MARK: - Detail

private struct SentryEventDetailView: View {
    let event: SentryEvent

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 8) {
                        Image(systemName: "shield.lefthalf.filled")
                            .foregroundColor(.red)
                        Text(event.address.flatMap { $0.isEmpty ? nil : $0 } ?? "Sentry alert")
                            .font(.headline).bold()
                    }
                    Divider()
                    detailRow("Started", startedText)
                    detailRow("Ended", endedText)
                    detailRow("Duration", durationText)
                    detailRow("Coordinates", String(format: "%.4f, %.4f", event.latitude, event.longitude))
                }
                .padding()
                .background(.regularMaterial)
                .clipShape(RoundedRectangle(cornerRadius: 16))

                AmapView(latitude: event.latitude, longitude: event.longitude, title: event.address ?? "Sentry event")
                    .frame(height: 200)
                    .clipShape(RoundedRectangle(cornerRadius: 16))
            }
            .padding()
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("Sentry Event")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detailRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).font(.caption).foregroundColor(.secondary)
            Spacer()
            Text(value).font(.subheadline)
        }
    }

    private var startedText: String {
        guard let d = SentryHistoryView.parseDate(event.startDate) else { return "—" }
        let fmt = DateFormatter(); fmt.dateStyle = .medium; fmt.timeStyle = .short
        return fmt.string(from: d)
    }

    private var endedText: String {
        guard let end = event.endDate, let d = SentryHistoryView.parseDate(end) else { return "—" }
        let fmt = DateFormatter(); fmt.dateStyle = .medium; fmt.timeStyle = .short
        return fmt.string(from: d)
    }

    private var durationText: String {
        guard let end = event.endDate,
              let s = SentryHistoryView.parseDate(event.startDate),
              let e = SentryHistoryView.parseDate(end) else { return "—" }
        let secs = Int(e.timeIntervalSince(s))
        if secs < 60 { return "\(secs)s" }
        return "\(secs / 60)m \(secs % 60)s"
    }
}

#Preview {
    SentryHistoryView().environmentObject(AppState())
}
