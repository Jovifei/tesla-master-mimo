import type { Charge, Drive } from '../services/types'
import { isHistoryQuarantined } from './history'

/** Android HistoryQuality.isAnalysisEligible; do not apply route-length rules to More analyses. */
function analysisEligible(row: Drive | Charge): boolean {
  return !isHistoryQuarantined(row) && (
    row.qualityState === 'observed' || row.qualityState === 'derived' ||
    (row.qualityState === 'incomplete' && row.qualityReason === 'remote_quality_unavailable')
  )
}

export function isAppDriveAnalysisEligible(drive: Drive): boolean { return analysisEligible(drive) }
export function isAppChargeAnalysisEligible(charge: Charge): boolean { return analysisEligible(charge) }

/** The App hides only qualified sessions with a known battery input of at most 0.1 kWh by default. */
export function appChargeListRows<T extends Charge>(rows: readonly T[], showShort = false): T[] {
  return rows.filter(charge => !isHistoryQuarantined(charge) && (
    showShort || !isAppChargeAnalysisEligible(charge) || charge.energyAddedKwh == null || charge.energyAddedKwh > 0.1
  ))
}

/** Statistics and monthly charts include qualified short/zero-energy sessions. */
export function appChargeStatisticsRows<T extends Charge>(rows: readonly T[]): T[] {
  return rows.filter(isAppChargeAnalysisEligible)
}
