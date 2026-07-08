import SwiftUI

// MARK: - Drive Heatmap View
// GitHub-style 15-day × 24-hour activity heatmap.

struct HeatmapView: View {
    @EnvironmentObject var state: AppState
    @State private var drives: [Drive] = []
    @State private var loading = true

    // grid[hour][day] = total km driven in that hour on that day
    @State private var grid: [[Double]] = []
    @State private var maxValue: Double = 1
    @State private var selectedInfo: String?
    @State private var loadError: String?

    private static let dayCount = 15

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    var body: some View {
        NavigationStack {
            Group {
                if loading {
                    ProgressView("Loading heatmap...")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if let loadError {
                    EmptyStateView("Heatmap Unavailable", systemImage: "exclamationmark.triangle", message: loadError)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    content
                }
            }
            .navigationTitle("Drive Heatmap")
            .background(Color(.systemGroupedBackground))
            .task { await load() }
        }
    }

    // MARK: - Main Content

    private var content: some View {
        VStack(alignment: .leading, spacing: 16) {
            // Description
            Text("GitHub-style activity heatmap \u{2014} \(Self.dayCount) days \u{00D7} 24 hours")
                .font(.subheadline)
                .foregroundColor(.secondary)
                .padding(.horizontal)

            // Heatmap Grid
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 3) {
                    hourLabelsColumn
                    dayColumns
                }
                .padding(.horizontal)
                .padding(.vertical, 4)
            }

            // Legend
            legend

            // Tooltip
            if let info = selectedInfo {
                Text(info)
                    .font(.caption.weight(.medium))
                    .foregroundColor(.blue)
                    .padding(.horizontal)
                    .transition(.opacity)
            }

            Spacer()
        }
        .padding(.vertical)
    }

    // MARK: - Hour Labels

    private var hourLabelsColumn: some View {
        VStack(alignment: .trailing, spacing: 3) {
            // Spacer for day label row
            Color.clear
                .frame(width: 22, height: 18)

            ForEach(stride(from: 0, to: 24, by: 3).map { $0 }, id: \.self) { hour in
                Text(String(format: "%02d", hour))
                    .font(.system(size: 10, weight: .medium))
                    .foregroundColor(.secondary)
                    .frame(height: 16)
            }
        }
        .padding(.trailing, 6)
    }

    // MARK: - Day Columns

    private var dayColumns: some View {
        let today = Date()
        let calendar = Calendar.current

        return HStack(alignment: .top, spacing: 3) {
            ForEach(0..<Self.dayCount, id: \.self) { col in
                // Calculate the date for this column (leftmost = 14 days ago, rightmost = today)
                let date = calendar.date(byAdding: .day, value: -(Self.dayCount - 1 - col), to: today)!

                VStack(spacing: 3) {
                    // Day label (every 3rd column, show day number)
                    if col % 3 == 0 {
                        Text("\(calendar.component(.day, from: date))")
                            .font(.system(size: 10, weight: .medium))
                            .foregroundColor(.secondary)
                            .frame(height: 18)
                    } else {
                        Color.clear
                            .frame(height: 18)
                    }

                    // 24 hour cells
                    ForEach(0..<24, id: \.self) { hour in
                        let value = grid.indices.contains(hour) && grid[hour].indices.contains(col)
                            ? grid[hour][col]
                            : 0
                        cellView(value: value)
                            .onTapGesture {
                                let dayLabel = DateFormatter.localizedString(
                                    from: date,
                                    dateStyle: .medium,
                                    timeStyle: .none
                                )
                                if value > 0 {
                                    selectedInfo = "\(String(format: "%02d", hour)):00 \u{00B7} \(String(format: "%.1f", value)) km \u{00B7} \(dayLabel)"
                                } else {
                                    selectedInfo = "\(String(format: "%02d", hour)):00 \u{00B7} No driving \u{00B7} \(dayLabel)"
                                }
                            }
                    }
                }
            }
        }
    }

    // MARK: - Cell View

    private func cellView(value: Double) -> some View {
        Rectangle()
            .fill(cellColor(value: value))
            .frame(width: 16, height: 16)
            .cornerRadius(3)
    }

    // MARK: - Color Scale

    private func cellColor(value: Double) -> Color {
        guard maxValue > 0, value > 0 else {
            return Color(.systemGray6)
        }
        let ratio = value / maxValue
        if ratio < 0.25 {
            return Color.blue.opacity(0.18)
        } else if ratio < 0.50 {
            return Color.blue.opacity(0.38)
        } else if ratio < 0.75 {
            return Color.blue.opacity(0.60)
        } else {
            return Color.blue.opacity(0.85)
        }
    }

    // MARK: - Legend

    private var legend: some View {
        HStack(spacing: 8) {
            Text("Less")
                .font(.caption2)
                .foregroundColor(.secondary)

            ForEach([0.0, 0.25, 0.5, 0.75, 1.0], id: \.self) { level in
                RoundedRectangle(cornerRadius: 2)
                    .fill(cellColor(value: level * maxValue))
                    .frame(width: 14, height: 14)
            }

            Text("More")
                .font(.caption2)
                .foregroundColor(.secondary)
        }
        .padding(.horizontal)
    }

    // MARK: - Data Loading

    private func load() async {
        loading = true
        loadError = nil
        if state.isMockMode {
            drives = await state.mock.getDrives(state.currentCarId)
        } else if let api = state.real {
            do {
                drives = try await api.fetch("/api/v1/cars/\(state.currentCarId)/drives")
            } catch {
                drives = []
                grid = []
                loadError = "Unable to load real drive data: \(error.localizedDescription)"
                loading = false
                return
            }
        } else {
            drives = []
            grid = []
            loadError = "No TeslaMate instance is configured."
            loading = false
            return
        }
        buildGrid()
        loading = false
    }

    private func buildGrid() {
        let calendar = Calendar.current
        let now = Date()
        var g = Array(repeating: Array(repeating: 0.0, count: Self.dayCount), count: 24)

        for drive in drives {
            guard let date = parseDate(drive.startDate) else { continue }

            let hour = calendar.component(.hour, from: date)
            let dayOffset = calendar.dateComponents([.day], from: date, to: now).day ?? -1

            // Only include drives within the 15-day window
            guard dayOffset >= 0, dayOffset < Self.dayCount else { continue }

            // Rightmost column = today (offset 0), leftmost = 14 days ago
            let col = Self.dayCount - 1 - dayOffset
            g[hour][col] += drive.distanceKm
        }

        grid = g
        maxValue = max(1, g.flatMap { $0 }.max() ?? 1)
    }

    private func parseDate(_ iso: String) -> Date? {
        if let d = Self.isoFormatter.date(from: iso) { return d }
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return f2.date(from: iso)
    }
}

// MARK: - Preview

#Preview("Heatmap") {
    let state = AppState()
    return HeatmapView()
        .environmentObject(state)
}
