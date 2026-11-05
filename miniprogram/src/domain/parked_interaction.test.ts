import { expect, it } from 'vitest'
import { recordedChargeIntervals } from './parked_interaction'
const window = { source: 'teslamate_archive', source_instance_id: 'archive-A', start_date: '2026-11-03T00:00:00Z', end_date: '2026-11-03T03:00:00Z' }
const charge = (id = 1) => ({ charge_id: id, source: window.source, quality_state: 'observed', start_date: '2026-11-03T00:10:00Z', end_date: '2026-11-03T00:30:00Z', energy_added_kwh: 0 })
it('preserves two separate reported sessions, true zero and an unreported finish', () => {
  const rows = recordedChargeIntervals({ ...window, linked_charges: [charge(), { ...charge(2), start_date: '2026-11-03T01:00:00Z', end_date: null }] })
  expect(rows).toEqual([{ id: '1', startDate: charge().start_date, endDate: charge().end_date, energyKwh: 0 },
    { id: '2', startDate: '2026-11-03T01:00:00Z', endDate: null, energyKwh: null }])
})
it.each([
  { source: 'fleet_api' }, { source_instance_id: 'archive-B' }, { source_id: 'A', source_record_id: 'B' },
  { quality_state: ' Quarantined ' }, { start_date: '2026-11-03T04:00:00Z' }, { end_date: 'invalid' }, { start_date: '2026-11-03T00:10:00' },
])('excludes conflicting, invalid or out-of-window linked receipts without replacing unknown values: %j', bad => {
  expect(recordedChargeIntervals({ ...window, linked_charges: [{ ...charge(), ...bad }] })).toEqual([])
})
it('vetoes ambiguous duplicate identifiers and limits work without fabricating extra records', () => {
  expect(recordedChargeIntervals({ ...window, linked_charges: [charge(), charge()] })).toEqual([])
  expect(recordedChargeIntervals({ ...window, linked_charges: Array.from({ length: 100 }, (_, i) => charge(i + 1)) })).toHaveLength(64)
  expect(recordedChargeIntervals({ ...window, linked_charge: charge() })).toHaveLength(1)
  expect(recordedChargeIntervals({ ...window, linked_charges: [], linked_charge: charge() })).toEqual([])
  expect(recordedChargeIntervals({ ...window, source: null, linked_charges: [charge()] })).toEqual([])
})
