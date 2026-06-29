import Foundation
import CoreGraphics

// MARK: - Route Simplifier
/// Implements the Douglas-Peucker algorithm for polyline simplification.
///
/// Use this to reduce the number of points in a GPS track or route while
/// preserving the overall shape within a configurable tolerance.
///
/// Example:
/// ```swift
/// let points: [(x: Double, y: Double)] = [
///     (x: 121.5, y: 25.0),
///     (x: 121.6, y: 25.1),
///     (x: 121.7, y: 25.0),
///     (x: 121.9, y: 24.9),
/// ]
/// let simplified = RouteSimplifier.simplify(points: points, tolerance: 0.01)
/// ```
enum RouteSimplifier {

    /// Simplifies an array of coordinate points using the Douglas-Peucker algorithm.
    ///
    /// - Parameters:
    ///   - points: Array of `(x: Double, y: Double)` tuples representing the polyline.
    ///   - epsilon: Perpendicular distance tolerance. Points whose perpendicular
    ///              distance to the approximating line segment exceeds `epsilon`
    ///              are retained. Default is `1.0`.
    /// - Returns: A simplified array of points. The first and last points of the
    ///            input are always preserved.
    static func simplify(points: [(x: Double, y: Double)], tolerance epsilon: Double = 1.0) -> [(x: Double, y: Double)] {
        guard points.count > 2 else { return points }

        let first = points.first!
        let last = points.last!

        // Find the point farthest from the line segment [first, last]
        var maxDistance: Double = 0
        var maxIndex: Int = 0

        for i in 1..<(points.count - 1) {
            let dist = perpendicularDistance(from: points[i], toLineSegment: first, last)
            if dist > maxDistance {
                maxDistance = dist
                maxIndex = i
            }
        }

        // If max distance exceeds epsilon, recurse
        if maxDistance > epsilon {
            let left = simplify(points: Array(points[0...maxIndex]), tolerance: epsilon)
            let right = simplify(points: Array(points[maxIndex..<points.count]), tolerance: epsilon)
            // Remove duplicate end/start point between the two segments
            return left.dropLast() + right
        } else {
            return [first, last]
        }
    }

    /// Simplifies an array of `CGPoint` values.
    ///
    /// A convenience wrapper around `simplify(points:tolerance:)`.
    ///
    /// - Parameters:
    ///   - points: Array of `CGPoint` values.
    ///   - epsilon: Tolerance value.
    /// - Returns: Simplified array of `CGPoint`.
    static func simplify(cgPoints points: [CGPoint], tolerance epsilon: Double = 1.0) -> [CGPoint] {
        let tuples = points.map { (x: Double($0.x), y: Double($0.y)) }
        let simplified = simplify(points: tuples, tolerance: epsilon)
        return simplified.map { CGPoint(x: $0.x, y: $0.y) }
    }

    // MARK: - Private Helpers

    /// Computes the perpendicular distance from `point` to the infinite line
    /// defined by `lineStart` and `lineEnd`.
    private static func perpendicularDistance(
        from point: (x: Double, y: Double),
        toLineSegment lineStart: (x: Double, y: Double),
        _ lineEnd: (x: Double, y: Double)
    ) -> Double {
        let dx = lineEnd.x - lineStart.x
        let dy = lineEnd.y - lineStart.y
        let lengthSquared = dx * dx + dy * dy

        guard lengthSquared > 0 else {
            // Line segment is effectively a point; return Euclidean distance
            let px = point.x - lineStart.x
            let py = point.y - lineStart.y
            return sqrt(px * px + py * py)
        }

        // Perpendicular distance from point to an infinite line:
        // |(y2 - y1)*x0 - (x2 - x1)*y0 + x2*y1 - y2*x1| / sqrt((x2-x1)^2 + (y2-y1)^2)
        let numerator = abs(
            dy * point.x
            - dx * point.y
            + lineEnd.x * lineStart.y
            - lineEnd.y * lineStart.x
        )
        return numerator / sqrt(lengthSquared)
    }
}

// MARK: - CGPoint Convenience
extension RouteSimplifier {
    /// Simplifies an array of `CGPoint` values with default tolerance.
    /// - Parameter points: Array of `CGPoint` values.
    /// - Returns: Simplified array of `CGPoint`.
    static func simplify(_ points: [CGPoint]) -> [CGPoint] {
        simplify(cgPoints: points, tolerance: 1.0)
    }
}

// MARK: - CLLocationCoordinate2D Convenience
#if canImport(CoreLocation)
import CoreLocation

extension RouteSimplifier {
    /// Simplifies an array of `CLLocationCoordinate2D` values.
    ///
    /// - Parameters:
    ///   - coordinates: Array of `CLLocationCoordinate2D`.
    ///   - epsilon: Tolerance in coordinate degrees. Default `0.001` (roughly
    ///              111 m at the equator).
    /// - Returns: Simplified array of `CLLocationCoordinate2D`.
    static func simplify(
        coordinates: [CLLocationCoordinate2D],
        tolerance epsilon: Double = 0.001
    ) -> [CLLocationCoordinate2D] {
        let tuples = coordinates.map { (x: $0.longitude, y: $0.latitude) }
        let simplified = simplify(points: tuples, tolerance: epsilon)
        return simplified.map { CLLocationCoordinate2D(latitude: $0.y, longitude: $0.x) }
    }
}
#endif
