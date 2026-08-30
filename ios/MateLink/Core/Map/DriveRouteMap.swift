import SwiftUI
import MapKit

/// MapKit-based route display for a drive segment. Shows start/end pins
/// and a polyline between them. Applies GCJ-02 conversion for Chinese locales.
/// Uses UIViewRepresentable for iOS 16 compatibility.
struct DriveRouteMap: View {
    let startLat: Double
    let startLon: Double
    let endLat: Double
    let endLon: Double
    var routeCoordinates: [CLLocationCoordinate2D] = []

    var body: some View {
        DriveRouteMapRepresentable(
            start: startCoordinate,
            end: endCoordinate,
            route: routeCoordinates.isEmpty ? [startCoordinate, endCoordinate] : routeCoordinates.map(convertIfChinese)
        )
    }

    private var startCoordinate: CLLocationCoordinate2D {
        convertIfChinese(CLLocationCoordinate2D(latitude: startLat, longitude: startLon))
    }

    private var endCoordinate: CLLocationCoordinate2D {
        convertIfChinese(CLLocationCoordinate2D(latitude: endLat, longitude: endLon))
    }

    private func convertIfChinese(_ coord: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        guard Locale.preferredLanguages.first?.hasPrefix("zh") == true else { return coord }
        return GCJ02Converter.wgs84ToGcj02(coordinate: coord)
    }
}

// MARK: - UIKit Map (iOS 16+)

private struct DriveRouteMapRepresentable: UIViewRepresentable {
    let start: CLLocationCoordinate2D
    let end: CLLocationCoordinate2D
    let route: [CLLocationCoordinate2D]

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.isScrollEnabled = true
        map.isZoomEnabled = true
        map.delegate = context.coordinator
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        map.removeAnnotations(map.annotations)
        map.removeOverlays(map.overlays)

        let startAnnotation = MKPointAnnotation()
        startAnnotation.coordinate = start
        startAnnotation.title = "Start"

        let endAnnotation = MKPointAnnotation()
        endAnnotation.coordinate = end
        endAnnotation.title = "End"

        map.addAnnotations([startAnnotation, endAnnotation])

        let polyline = MKPolyline(coordinates: route, count: route.count)
        map.addOverlay(polyline)

        let lats = route.map(\.latitude)
        let lons = route.map(\.longitude)
        let region = MKCoordinateRegion(
            center: CLLocationCoordinate2D(
                latitude: (lats.min()! + lats.max()!) / 2,
                longitude: (lons.min()! + lons.max()!) / 2
            ),
            span: MKCoordinateSpan(
                latitudeDelta: max((lats.max()! - lats.min()) * 1.5, 0.01),
                longitudeDelta: max((lons.max()! - lons.min()) * 1.5, 0.01)
            )
        )
        map.setRegion(region, animated: false)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MKMapViewDelegate {
        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let polyline = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKPolylineRenderer(polyline: polyline)
            renderer.strokeColor = UIColor.systemBlue
            renderer.lineWidth = 3
            return renderer
        }
    }
}
