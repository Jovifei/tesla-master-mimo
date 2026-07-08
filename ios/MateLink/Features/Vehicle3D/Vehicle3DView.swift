import SwiftUI

struct Vehicle3DView: View {
    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "car.2")
                .font(.system(size: 56, weight: .semibold))
                .foregroundColor(.secondary)

            VStack(spacing: 8) {
                Text("3D Vehicle Preview")
                    .font(.title2.weight(.semibold))
                Text("This entry is wired for Android and iOS parity. The native iOS 3D renderer is deferred until the model/rendering stack is selected.")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
        .navigationTitle("3D Vehicle")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    NavigationStack {
        Vehicle3DView()
    }
}
