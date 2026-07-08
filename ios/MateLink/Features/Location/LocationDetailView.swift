import SwiftUI

struct LocationDetailView: View {
    let status: CarStatus

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                AmapView(latitude: status.latitude, longitude: status.longitude, title: "Vehicle Location")
                    .frame(height: 280)
                    .clipShape(RoundedRectangle(cornerRadius: 8))

                VStack(spacing: 12) {
                    detailRow("Latitude", String(format: "%.6f", status.latitude))
                    detailRow("Longitude", String(format: "%.6f", status.longitude))
                    detailRow("Elevation", "\(Int(status.elevation)) m")
                    detailRow("State", StateColor.label(status.state))
                    detailRow("Since", status.since.isEmpty ? "Unavailable" : status.since)
                }
                .padding()
                .background(StitchColors.surface)
                .stitchCard()
            }
            .padding()
        }
        .navigationTitle("Location")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.body.monospacedDigit())
                .multilineTextAlignment(.trailing)
        }
    }
}
