import SwiftUI

/// Reusable stat card for displaying a single metric with title, value, subtitle.
/// Used across Dashboard, Cost, Battery, and other analytics views.
struct StatCard: View {
    let title: String
    let value: String
    let subtitle: String
    var color: Color = .primary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.largeTitle.bold())
                .foregroundStyle(color)
                .monospacedDigit()
            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }
}
