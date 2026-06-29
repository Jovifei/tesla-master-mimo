import WidgetKit
import SwiftUI

// MARK: - Timeline Entry

struct MateLinkWidgetEntry: TimelineEntry {
    let date: Date
    let batteryLevel: Int
    let rangeKm: Int
    let state: String
}

// MARK: - Provider

struct Provider: TimelineProvider {
    func placeholder(in context: Context) -> MateLinkWidgetEntry {
        MateLinkWidgetEntry(date: Date(), batteryLevel: 78, rangeKm: 312, state: "online")
    }

    func getSnapshot(in context: Context, completion: @escaping (MateLinkWidgetEntry) -> Void) {
        completion(MateLinkWidgetEntry(date: Date(), batteryLevel: 78, rangeKm: 312, state: "online"))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MateLinkWidgetEntry>) -> Void) {
        let defaults = UserDefaults(suiteName: "group.com.matelink")
        let entry = MateLinkWidgetEntry(
            date: Date(),
            batteryLevel: defaults?.integer(forKey: "widget_battery") ?? 78,
            rangeKm: defaults?.integer(forKey: "widget_range") ?? 312,
            state: defaults?.string(forKey: "widget_state") ?? "online"
        )
        let timeline = Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(900)))
        completion(timeline)
    }
}

// MARK: - Widget Views

struct MateLinkWidgetEntryView: View {
    var entry: MateLinkWidgetEntry
    @Environment(\.widgetFamily) var family

    var body: some View {
        switch family {
        case .systemSmall:
            smallView
        default:
            mediumView
        }
    }

    // MARK: Small Widget

    private var smallView: some View {
        VStack(spacing: 4) {
            Image(systemName: "car.fill")
                .font(.title3)
                .foregroundColor(.blue)
            Text("\(entry.batteryLevel)%")
                .font(.system(size: 36, weight: .bold))
                .foregroundColor(.primary)
            Text("\(entry.rangeKm) km")
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .containerBackground(.fill.tertiary, for: .widget)
    }

    // MARK: Medium Widget

    private var mediumView: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Image(systemName: "car.fill")
                        .foregroundColor(.blue)
                    Text("MateLink")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Text("\(entry.batteryLevel)%")
                    .font(.title.bold())
                    .foregroundColor(.primary)
                Text("\(entry.rangeKm) km range")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(statusLabel)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(statusColor.opacity(0.2))
                    .foregroundColor(statusColor)
                    .clipShape(Capsule())
                Spacer()
            }
        }
        .padding()
        .containerBackground(.fill.tertiary, for: .widget)
    }

    private var statusColor: Color {
        switch entry.state {
        case "online", "driving": return .blue
        case "charging": return .orange
        case "asleep": return .gray
        default: return .secondary
        }
    }

    private var statusLabel: String {
        entry.state.capitalized
    }
}

// MARK: - Widget Definition

struct MateLinkWidget: Widget {
    let kind = "MateLinkWidget"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: Provider()) { entry in
            MateLinkWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("MateLink")
        .description("Tesla battery & range at a glance")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

// MARK: - Preview

#if DEBUG
#Preview("Small", as: .systemSmall) {
    MateLinkWidget()
} timeline: {
    MateLinkWidgetEntry(date: .now, batteryLevel: 78, rangeKm: 312, state: "online")
}

#Preview("Medium", as: .systemMedium) {
    MateLinkWidget()
} timeline: {
    MateLinkWidgetEntry(date: .now, batteryLevel: 45, rangeKm: 180, state: "charging")
}
#endif
