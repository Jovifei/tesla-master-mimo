import { describe, expect, it } from 'vitest'
import type { Car, Charge, Drive, EnergyContract, EnergyMetric, RawDrive } from '../services/types'
import { driveEnergyReceipt, mergeChargeEnergyRecord, mergeDriveEnergyRecord, resolveChargeEnergy, resolveDriveEnergy, sameEnergyInstant } from './drive_energy'

const start = '2026-10-14T00:00:00.000000001Z', end = '2026-10-14T00:01:00.000000001Z'
const raw = (extra: Partial<RawDrive> = {}): RawDrive => ({ drive_id: 4, start_date: start, end_date: end,
  source: 'telemetry_mqtt', odometer_details: { odometer_distance: 2 }, battery_details: { start_battery_level: 80, end_battery_level: 79 }, ...extra })
const car: Car = { id: 7, stableId: 'vehicle-a', name: null, model: 'Y', trim: 'Long Range AWD', exteriorColor: null, wheelType: null, displayName: null }
const reported = (value: number | null, extra: Partial<EnergyMetric> = {}): EnergyMetric => ({ value_kwh: value, unit: 'kWh', method: 'api_reported_net',
  measurement_point: 'reported_net', source: 'telemetry_mqtt', quality: 'reported', start_date: start, end_date: end, ...extra })
const drive = (input: RawDrive, vehicle?: Car): Drive => {
  const energyReceipt = driveEnergyReceipt(input), energy = resolveDriveEnergy(energyReceipt, vehicle)
  return { id: 'drive-4', sessionId: null, startDate: input.start_date ?? null, endDate: input.end_date ?? null, source: input.source ?? null,
    startAddress: null, endAddress: null, durationMinutes: null, distanceKm: input.odometer_details?.odometer_distance ?? null,
    startBatteryLevel: input.battery_details?.start_battery_level ?? null, endBatteryLevel: input.battery_details?.end_battery_level ?? null,
    energyConsumedKwh: energy.energyKwh, efficiencyWhKm: energy.efficiencyWhKm, energy, energyReceipt,
    startLatitude: null, startLongitude: null, endLatitude: null, endLongitude: null, qualityState: 'observed', qualityReason: null }
}

