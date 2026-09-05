import SwiftUI

/// Displays a centered icon, title, and optional message for empty/placeholder states.
/// Optionally offers a single recovery action (mirrors Android MetricStatusPanel).
struct EmptyStateView: View {
    let title: String
    let systemImage: String
    let message: String?
    var actionLabel: String? = nil
    var onAction: (() -> Void)? = nil

    init(_ title: String, systemImage: String, message: String? = nil,
         actionLabel: String? = nil, onAction: (() -> Void)? = nil) {
        self.title = title
        self.systemImage = systemImage
        self.message = message
        self.actionLabel = actionLabel
        self.onAction = onAction
    }

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text(title)
                .font(.headline)
            if let message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            if let actionLabel, let onAction {
                Button(action: onAction) {
                    Text(actionLabel)
                        .frame(minWidth: 120)
                }
                .buttonStyle(.bordered)
                .padding(.top, 4)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
