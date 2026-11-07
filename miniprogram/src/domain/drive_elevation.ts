/**
 * Drive-detail elevation statistics, aligned with the Android App
 * (DriveDetailMetrics.elevationChange): max/min over valid samples;
 * gain/loss accumulate consecutive-sample diffs, skipping any pair
 * with a missing endpoint; gain/loss stay null when no valid pair
 * exists, and the whole group is null when no sample has elevation.
 */
export type ElevationStats = {
  max: number
  min: number
  gain: number | null
  loss: number | null
}

export function elevationStats(points: readonly { elevationM: number | null }[]): ElevationStats | null {
  const values = points
    .map(point => point.elevationM)
    .filter((value): value is number => value != null && Number.isFinite(value))
  if (!values.length) return null
  let gain = 0
  let loss = 0
  let pairs = 0
  for (let index = 1; index < points.length; index++) {
    const a = points[index - 1].elevationM
    const b = points[index].elevationM
    if (a == null || b == null || !Number.isFinite(a) || !Number.isFinite(b)) continue
    pairs++
    const diff = b - a
    if (diff > 0) gain += diff
    else loss -= diff
  }
  return {
    max: Math.max(...values),
    min: Math.min(...values),
    gain: pairs > 0 ? Math.round(gain) : null,
    loss: pairs > 0 ? Math.round(loss) : null,
  }
}
