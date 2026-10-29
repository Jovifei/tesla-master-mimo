import { isHistoryQuarantined } from '../../domain/history'
import { historyDateTime } from '../../domain/history_query'
import type { Drive } from '../../services/types'
import { isAppDriveAnalysisEligible } from '../../domain/app_history_projection'

// Match the App's route and statistics admission (DrivesViewModel). A short,
// qualified repositioning is part of the parking interval, not a route.
export function isAppRouteDrive(drive: Drive): boolean {
  if (isHistoryQuarantined(drive)) return false
  if (!isAppAnalysisDrive(drive) || drive.durationMinutes == null || drive.distanceKm == null) return true
  return drive.durationMinutes >= 1 && drive.distanceKm >= 0.2
}

export function isAppAnalysisDrive(drive: Drive): boolean {
  return isAppDriveAnalysisEligible(drive)
}

export function isAppStatisticsDrive(drive: Drive): boolean {
  return isAppRouteDrive(drive) && isAppAnalysisDrive(drive)
}

export function appDriveMonthCounts(rows: readonly Drive[]): { month: string; count: number }[] {
  const counts = new Map<string, number>()
  for (const row of rows) {
    const date = historyDateTime(row.startDate)
    if (date === '暂无日期') continue
    const month = date.slice(0, 7)
    counts.set(month, (counts.get(month) ?? 0) + 1)
  }
  if (!counts.size) return []
  const first = [...counts.keys()].sort()[0]!
  const now = new Date()
  const last = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
  const keys = [...counts.keys(), last].sort()
  const end = keys[keys.length - 1]!
  const months: { month: string; count: number }[] = []
  let [year, month] = first.split('-').map(Number)
  while (`${year}-${String(month).padStart(2, '0')}` <= end && months.length < 240) {
    const key = `${year}-${String(month).padStart(2, '0')}`
    months.push({ month: key, count: counts.get(key) ?? 0 })
    month += 1
    if (month > 12) { month = 1; year += 1 }
  }
  return months
}
