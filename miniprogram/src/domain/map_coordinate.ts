/** Same one-time WGS84 conversion as Android AmapCoordinateTransformer. */
export function mapCoordinate(latitude: number | null | undefined, longitude: number | null | undefined, alreadyGcj = false) {
  if (latitude == null || longitude == null || !Number.isFinite(latitude) || !Number.isFinite(longitude) ||
    Math.abs(latitude) > 90 || Math.abs(longitude) > 180 || (latitude === 0 && longitude === 0)) return null
  if (alreadyGcj || latitude < 0.8293 || latitude > 55.8271 || longitude < 72.004 || longitude > 137.8347) return { latitude, longitude }
  const x = longitude - 105, y = latitude - 35, pi = Math.PI
  let a = -100 + 2 * x + 3 * y + .2 * y * y + .1 * x * y + .2 * Math.sqrt(Math.abs(x))
  a += (20 * Math.sin(6 * x * pi) + 20 * Math.sin(2 * x * pi)) * 2 / 3
  a += (20 * Math.sin(y * pi) + 40 * Math.sin(y / 3 * pi)) * 2 / 3
  a += (160 * Math.sin(y / 12 * pi) + 320 * Math.sin(y * pi / 30)) * 2 / 3
  let b = 300 + x + 2 * y + .1 * x * x + .1 * x * y + .1 * Math.sqrt(Math.abs(x))
  b += (20 * Math.sin(6 * x * pi) + 20 * Math.sin(2 * x * pi)) * 2 / 3
  b += (20 * Math.sin(x * pi) + 40 * Math.sin(x / 3 * pi)) * 2 / 3
  b += (150 * Math.sin(x / 12 * pi) + 300 * Math.sin(x / 30 * pi)) * 2 / 3
  const radians = latitude / 180 * pi, eccentricity = .00669342162296594323
  const magic = 1 - eccentricity * Math.sin(radians) ** 2, root = Math.sqrt(magic)
  return { latitude: latitude + a * 180 / ((6378245 * (1 - eccentricity)) / (magic * root) * pi),
    longitude: longitude + b * 180 / (6378245 / root * Math.cos(radians) * pi) }
}
