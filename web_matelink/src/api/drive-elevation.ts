import type { WebDrivePoint } from './matelink-types'

/**
 * Elevation statistics for a drive, ported from the miniprogram's
 * domain/drive_elevation.ts (App DriveDetailMetrics.elevationChange
 * semantics): consecutive valid-sample differences; a pair with a
 * missing endpoint is skipped; gain/loss stay null when no valid
 * pair exists.
 */
export type ElevationStats = {
  maxM: number
  minM: number
  gainM: number | null
  lossM: number | null
}

export function elevationStats(points: WebDrivePoint[]): ElevationStats | null {
  const values = points.map(point => point.elevationM)
  const present = values.filter((value): value is number => value != null)
  if (!present.length) return null
  let gain = 0
  let loss = 0
  let pairs = 0
  for (let i = 1; i < values.length; i++) {
    const prev = values[i - 1]
    const current = values[i]
    if (prev == null || current == null) continue
    const diff = current - prev
    if (diff > 0) gain += diff
    else loss += -diff
    pairs++
  }
  return {
    maxM: Math.max(...present),
    minM: Math.min(...present),
    gainM: pairs ? gain : null,
    lossM: pairs ? loss : null,
  }
}
