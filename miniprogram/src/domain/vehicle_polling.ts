import type { CarStatus } from '../services/types'

/** DashboardViewModel.startPolling cadence; charging_state alone does not replace vehicle state. */
export function vehicleSnapshotDelay(status: CarStatus | null): number {
  const state = status?.state?.toLowerCase()
  if (state === 'driving' || (status?.speed ?? 0) > 0) return 8000
  if (state === 'charging') return 15000
  if (state === 'asleep') return 60000
  return 10000
}
