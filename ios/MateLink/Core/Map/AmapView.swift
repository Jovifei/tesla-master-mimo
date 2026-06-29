// TODO: Replace with AMap SDK for China. Register at https://lbs.amap.com/
//
// When integrating the official AMap SDK:
//   1. Add pod 'AMap3DMap' / 'AMapSearch' to Podfile
//   2. Import AMapFoundationKit + MAMapKit
//   3. Register API key via AMapServices.shared().apiKey = "..."
//   4. Replace MapKit's Map with MAMapView
//
// Current implementation uses MapKit as a fallback with automatic
// WGS-84 → GCJ-02 coordinate conversion for zh-CN locale.

import SwiftUI
import MapKit
import CoreLocation

// MARK: - AmapView
/// A SwiftUI map view that displays a single annotated location.
///
/// When the device locale is `zh-CN` (or any `zh` variant), coordinates are
/// automatically converted from WGS-84 (GPS native) to GCJ-02 (Chinese
/// national standard) so the marker aligns with Chinese map services.
///
/// Usage:
/// ```swift
/// AmapView(
///     latitude: 39.913818,
///     longitude: 116.397828,
///     title: "Tiananmen"
/// )
/// .frame(height: 300)
/// ```
struct AmapView: View {

    // MARK: - Properties

    let latitude: Double
    let longitude: Double
    let title: String

    // MARK: - Body

    var body: some View {
        let coordinate = displayCoordinate
        let region = MKCoordinateRegion(
            center: coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.01, longitudeDelta: 0.01)
        )

        Map(initialPosition: .region(region)) {
            Marker(title, coordinate: coordinate)
        }
        .mapStyle(.standard)
    }

    // MARK: - Coordinate Conversion

    /// Returns the coordinate after applying GCJ-02 conversion if the
    /// current locale is a Chinese locale; otherwise returns the raw
    /// WGS-84 coordinate.
    private var displayCoordinate: CLLocationCoordinate2D {
        guard isChineseLocale else {
            return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        }
        return GCJ02Converter.wgs84ToGcj02(
            lat: latitude,
            lng: longitude
        )
    }

    /// Returns `true` when the device's primary language is Chinese,
    /// indicating that GCJ-02 conversion should be applied.
    private var isChineseLocale: Bool {
        Locale.preferredLanguages.first?.hasPrefix("zh") ?? false
    }
}

// MARK: - Preview

#Preview {
    AmapView(
        latitude: 39.913818,
        longitude: 116.397828,
        title: "Tiananmen"
    )
    .frame(height: 300)
    .padding()
}
