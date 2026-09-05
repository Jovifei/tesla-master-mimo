import Foundation

/// Largest-Triangle-Three-Buckets (LTTB) downsampling for chart data.
/// Reduces N points to `targetCount` while preserving visual shape — the
/// key algorithm used by Android's `ChartDrawUtils.kt` for all interactive
/// charts (speed, power, battery, temperature, voltage, current curves).
///
/// Reference: Sveinn Steffansson et al., "Downsampling time series for
/// visual representation" (2014).
enum LTTBDownsample {

    /// Downsample a series of (x, y) points to `targetCount` points.
    /// - Parameters:
    ///   - points: Original data points (must have at least 3 points to downsample).
    ///   - targetCount: Desired number of output points. Must be >= 3.
    /// - Returns: Downsampled points preserving the first and last original points.
    static func downsample(_ points: [(x: Double, y: Double)], to targetCount: Int) -> [(x: Double, y: Double)] {
        guard points.count > 2, targetCount >= 3, targetCount < points.count else {
            return points
        }

        var result: [(x: Double, y: Double)] = [points[0]]

        let bucketSize = Double(points.count - 2) / Double(targetCount - 2)

        var aIndex = 0  // Previous selected point index

        for i in 1..<(targetCount - 1) {
            // Calculate bucket range
            let bucketStart = Int(floor(Double(i - 1) * bucketSize)) + 1
            let bucketEnd = min(Int(floor(Double(i) * bucketSize)) + 1, points.count - 1)

            // Calculate average of next bucket (for the triangle area calculation)
            let nextBucketStart = Int(floor(Double(i) * bucketSize)) + 1
            let nextBucketEnd = min(Int(floor(Double(i + 1) * bucketSize)) + 1, points.count - 1)

            var avgX = 0.0, avgY = 0.0
            let nextBucketCount = nextBucketEnd - nextBucketStart + 1
            for j in nextBucketStart...nextBucketEnd {
                avgX += points[j].x
                avgY += points[j].y
            }
            if nextBucketCount > 0 {
                avgX /= Double(nextBucketCount)
                avgY /= Double(nextBucketCount)
            }

            // Find the point in current bucket with largest triangle area
            var maxArea = -1.0
            var maxIndex = bucketStart

            let ax = points[aIndex].x
            let ay = points[aIndex].y

            for j in bucketStart...bucketEnd {
                let area = abs(
                    (ax - avgX) * (points[j].y - ay) -
                    (ax - points[j].x) * (avgY - ay)
                )
                if area > maxArea {
                    maxArea = area
                    maxIndex = j
                }
            }

            result.append(points[maxIndex])
            aIndex = maxIndex
        }

        result.append(points[points.count - 1])
        return result
    }

    /// Convenience: downsample arrays of values with implicit index-based x.
    static func downsample(_ values: [Double], to targetCount: Int) -> [Double] {
        let points = values.enumerated().map { (x: Double($0.offset), y: $0.element) }
        return downsample(points, to: targetCount).map(\.y)
    }
}