describe('qualified per-drive energy inputs', () => {
  it('rejects default capacity assumptions while retaining explicit capacity for unknown models', () => {
    for (const model of [null, '', 'unknown', 'Roadster']) {
      const vehicle = { ...car, model }
      expect(resolveDriveEnergy(raw(), vehicle).energyKwh).toBeNull()
      expect(resolveDriveEnergy(raw(), { ...vehicle, capacityKwh: 70, capacityBasis: 'provided_capacity' }).energyKwh).toBeCloseTo(0.7)
    }
    const m: EnergyMetric = { ...reported(0.6), quality: 'estimated', method: 'soc_capacity_estimate', measurement_point: 'battery_soc',
      coverage_kind: 'endpoints', coverage_ratio: 1, capacity_kwh: 60, capacity_basis: 'default_assumption',
      calculation_version: 1, start_soc: 80, end_soc: 79 }
    expect(resolveDriveEnergy(raw({ energy_contract: { net_energy: m } })).energyKwh).toBeNull()
  })
  it('keeps reported zero and signed net energy separate from missing', () => {
    for (const value of [0, -0.25, 0.5]) {
      const energy = resolveDriveEnergy(raw({ energy_contract: { version: 1, net_energy: reported(value) } }))
      expect(energy.energyKwh).toBe(value); expect(energy.efficiencyWhKm).toBe(value * 500)
      expect(energy.quality).toBe('reported')
    }
    expect(resolveDriveEnergy(raw({ energy_contract: { net_energy: reported(null) } })).energyKwh).toBeNull()
  })

  it('rejects malformed contract versions, units, purpose and exact nanosecond window mismatches', () => {
    expect(sameEnergyInstant(start, '2026-10-14T08:00:00.000000001+08:00')).toBe(true)
    expect(sameEnergyInstant(start, '2026-10-14T00:00:00Z')).toBe(false)
    const bad: EnergyContract[] = [{ version: 2, net_energy: reported(2) }, { version: null, net_energy: reported(2) },
      { net_energy: reported(2, { unit: 'Wh' }) }, { net_energy: reported(2, { measurement_point: 'battery_input' }) },
      { net_energy: reported(2, { start_date: '2026-10-14T00:00:00Z' }) },
      { net_energy: reported(2, { source: 'other_source' }) }, { net_energy: reported(Infinity) }]
    for (const energy_contract of bad) expect(resolveDriveEnergy(raw({ energy_contract })).energyKwh).toBeNull()
    expect(resolveDriveEnergy(raw({ energy_contract: { net_energy: reported(1, { start_date: '2026-10-14T08:00:00.000000001+08:00' }) } })).energyKwh).toBe(1)
  })

  it('requires real nominal endpoints, source field, activity and no-charge proof', () => {
    const nominal: EnergyMetric = { ...reported(-0.2), quality: 'estimated', method: 'energy_remaining_delta', measurement_point: 'nominal_battery_remaining',
      source_field: 'EnergyRemaining', time_basis: 'collector_received_at', coverage_kind: 'endpoints', coverage_ratio: 1,
      observed_start_at: start, observed_end_at: end, activity_evidence: 'continuous_drive_no_charging_with_valid_endpoints' }
    expect(resolveDriveEnergy(raw({ energy_contract: { net_energy: nominal } })).energyKwh).toBe(-0.2)
    const bad: EnergyMetric[] = [{ ...nominal, activity_evidence: undefined }, { ...nominal, activity_evidence: 'charging_during_window' },
      { ...nominal, source_field: 'BatteryLevel' }, { ...nominal, time_basis: 'source_report' },
      { ...nominal, observed_start_at: null }, { ...nominal, observed_end_at: '2026-10-14T00:02:00Z' },
      { ...nominal, observed_start_at: '2026-10-14T00:00:31Z' }, { ...nominal, observed_end_at: start },
      { ...nominal, observed_start_at: '2026-10-14T00:00:05Z', observed_end_at: '2026-10-14T00:00:55Z' }]
    for (const m of bad) expect(resolveDriveEnergy(raw({ energy_contract: { net_energy: m } })).energyKwh).toBeNull()
  })

  it('uses complete trapezoids, replay deduplication and nanosecond timestamp identity', () => {
    const drive_details = [0, 15, 30, 45, 60].map(seconds => ({ date: `2026-10-14T00:${seconds === 60 ? '01' : '00'}:${String(seconds % 60).padStart(2, '0')}.000000001Z`, power: 10 }))
    const result = resolveDriveEnergy(raw({ drive_details: [...drive_details, drive_details[2]] }))
    expect(result.energyKwh).toBeCloseTo(1 / 6, 12)
    expect(result.coverageSeconds).toBe(60); expect(result.coverageRatio).toBe(1)
    expect(result.source).toBe('power_samples'); expect(result.quality).toBe('estimated')
  })

  it('keeps partial power diagnostic while SOC uses an explicit capacity assumption', () => {
    const input = raw({ drive_details: [{ date: start, power: 10 }, { date: '2026-10-14T00:00:10.000000001Z', power: 10 }] })
    const result = resolveDriveEnergy(input, car)
    expect(result.energyKwh).toBeCloseTo(0.784, 12)
    expect(result.evidence).toMatchObject({ method: 'soc_capacity_estimate', coverage_kind: 'endpoints', capacity_kwh: 78.4,
      capacity_basis: 'model_trim_assumption', calculation_version: 1, start_soc: 80, end_soc: 79 })
    expect(result.observedEnergyKwh).toBeCloseTo(1 / 36, 12)
    expect(resolveDriveEnergy(raw(), { ...car, capacityKwh: 70, capacityBasis: 'api_reported_current_capacity' }).energyKwh).toBeCloseTo(0.7, 12)
  })

  it('only permits the unchanged-SOC power fallback above 85% and never manufactures zero', () => {
    const points = (seconds: number) => [{ date: `2026-10-14T00:00:${String(seconds).padStart(2, '0')}.000000001Z`, power: 10 },
      { date: '2026-10-14T00:00:30.000000001Z', power: 10 }, { date: end, power: 10 }]
    const input = raw({ battery_details: { start_battery_level: 80, end_battery_level: 80 } })
    expect(resolveDriveEnergy({ ...input, drive_details: points(9) }).source).toBe('power_samples')
    expect(resolveDriveEnergy({ ...input, drive_details: points(10) }).energyKwh).toBeNull()
    expect(resolveDriveEnergy(input).energyKwh).toBeNull()
    expect(resolveDriveEnergy({ ...input, drive_details: [{ date: start, power: 10 }, { date: end, power: 10 }] }).energyKwh).toBeNull()
  })

  it('barriers and invalid sample dates cannot become complete power evidence', () => {
    const middle = '2026-10-14T00:00:30.000000001Z'
    const samples = [{ date: start, power: 10 }, { date: middle, power: 10 }, { date: end, power: 10 }]
    const input = raw({ battery_details: null })
    expect(resolveDriveEnergy({ ...input, drive_details: [...samples, { date: middle, power: 20 }] }).energyKwh).toBeNull()
    expect(resolveDriveEnergy({ ...input, drive_details: [samples[0], { date: middle, power: null }, samples[2]] }).energyKwh).toBeNull()
    expect(resolveDriveEnergy({ ...input, drive_details: [...samples, { date: 'invalid', power: 10 }] }).energyKwh).toBeNull()
    expect(sameEnergyInstant('2026-02-30T00:00:00Z', '2026-03-02T00:00:00Z')).toBe(false)
  })

  it('retains bounded parsed input without route arrays, locations or unknown fields', () => {
    const input = { ...raw(), drive_details: [{ date: start, power: 10 }, { date: end, power: 10 }], start_address: 'private address',
      start_latitude: 10, access_token: 'must not persist', ignored: Array(100).fill('unexpected') }
    const receipt = driveEnergyReceipt(input)
    expect(Object.keys(receipt)).not.toContain('drive_details')
    expect(JSON.stringify(receipt)).not.toMatch(/private address|access_token|unexpected|start_latitude/)
    expect(receipt.parsedEnergyVersion).toBe(1)
  })
})

