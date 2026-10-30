import { resolveChargeEnergy, sameEnergyInstant } from './drive_energy'
import type { Charge } from '../services/types'

/** App ChargeEnergyBinding.qualifiedAcEnergyBalance: two counters alone cannot establish efficiency. */
export function qualifiedAcEnergyBalance(charge: Charge) {
  const proof = charge.energy?.evidence, input = proof?.ac_input, battery = proof?.battery_input
  if ((charge.chargeModeConflict != null && charge.chargeModeConflict !== false) || proof?.version !== 1 || charge.reportedChargeMode !== 'ac' || proof.charge_mode !== 'ac' ||
    proof.charge_mode_evidence !== 'observed_boundary_modes_no_conflict' || !input || !battery ||
    input.method !== 'session_counter_delta' || battery.method !== 'session_counter_delta' ||
    input.measurement_point !== 'ac_charger_input' || battery.measurement_point !== 'battery_input' ||
    input.source !== battery.source || input.time_basis !== battery.time_basis || !input.time_basis?.trim()) return null
  for (const metric of [input, battery]) {
    const instant = (value: unknown) => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(value)
    if (![metric.observed_start_at, metric.observed_end_at, charge.startDate, charge.endDate].every(instant) ||
      !sameEnergyInstant(metric.observed_start_at, charge.startDate) || !sameEnergyInstant(metric.observed_end_at, charge.endDate)) return null
  }
  const energy = resolveChargeEnergy({ source: charge.source, start_date: charge.startDate, end_date: charge.endDate, energy_contract: proof })
  const incoming = energy.acInputKwh, added = energy.batteryInputKwh
  if (incoming == null || added == null || incoming <= 0 || added < 0 || added > incoming) return null
  return { inputKwh: incoming, batteryKwh: added, lossKwh: incoming - added, efficiencyPercent: added / incoming * 100 }
}
