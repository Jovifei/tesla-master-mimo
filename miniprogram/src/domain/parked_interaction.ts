import { tpmsInstant } from './tpms'
import type { RecordedChargeInterval } from '../services/analysis_types'
type Row = Record<string, unknown>
const row = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
/** Call only on the already scope-checked parked response. No additional history requests. */
export function recordedChargeIntervals(value: unknown): RecordedChargeInterval[] {
  const input = row(value), start = tpmsInstant(input.start_date), end = tpmsInstant(input.end_date)
  if (start == null || end == null || end <= start || typeof input.source !== 'string' || !input.source) return []
  const raw = Array.isArray(input.linked_charges) ? input.linked_charges.slice(0, 64) : input.linked_charge ? [input.linked_charge] : []
  const candidates: RecordedChargeInterval[] = [], counts = new Map<string, number>()
  for (const item of raw) {
    const r = row(item), id = typeof r.charge_id === 'number' && Number.isSafeInteger(r.charge_id) && r.charge_id > 0 ? String(r.charge_id) :
      typeof r.charge_id === 'string' && /^[1-9]\d*$/.test(r.charge_id) && Number.isSafeInteger(Number(r.charge_id)) ? r.charge_id : null
    if (id) counts.set(id, (counts.get(id) ?? 0) + 1)
    const from = tpmsInstant(r.start_date), to = r.end_date == null ? null : tpmsInstant(r.end_date)
    if (!id || from == null || from >= end || (r.end_date != null && (to == null || to <= from)) || (to != null && to <= start) ||
      r.source !== input.source || (typeof r.quality_state === 'string' && r.quality_state.trim().toLowerCase() === 'quarantined') ||
      (r.source_instance_id != null && (input.source_instance_id == null || r.source_instance_id !== input.source_instance_id)) ||
      (r.source_id != null && r.source_record_id != null && r.source_id !== r.source_record_id)) continue
    const energy = r.quality_state === 'observed' && to != null && typeof r.energy_added_kwh === 'number' && Number.isFinite(r.energy_added_kwh) && r.energy_added_kwh >= 0 ? r.energy_added_kwh : null
    candidates.push({ id, startDate: r.start_date as string, endDate: to == null ? null : r.end_date as string, energyKwh: energy })
  }
  return candidates.filter(record => counts.get(record.id) === 1).sort((a, b) => Date.parse(a.startDate) - Date.parse(b.startDate) || a.id.localeCompare(b.id))
}