describe('canonical drive and charge energy merge', () => {
  it('keeps known SOC and distance through same-window weak null scalar fields', () => {
    const prior = drive(raw(), car)
    const weak = drive(raw({ battery_details: { start_battery_level: null, end_battery_level: null },
      odometer_details: { odometer_distance: null }, energy_contract: null }))
    const next = mergeDriveEnergyRecord(prior, weak, prior)
    expect(next.energyConsumedKwh).toBeCloseTo(0.784, 12)
    expect([next.startBatteryLevel, next.endBatteryLevel, next.distanceKm, next.efficiencyWhKm]).toEqual([80, 79, 2, 392])
  })
  it('keeps reported energy efficiency when a weak summary has a null distance', () => {
    const prior = drive(raw({ energy_contract: { version: 1, net_energy: reported(-0.25) } }))
    const weak = drive(raw({ odometer_details: { odometer_distance: null }, energy_contract: null }))
    const next = mergeDriveEnergyRecord(prior, weak, prior)
    expect(next.energyConsumedKwh).toBe(-0.25)
    expect(next.distanceKm).toBe(2)
    expect(next.efficiencyWhKm).toBe(-125)
  })

  it('retains weak numeric nulls without masking real zero, unknown contracts or changed scopes', () => {
    const prior = drive(raw(), car)
    const zero = drive(raw({ battery_details: { start_battery_level: 0, end_battery_level: 0 }, odometer_details: { odometer_distance: 0 } }))
    const corrected = mergeDriveEnergyRecord(prior, zero, prior)
    expect(corrected).toMatchObject({ startBatteryLevel: 0, endBatteryLevel: 0, distanceKm: 0, energyConsumedKwh: null, efficiencyWhKm: null })
    const input = { battery_details: { start_battery_level: null, end_battery_level: null },
      odometer_details: { odometer_distance: null }, energy_contract: null }
    const unknown = drive(raw({ ...input, energy_contract: { version: 1, net_energy: reported(null, { quality: 'unknown' }) } }))
    const revoked = mergeDriveEnergyRecord(prior, unknown, prior)
    expect(revoked).toMatchObject({ startBatteryLevel: 80, endBatteryLevel: 79, distanceKm: 2, energyConsumedKwh: null })
    for (const changed of [
      drive(raw({ ...input, source: 'teslamate_archive' })),
      drive(raw({ ...input, end_date: '2026-10-14T00:02:00Z' })),
      { ...drive(raw(input)), id: 'drive-5' },
    ]) expect(mergeDriveEnergyRecord(prior, changed, changed)).toMatchObject({
      startBatteryLevel: null, endBatteryLevel: null, distanceKm: null, energyConsumedKwh: null, efficiencyWhKm: null,
    })
  })
  it('keeps the first equally qualified capacity and only upgrades a stronger basis', () => {
    const original = drive(raw(), { ...car, capacityKwh: 90, capacityBasis: 'api_reported_current_capacity' })
    const late = drive(raw(), { ...car, capacityKwh: 60, capacityBasis: 'api_reported_current_capacity' })
    const next = mergeDriveEnergyRecord(original, late, original)
    expect(next.energyConsumedKwh).toBeCloseTo(0.9, 12)
    expect(next.energy?.evidence?.capacity_kwh).toBe(90)
    const correction = drive(raw({ battery_details: { start_battery_level: 80, end_battery_level: 78 } }), { ...car, capacityKwh: 60, capacityBasis: 'api_reported_current_capacity' })
    expect(mergeDriveEnergyRecord(next, correction, next).energyConsumedKwh).toBeCloseTo(1.8, 12)
    const guessed = drive(raw(), car)
    expect(mergeDriveEnergyRecord(guessed, original, guessed).energyConsumedKwh).toBeCloseTo(0.9, 12)
    const otherSource = { ...late, source: 'teslamate_archive', energyReceipt: { ...late.energyReceipt!, source: 'teslamate_archive' } }
    expect(mergeDriveEnergyRecord(original, otherSource, otherSource).energy?.evidence?.capacity_kwh).not.toBe(90)
  })

  it('preserves a real legacy report through same-input weak null without upgrading a display scalar', () => {
    const original = drive(raw({ source: 'teslamate_archive', energy_consumed_net: 8 }))
    const weak = drive(raw({ source: 'teslamate_archive', energy_consumed_net: null }))
    const next = mergeDriveEnergyRecord(original, weak, original)
    expect(next.energyConsumedKwh).toBe(8)
    expect(next.energy?.quality).toBe('reported')
    expect(next.energyReceipt?.energy_consumed_net).toBe(8)
    expect(next.energyReceipt?.energy_contract).toBeUndefined()
    const reopened = JSON.parse(JSON.stringify(next)) as Drive
    expect(mergeDriveEnergyRecord(reopened, weak, reopened).energyConsumedKwh).toBe(8)
    const zero = drive(raw({ source: 'teslamate_archive', energy_consumed_net: 0 }))
    expect(mergeDriveEnergyRecord(next, zero, next).energyConsumedKwh).toBe(0)
    const providedCapacity = { ...car, capacityKwh: 60, capacityBasis: 'provided_capacity' }
    const corrected = drive(raw({ source: 'teslamate_archive', energy_consumed_net: null, battery_details: { start_battery_level: 80, end_battery_level: 78 } }), providedCapacity)
    expect(mergeDriveEnergyRecord(next, corrected, next).energyConsumedKwh).toBeCloseTo(1.2, 12)
    const distanceCorrection = drive(raw({ source: 'teslamate_archive', energy_consumed_net: null, odometer_details: { odometer_distance: 4 } }), providedCapacity)
    expect(mergeDriveEnergyRecord(next, distanceCorrection, next).efficiencyWhKm).toBeCloseTo(150, 12)
    const moved = drive(raw({ source: 'teslamate_archive', end_date: '2026-10-14T00:02:00Z', energy_consumed_net: null }))
    expect(mergeDriveEnergyRecord(next, moved, moved).energyConsumedKwh).not.toBe(8)
    const unknown = drive(raw({ source: 'teslamate_archive', energy_consumed_net: null, energy_contract: { version: 1, net_energy: reported(null, { source: 'teslamate_archive', quality: 'unknown' }) } }))
    const revoked = mergeDriveEnergyRecord(next, unknown, next)
    expect(revoked.energyConsumedKwh).toBeNull()
    expect(mergeDriveEnergyRecord(revoked, weak, revoked).energyConsumedKwh).toBeNull()
  })

  it('recomputes corrected SOC and distance with retained stronger capacity and survives JSON reopening', () => {
    const prior = drive(raw(), car)
    const incoming = drive(raw({ battery_details: { start_battery_level: 80, end_battery_level: 78 }, odometer_details: { odometer_distance: 4 } }))
    const next = mergeDriveEnergyRecord(prior, incoming, prior)
    expect(next.energyConsumedKwh).toBeCloseTo(1.568, 12)
    expect(next.efficiencyWhKm).toBeCloseTo(392, 12)
    expect(next.endBatteryLevel).toBe(78); expect(next.distanceKm).toBe(4)
    const reopened = JSON.parse(JSON.stringify(next)) as Drive
    const weak = drive({ drive_id: 4, start_date: start, end_date: end, source: 'telemetry_mqtt' })
    const refreshed = mergeDriveEnergyRecord(reopened, weak, reopened)
    expect(refreshed.energyConsumedKwh).toBeCloseTo(1.568, 12)
    expect(refreshed.energy?.evidence?.capacity_basis).toBe('model_trim_assumption')
  })

  it('retains qualified power through weak summaries but revokes explicit unknown immediately', () => {
    const prior = drive(raw({ energy_contract: { version: 1, net_energy: reported(-0.25) } }))
    const weak = drive({ drive_id: 4, start_date: start, end_date: end, source: 'telemetry_mqtt' })
    expect(mergeDriveEnergyRecord(prior, weak, prior).energyConsumedKwh).toBe(-0.25)
    const unknown = drive(raw({ energy_contract: { version: 1, net_energy: reported(null, { quality: 'unknown', reason: 'provider_energy_unsupported' }) } }))
    const revoked = mergeDriveEnergyRecord(prior, unknown, prior)
    expect(revoked.energyConsumedKwh).toBeNull()
    expect(mergeDriveEnergyRecord(revoked, weak, revoked).energyConsumedKwh).toBeNull()
  })

  it('keeps an authoritative unknown contract through a same-window weak null contract', () => {
    const prior = drive(raw({ energy_contract: { version: 1, net_energy: reported(null, { quality: 'unknown', reason: 'provider_energy_unsupported' }) } }))
    const weak = drive(raw({ energy_contract: null }))
    const refreshed = mergeDriveEnergyRecord(prior, weak, prior)
    expect(refreshed.energyConsumedKwh).toBeNull()
    expect(refreshed.energyReceipt?.energy_contract).toEqual(prior.energyReceipt?.energy_contract)
    const reopened = JSON.parse(JSON.stringify(refreshed)) as Drive
    expect(mergeDriveEnergyRecord(reopened, weak, reopened).energyConsumedKwh).toBeNull()
  })

  it('retains a valid contract through weak null but never lends it across source, window or ID', () => {
    const prior = drive(raw({ energy_contract: { version: 1, net_energy: reported(-0.25) } }))
    const weak = drive(raw({ energy_contract: null }))
    expect(mergeDriveEnergyRecord(prior, weak, prior).energyConsumedKwh).toBe(-0.25)
    for (const changed of [
      drive({ drive_id: 4, source: 'teslamate_archive', start_date: start, end_date: end, energy_contract: null }),
      drive({ drive_id: 4, source: 'telemetry_mqtt', start_date: start, end_date: '2026-10-14T00:02:00Z', energy_contract: null }),
      { ...drive({ drive_id: 5, source: 'telemetry_mqtt', start_date: start, end_date: end, energy_contract: null }), id: 'drive-5' },
    ]) {
      const result = mergeDriveEnergyRecord(prior, changed, changed)
      expect(result.energyConsumedKwh).toBeNull()
      expect(result.energyReceipt?.energy_contract).toBeNull()
    }
  })

  it('cannot graft old SOC or energy across source, window, ID or display-only legacy caches', () => {
    const prior = drive(raw(), car)
    const changed = drive({ drive_id: 4, source: 'telemetry_mqtt', start_date: start, end_date: '2026-10-14T00:02:00Z' })
    expect(mergeDriveEnergyRecord(prior, changed, { ...prior, endDate: changed.endDate }).energyConsumedKwh).toBeNull()
    const foreign = drive({ drive_id: 4, source: 'teslamate_archive', start_date: start, end_date: end })
    expect(mergeDriveEnergyRecord(prior, foreign, foreign).energyConsumedKwh).toBeNull()
    const legacy = { ...prior, energy: undefined, energyReceipt: undefined, energyConsumedKwh: 9 }
    expect(mergeDriveEnergyRecord(legacy, legacy, legacy).energyConsumedKwh).toBeNull()
    expect(mergeDriveEnergyRecord(prior, { ...changed, id: 'drive-5' }, changed).energyConsumedKwh).toBeNull()
  })

  it('separates battery and AC input, requires AC mode proof and never integrates charger power', () => {
    const counter = (point: string, value: number): EnergyMetric => ({ ...reported(value), method: 'session_counter_delta', measurement_point: point,
      coverage_kind: 'endpoints', coverage_ratio: 1 })
    const proof: EnergyContract = { version: 1, battery_input: counter('battery_input', 0), ac_input: counter('ac_charger_input', 2),
      charge_mode: 'ac', charge_mode_evidence: 'observed_boundary_modes_no_conflict' }
    const input = { source: 'telemetry_mqtt', start_date: start, end_date: end, energy_contract: proof, charger_power: 20 }
    expect(resolveChargeEnergy(input)).toMatchObject({ batteryInputKwh: 0, acInputKwh: 2, inputEnergyKwh: 2, chargeMode: 'ac' })
    expect(resolveChargeEnergy({ ...input, energy_contract: { ...proof, charge_mode: 'dc' } }).acInputKwh).toBeNull()
    expect(resolveChargeEnergy({ ...input, energy_contract: null }).batteryInputKwh).toBeNull()
    expect(resolveChargeEnergy({ charge_energy_added: -1 }).batteryInputKwh).toBeNull()
    const prior = { id: 'charge-1', source: 'telemetry_mqtt', startDate: start, endDate: end, energy: resolveChargeEnergy(input), energyAddedKwh: 0, energyUsedKwh: 2 } as Charge
    const next = { ...prior, energy: resolveChargeEnergy({ ...input, energy_contract: { version: 1, battery_input: { quality: 'unknown' } } }) }
    expect(mergeChargeEnergyRecord(prior, next, prior).energyAddedKwh).toBeNull()
    expect(mergeChargeEnergyRecord(prior, next, prior).energyUsedKwh).toBeNull()
  })

  it.each([5, 0])('keeps qualified legacy charging %s through absent/null summary but never revives unknown', value => {
    const input = { source: 'teslamate_archive', start_date: start, end_date: end, charge_energy_added: value }
    const prior = { id: 'charge-1', source: input.source, startDate: start, endDate: end,
      energy: resolveChargeEnergy(input), energyAddedKwh: value, energyUsedKwh: null } as Charge
    for (const charge_energy_added of [undefined, null]) {
      const incoming = { ...prior, energy: resolveChargeEnergy({ ...input, charge_energy_added }), energyAddedKwh: null }
      const kept = mergeChargeEnergyRecord(prior, incoming, prior)
      expect(kept.energyAddedKwh).toBe(value)
      const reopened = JSON.parse(JSON.stringify(kept)) as Charge
      expect(mergeChargeEnergyRecord(reopened, incoming, reopened).energyAddedKwh).toBe(value)
      const unknown = { ...incoming, energy: resolveChargeEnergy({ ...input, energy_contract: { version: 1, battery_input: { quality: 'unknown' } } }) }
      const revoked = mergeChargeEnergyRecord(kept, unknown, kept)
      expect(revoked.energyAddedKwh).toBeNull()
      expect(mergeChargeEnergyRecord(revoked, prior, revoked).energyAddedKwh).toBeNull()
      const other = { ...incoming, source: 'telemetry_mqtt' }
      expect(mergeChargeEnergyRecord(prior, other, other).energyAddedKwh).toBeNull()
      const otherId = { ...incoming, id: 'charge-2' }
      expect(mergeChargeEnergyRecord(prior, otherId, otherId).energyAddedKwh).toBeNull()
      const otherWindow = { ...incoming, endDate: '2026-10-14T00:02:00Z' }
      expect(mergeChargeEnergyRecord(prior, otherWindow, otherWindow).energyAddedKwh).toBeNull()
    }
  })

  it('merges independently reported legacy battery and generic input fields without clearing legitimate zero', () => {
    const input = { source: 'teslamate_archive', start_date: start, end_date: end, charge_energy_added: 5, charge_energy_used: 6 }
    const prior = { id: 'charge-1', source: input.source, startDate: start, endDate: end,
      energy: resolveChargeEnergy(input), energyAddedKwh: 5, energyUsedKwh: 6 } as Charge
    const incoming = { ...prior, energy: resolveChargeEnergy({ ...input, charge_energy_added: 0, charge_energy_used: null }), energyAddedKwh: 0, energyUsedKwh: null }
    expect(mergeChargeEnergyRecord(prior, incoming, prior)).toMatchObject({ energyAddedKwh: 0, energyUsedKwh: 6,
      energy: { batteryInputKwh: 0, acInputKwh: null, inputEnergyKwh: 6, quality: 'reported' } })
  })

  it.each(['dc', undefined] as const)('keeps legacy charging input generic without claiming AC for mode %s', mode => {
    const input = { source: 'teslamate_archive', start_date: start, end_date: end,
      charge_energy_added: 5, charge_energy_used: 6, charge_type: mode }
    const energy = resolveChargeEnergy(input)
    expect(energy).toMatchObject({ batteryInputKwh: 5, acInputKwh: null, inputEnergyKwh: 6, evidence: null })
    const prior = { id: 'charge-1', source: input.source, startDate: start, endDate: end,
      energy, energyAddedKwh: 5, energyUsedKwh: 6 } as Charge
    const weak = { ...prior, energy: resolveChargeEnergy({ ...input, charge_energy_added: null, charge_energy_used: null }) }
    const kept = mergeChargeEnergyRecord(prior, weak, prior)
    expect(kept.energy).toMatchObject({ acInputKwh: null, inputEnergyKwh: 6 })
    expect(kept.energyUsedKwh).toBe(6)
    const reopened = JSON.parse(JSON.stringify(kept)) as Charge
    expect(mergeChargeEnergyRecord(reopened, weak, reopened).energy?.inputEnergyKwh).toBe(6)
    const unknown = { ...weak, energy: resolveChargeEnergy({ ...input, energy_contract: { version: 1, battery_input: { quality: 'unknown' }, ac_input: { quality: 'unknown' } } }) }
    const revoked = mergeChargeEnergyRecord(kept, unknown, kept)
    expect(revoked.energy).toMatchObject({ batteryInputKwh: null, acInputKwh: null, inputEnergyKwh: null })
    expect(mergeChargeEnergyRecord(revoked, weak, revoked).energy?.inputEnergyKwh).toBeNull()
    const foreign = { ...weak, source: 'telemetry_mqtt' }
    expect(mergeChargeEnergyRecord(prior, foreign, foreign).energy?.inputEnergyKwh).toBeNull()
  })

  it('demotes old cached AC without a contract to generic input through weak null and reopening', () => {
    const input = { source: 'teslamate_archive', start_date: start, end_date: end, charge_energy_added: 5, charge_energy_used: 6 }
    const old = { id: 'charge-1', source: input.source, startDate: start, endDate: end, energyAddedKwh: 5, energyUsedKwh: 6,
      energy: { ...resolveChargeEnergy(input), acInputKwh: 6 } } as Charge
    const weak = { ...old, energy: resolveChargeEnergy({ ...input, charge_energy_added: null, charge_energy_used: null }) }
    const refreshed = mergeChargeEnergyRecord(old, weak, old)
    expect(refreshed.energy).toMatchObject({ acInputKwh: null, inputEnergyKwh: 6, evidence: null })
    expect(refreshed.energyUsedKwh).toBe(6)
    const reopened = JSON.parse(JSON.stringify(refreshed)) as Charge
    expect(mergeChargeEnergyRecord(reopened, weak, reopened).energy).toMatchObject({ acInputKwh: null, inputEnergyKwh: 6 })
    const unknown = { ...weak, energy: resolveChargeEnergy({ ...input, energy_contract: { version: 1, ac_input: { quality: 'unknown' } } }) }
    const revoked = mergeChargeEnergyRecord(refreshed, unknown, refreshed)
    expect(mergeChargeEnergyRecord(revoked, weak, revoked).energy).toMatchObject({ acInputKwh: null, inputEnergyKwh: null })
  })
})
