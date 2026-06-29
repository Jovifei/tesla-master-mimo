import Foundation

/// Map utility functions for determining map provider and coordinate conversion.
enum MapUtils {

    /// Determine if the current locale is Chinese.
    /// If true, use AMap (高德地图); otherwise, use system default maps.
    static var isChineseLocale: Bool {
        guard let language = Locale.preferredLanguages.first else { return false }
        return language.hasPrefix("zh")
    }

    /// Convert WGS-84 coordinates to the appropriate coordinate system for the current map.
    /// - zh: Convert to GCJ-02 (for AMap)
    /// - Others: Keep WGS-84 (for MapKit)
    static func convertCoordinates(lat: Double, lng: Double) -> (Double, Double) {
        if isChineseLocale {
            return GCJ02Converter.wgs84ToGcj02(lat: lat, lng: lng)
        }
        return (lat, lng)
    }

    /// Get map provider name for display.
    static var mapProviderName: String {
        return isChineseLocale ? "高德地图" : "Apple Maps"
    }
}
