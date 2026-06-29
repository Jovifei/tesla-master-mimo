import Foundation
import CoreLocation

// MARK: - GCJ-02 Coordinate Converter
/// Converts between WGS-84 (GPS native) and GCJ-02 (Chinese national standard)
/// coordinate systems using the well-known eviltransform algorithm.
///
/// China's regulatory requirements mandate that all maps published within China
/// use the GCJ-02 datum. Coordinates obtained from GPS are natively WGS-84.
/// This converter applies the standard offset so that vehicle positions align
/// correctly with Chinese map tile services (AMap / AutoNavi, Google Roadmap).
///
/// Algorithm reference: https://github.com/googollee/eviltransform
/// Accuracy: < 0.5 m within China; coordinates outside China pass through unchanged.
///
/// Example:
/// ```swift
/// // Tiananmen, Beijing — WGS-84 (39.913818, 116.397828) → GCJ-02 (39.91522, 116.40407)
/// let (gcjLat, gcjLng) = GCJ02Converter.wgs84ToGcj02(lat: 39.913818, lng: 116.397828)
/// ```
enum GCJ02Converter {

    // MARK: - Constants

    /// Semi-major axis of the Clarke 1866 ellipsoid used by the GCJ-02 algorithm (metres).
    private static let a: Double = 6378245.0

    /// Squared eccentricity of the Clarke 1866 ellipsoid.
    private static let ee: Double = 0.00669342162296594323

    /// Bounding box of China (approximately, includes mainland + Hong Kong + Macau + Taiwan).
    private static let chinaLatMin: Double =  0.8293
    private static let chinaLatMax: Double = 55.8271
    private static let chinaLngMin: Double = 72.004
    private static let chinaLngMax: Double = 137.8347

    /// Pi for internal calculations.
    private static let pi: Double = .pi

    // MARK: - Public API

    /// Checks whether a coordinate falls within the rough bounding box of China.
    ///
    /// Only coordinates inside this box are transformed; coordinates outside
    /// pass through unchanged.
    ///
    /// - Parameters:
    ///   - lat: Latitude in degrees (WGS-84).
    ///   - lng: Longitude in degrees (WGS-84).
    /// - Returns: `true` if the coordinate is within the China bounding box.
    static func isInChina(lat: Double, lng: Double) -> Bool {
        lat >= chinaLatMin && lat <= chinaLatMax
            && lng >= chinaLngMin && lng <= chinaLngMax
    }

    /// Converts a WGS-84 coordinate to GCJ-02.
    ///
    /// - Parameters:
    ///   - lat: Latitude in degrees (WGS-84).
    ///   - lng: Longitude in degrees (WGS-84).
    /// - Returns: A `(lat, lng)` tuple in the GCJ-02 datum.
    ///            Coordinates outside the China bounding box are returned unchanged.
    static func wgs84ToGcj02(lat: Double, lng: Double) -> (lat: Double, lng: Double) {
        guard isInChina(lat: lat, lng: lng) else { return (lat, lng) }

        let dLat = _transformLat(x: lng - 105.0, y: lat - 35.0)
        let dLng = _transformLng(x: lng - 105.0, y: lat - 35.0)

        let radLat = lat / 180.0 * pi
        var magic = sin(radLat)
        magic = 1.0 - ee * magic * magic
        let sqrtMagic = sqrt(magic)

        let dLatAdj = (dLat * 180.0) / ((a * (1.0 - ee)) / (magic * sqrtMagic) * pi)
        let dLngAdj = (dLng * 180.0) / (a / sqrtMagic * cos(radLat) * pi)

        return (lat + dLatAdj, lng + dLngAdj)
    }

    /// Converts a GCJ-02 coordinate back to WGS-84 using iterative approximation.
    ///
    /// Since the GCJ-02 transform is not analytically invertible, this method
    /// uses a fixed-point iteration that typically converges within 2-3 passes.
    ///
    /// - Parameters:
    ///   - lat: Latitude in degrees (GCJ-02).
    ///   - lng: Longitude in degrees (GCJ-02).
    ///   - iterations: Number of approximation iterations. Default is 3.
    /// - Returns: A `(lat, lng)` tuple in the WGS-84 datum.
    ///            Coordinates outside the China bounding box are returned unchanged.
    static func gcj02ToWgs84(lat: Double, lng: Double, iterations: Int = 3) -> (lat: Double, lng: Double) {
        guard isInChina(lat: lat, lng: lng) else { return (lat, lng) }

        var wgsLat = lat
        var wgsLng = lng

        for _ in 0..<max(iterations, 1) {
            let (gcjLat, gcjLng) = wgs84ToGcj02(lat: wgsLat, lng: wgsLng)
            wgsLat += lat - gcjLat
            wgsLng += lng - gcjLng
        }

        return (wgsLat, wgsLng)
    }

    /// Convenience overload that accepts and returns `CLLocationCoordinate2D`.
    ///
    /// - Parameter coordinate: A WGS-84 coordinate.
    /// - Returns: A GCJ-02 coordinate.
    static func wgs84ToGcj02(coordinate: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        let result = wgs84ToGcj02(lat: coordinate.latitude, lng: coordinate.longitude)
        return CLLocationCoordinate2D(latitude: result.lat, longitude: result.lng)
    }

    /// Convenience overload that accepts and returns `CLLocationCoordinate2D`.
    ///
    /// - Parameter coordinate: A GCJ-02 coordinate.
    /// - Returns: A WGS-84 coordinate.
    static func gcj02ToWgs84(coordinate: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        let result = gcj02ToWgs84(lat: coordinate.latitude, lng: coordinate.longitude)
        return CLLocationCoordinate2D(latitude: result.lat, longitude: result.lng)
    }

    // MARK: - Private Transform Helpers

    /// Computes the latitude offset for the GCJ-02 transformation.
    ///
    /// - Parameters:
    ///   - x: Longitude relative to the central meridian (lng - 105.0).
    ///   - y: Latitude relative to the central parallel (lat - 35.0).
    /// - Returns: The raw latitude delta before ellipsoid correction.
    private static func _transformLat(x: Double, y: Double) -> Double {
        var result = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        result += (20.0 * sin(y * pi) + 40.0 * sin(y / 3.0 * pi)) * 2.0 / 3.0
        result += (160.0 * sin(y / 12.0 * pi) + 320.0 * sin(y * pi / 30.0)) * 2.0 / 3.0
        return result
    }

    /// Computes the longitude offset for the GCJ-02 transformation.
    ///
    /// - Parameters:
    ///   - x: Longitude relative to the central meridian (lng - 105.0).
    ///   - y: Latitude relative to the central parallel (lat - 35.0).
    /// - Returns: The raw longitude delta before ellipsoid correction.
    private static func _transformLng(x: Double, y: Double) -> Double {
        var result = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        result += (20.0 * sin(6.0 * x * pi) + 20.0 * sin(2.0 * x * pi)) * 2.0 / 3.0
        result += (20.0 * sin(x * pi) + 40.0 * sin(x / 3.0 * pi)) * 2.0 / 3.0
        result += (150.0 * sin(x / 12.0 * pi) + 300.0 * sin(x * pi / 30.0)) * 2.0 / 3.0
        return result
    }
}
