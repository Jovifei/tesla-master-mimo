import Taro from '@tarojs/taro'
import { ApiError, assertVehicle, getApiOrigin, getApiSessionGeneration, matelinkApi, normalizeCharge, normalizeDrive, requestJson } from './api'
import { readAppSession } from './session'
import { collectHistoryInterval } from '../domain/history_controller'
import { sameEnergyInstant } from '../domain/drive_energy'
import type { Car, Charge, Drive, RawCharge, RawDrive } from './types'
import type { AnalysisHistory, AnalysisMeta, AnalysisOptions, BatteryHealth, ChargeEvidence, ChargeEvidenceSummary, DriveEvidence, ParkedDetail, SoftwareUpdate } from './analysis_types'

type Row = Record<string, unknown>
const row = (value: unknown): Row => value && typeof value === 'object' && !Array.isArray(value) ? value as Row : {}
const text = (value: unknown): string | null => typeof value === 'string' && value.trim() && value.length <= 512 && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim() : null
const num = (value: unknown, min = -Infinity, max = Infinity): number | null => typeof value === 'number' && Number.isFinite(value) && value >= min && value <= max ? value : null
const id = (value: unknown): string | null => typeof value === 'number' ? Number.isSafeInteger(value) && value > 0 ? String(value) : null : text(value)
function failed(code = 'invalid_api_response'): never { throw new ApiError('数据证据暂时不可用，请刷新重试', { code }) }
function body(value: unknown): Row { const input = row(value); return 'data' in input ? row(input.data) : input }
function meta(data: Row): AnalysisMeta {
  const m = row(data.meta)
  return { availability: text(m.availability), source: text(m.source ?? data.source), qualityState: text(m.quality_state), qualityReason: text(m.quality_reason), observedAt: text(m.observed_at ?? data.observed_at) }
}
function unit(data: Row, key: string): string | null | undefined { return 'units' in data ? text(row(data.units)[key]) : undefined }
function length(value: unknown, lengthUnit: string | null | undefined): number | null {
  const n = num(value, 0), u = lengthUnit === undefined ? 'km' : lengthUnit
  return n !== null && (u === 'km' || u === 'mi') ? num(n * (u === 'mi' ? 1.609344 : 1), 0) : null
}
function temperature(value: unknown, temperatureUnit: string | null | undefined): number | null {
  const n = num(value), u = temperatureUnit === undefined ? 'C' : temperatureUnit
  return n !== null && (u === 'C' || u === 'F') ? num(u === 'F' ? (n - 32) * 5 / 9 : n, -100, 100) : null
}

function chargeSummary(raw: Row, data: Row): ChargeEvidenceSummary {
  const phases = num(raw.charger_phases, 0, 3), lengthUnit = unit(data, 'unit_of_length')
  return { chargerPowerKw: num(raw.charger_power), chargerVoltageV: num(raw.charger_voltage, 0), currentA: num(raw.charger_actual_current),
    phases: phases !== null && Number.isInteger(phases) ? phases : null, currentRequestA: num(raw.charge_current_request, 0),
    currentRequestMaxA: num(raw.charge_current_request_max, 0), timeToFullHours: num(raw.time_to_full_charge, 0),
    currentBatteryLevel: num(row(raw.battery_details).current_battery_level, 0, 100), outsideTempC: temperature(raw.outside_temp_avg, unit(data, 'unit_of_temperature')),
    startRatedRangeKm: length(row(raw.range_rated).start_range, lengthUnit), endRatedRangeKm: length(row(raw.range_rated).end_range, lengthUnit),
    odometerKm: length(raw.odometer, lengthUnit), isCharging: typeof raw.is_charging === 'boolean' ? raw.is_charging : null }
}

/** Captures the same proven car identity and rejects cancellation/account/epoch changes after every await. */
function guard(car: Car, options: AnalysisOptions = {}): () => void {
  const origin = getApiOrigin(), account = readAppSession(Taro)?.userId, generation = getApiSessionGeneration(), uid = car.stableId, carId = car.id
  const check = () => {
    if (!account || origin !== getApiOrigin() || account !== readAppSession(Taro)?.userId || generation !== getApiSessionGeneration() ||
      uid !== car.stableId || carId !== car.id || options.isCurrent?.() === false) failed('session_changed')
    if (car.remoteVerified !== true) failed('vehicle_not_found')
    assertVehicle(null, car.id, car)
  }
  check(); return check
}
async function read(car: Car, path: string, options: AnalysisOptions = {}): Promise<Row> {
  const check = guard(car, options)
  try {
    const data = body(await requestJson<Row>(path, { method: 'GET' }))
    check(); assertVehicle(data, car.id, car); return data
  } catch (error) { check(); throw error }
}

function qualifyParked(raw: Row, units: Row): ParkedDetail {
  const source = text(raw.source), startDate = text(raw.start_date), endDate = text(raw.end_date)
  const proof = row(raw.energy_contract), m = row(proof.stored_change), value = num(m.value_kwh)
  const ratio = num(m.coverage_ratio), seconds = startDate && endDate ? (Date.parse(endDate) - Date.parse(startDate)) / 1000 : NaN
  const valid = (proof.version === undefined ? 1 : proof.version) === 1 && value !== null && !raw.linked_charge && ['telemetry_mqtt', 'fleet_telemetry'].includes(source ?? '') &&
    m.source === source && m.source_field === 'EnergyRemaining' && m.method === 'energy_remaining_delta' && m.measurement_point === 'nominal_battery_remaining' &&
    m.quality === 'estimated' && (m.unit === undefined || m.unit === 'kWh') && m.coverage_kind === 'endpoints' && ratio !== null && ratio >= 1 - 1e-9 && ratio <= 1 &&
    m.activity_evidence === 'no_driving_or_charging_during_window' && sameEnergyInstant(startDate, m.start_date) && sameEnergyInstant(endDate, m.end_date) &&
    sameEnergyInstant(startDate, m.observed_start_at) && sameEnergyInstant(endDate, m.observed_end_at) && Number.isFinite(seconds) && seconds > 0
  const energyKwh = valid ? value : null
  return { olderDriveId: id(raw.older_drive_id), newerDriveId: id(raw.newer_drive_id), startDate, endDate, address: text(raw.address),
    startBatteryLevel: num(raw.start_battery_level, 0, 100), endBatteryLevel: num(raw.end_battery_level, 0, 100), batteryDelta: num(raw.battery_delta, -100, 100),
    energyKwh, averagePowerW: energyKwh === null ? null : num(energyKwh * 3_600_000 / seconds), peakPowerW: null,
    insideTempC: temperature(raw.inside_temp_average, unit(units, 'unit_of_temperature')), outsideTempC: temperature(raw.outside_temp_average, unit(units, 'unit_of_temperature')),
    sampleCount: num(raw.sample_count, 0), coverageSeconds: num(raw.coverage_seconds, 0), coverageRatio: num(raw.coverage_ratio, 0, 1),
    linkedChargeId: id(row(raw.linked_charge).charge_id), source, energyReason: valid ? null : text(m.reason) ?? 'no_qualified_parked_energy' }
}

export const analysisApi = {
  async getHistory<K extends 'drives' | 'charges'>(car: Car, kind: K, options: AnalysisOptions = {}): Promise<AnalysisHistory<K extends 'drives' ? Drive : Charge>> {
    const check = guard(car, options)
    const fetch = async (page: number) => {
      check()
      try {
        const data = kind === 'drives' ? await matelinkApi.getDrives(car.id, page, 20, car) : await matelinkApi.getCharges(car.id, page, 20, car)
        check(); return data
      } catch (error) { check(); throw error }
    }
    const first = await fetch(1)
    const interval = await collectHistoryInterval<Drive | Charge>(first, fetch, () => { try { check(); return true } catch { return false } })
    check()
    if (!interval) failed('session_changed')
    return { items: interval.items, complete: interval.complete, pages: interval.receivedPages, receivedPages: interval.receivedPages, reason: interval.reason, meta: first.meta } as AnalysisHistory<K extends 'drives' ? Drive : Charge>
  },

  async getBatteryHealth(car: Car, options: AnalysisOptions = {}): Promise<{ batteryHealth: BatteryHealth | null; meta: AnalysisMeta }> {
    const data = await read(car, `/api/v1/cars/${car.id}/battery-health`, options)
    if (!('battery_health' in data) || (data.battery_health !== null && (typeof data.battery_health !== 'object' || Array.isArray(data.battery_health)))) failed()
    const health = row(data.battery_health), distanceUnit = unit(data, 'unit_of_length')
    assertVehicle(health, car.id, car)
    const efficiency = num(health.rated_efficiency, 0), knownUnit = distanceUnit === undefined || distanceUnit === 'km' || distanceUnit === 'mi'
    return { batteryHealth: data.battery_health === null ? null : { maxRangeKm: length(health.max_range, distanceUnit), currentRangeKm: length(health.current_range, distanceUnit),
      maxCapacityKwh: num(health.max_capacity, Number.MIN_VALUE), currentCapacityKwh: num(health.current_capacity, Number.MIN_VALUE),
      ratedEfficiencyWhKm: efficiency !== null && knownUnit ? efficiency / (distanceUnit === 'mi' ? 1.609344 : 1) : null,
      healthPercentage: num(health.battery_health_percentage, 0) }, meta: meta(data) }
  },

  async getUpdates(car: Car, page = 1, options: AnalysisOptions = {}): Promise<{ items: SoftwareUpdate[]; meta: AnalysisMeta }> {
    if (!Number.isSafeInteger(page) || page < 1) failed()
    const data = await read(car, `/api/v1/cars/${car.id}/updates?page=${page}&show=20`, options)
    if (!Array.isArray(data.updates) || data.updates.length > 500) failed()
    return { items: data.updates.map(value => { const update = row(value), key = id(update.update_id); assertVehicle(update, car.id, car); return key ? { id: key, version: text(update.version), startDate: text(update.start_date), endDate: text(update.end_date) } : null }).filter((value): value is SoftwareUpdate => value !== null), meta: meta(data) }
  },

  async getDriveEvidence(car: Car, driveId: string, options: AnalysisOptions = {}): Promise<DriveEvidence> {
    if (!driveId || driveId.length > 256 || /[\s\u0000-\u001f\u007f]/.test(driveId) || driveId === '.' || driveId === '..') failed()
    const data = await read(car, `/api/v1/cars/${car.id}/drives/${encodeURIComponent(driveId)}`, options), raw = row(data.drive)
    if (!('drive' in data)) failed()
    assertVehicle(data.car, car.id, car)
    assertVehicle(raw, car.id, car)
    if (data.drive == null) return { drive: null, points: [], source: null, qualityState: null, pointsComplete: false, reason: 'detail_unavailable' }
    const drive = normalizeDrive(raw as RawDrive, car, unit(data, 'unit_of_length'), unit(data, 'unit_of_temperature'))
    if (!drive || drive.id !== driveId) failed()
    if (drive.qualityState === 'quarantined') return { drive, points: [], source: drive.source, qualityState: drive.qualityState, pointsComplete: false, reason: 'quarantined_history' }
    const samples = raw.drive_details, start = Date.parse(drive.startDate ?? ''), end = Date.parse(drive.endDate ?? '')
    if (!Array.isArray(samples)) return { drive, points: [], source: drive.source, qualityState: drive.qualityState, pointsComplete: false, reason: 'route_samples_unavailable' }
    const points = samples.slice(0, 20_000).flatMap(value => {
      const sample = row(value), date = text(sample.date), at = date ? Date.parse(date) : NaN
      if (!date || !sameEnergyInstant(date, date) || !Number.isFinite(at) || !Number.isFinite(start) || !Number.isFinite(end) || at < start || at > end) return []
      const speed = num(sample.speed, 0), speedUnit = text(raw.speed_unit)
      const speedKmh = raw.speed_unit == null ? length(speed, unit(data, 'unit_of_length')) : speed !== null && speedUnit !== null && ['km/h', 'kph', 'mph'].includes(speedUnit) ? num(speed * (speedUnit === 'mph' ? 1.609344 : 1), 0) : null
      return [{ date, speedKmh, powerKw: num(sample.power),
        batteryLevel: num(sample.battery_level, 0, 100), elevationM: num(sample.elevation, -500, 10000),
        latitude: num(sample.latitude, -90, 90), longitude: num(sample.longitude, -180, 180) }]
    }).sort((a, b) => Date.parse(a.date) - Date.parse(b.date))
    return { drive, points, source: drive.source, qualityState: drive.qualityState, pointsComplete: false,
      reason: samples.length > 20_000 ? 'route_sample_limit' : 'response_samples_only' }
  },

  async getChargeEvidence(car: Car, chargeId: string, options: AnalysisOptions = {}): Promise<ChargeEvidence> {
    if (!chargeId || chargeId.length > 256 || /[\s\u0000-\u001f\u007f]/.test(chargeId) || chargeId === '.' || chargeId === '..') failed()
    const data = await read(car, `/api/v1/cars/${car.id}/charges/${encodeURIComponent(chargeId)}`, options), raw = row(data.charge)
    assertVehicle(data.car, car.id, car); assertVehicle(raw, car.id, car)
    if (data.charge == null) return { charge: null, points: [], summary: chargeSummary({}, data), source: null, qualityState: null, pointsComplete: false, reason: 'detail_unavailable' }
    // Reuse the exact detail energy/currency parser; samples never confer kWh qualification.
    const charge = normalizeCharge(raw as RawCharge)
    if (!charge || charge.id !== chargeId) failed()
    if (charge.qualityState === 'quarantined') return { charge, points: [], summary: chargeSummary({}, data), source: charge.source, qualityState: charge.qualityState, pointsComplete: false, reason: 'quarantined_history' }
    const summary = chargeSummary(raw, data), samples = raw.charge_details
    const start = Date.parse(charge.startDate ?? ''), ongoing = raw.end_date == null && raw.is_charging === true
    const end = ongoing ? Date.now() : Date.parse(charge.endDate ?? '')
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start || !sameEnergyInstant(charge.startDate, charge.startDate) ||
      (!ongoing && !sameEnergyInstant(charge.endDate, charge.endDate))) return { charge, points: [], summary, source: charge.source, qualityState: charge.qualityState, pointsComplete: false, reason: 'charge_window_unavailable' }
    if (!Array.isArray(samples)) return { charge, points: [], summary, source: charge.source, qualityState: charge.qualityState, pointsComplete: false, reason: 'charge_samples_unavailable' }
    const points = samples.slice(0, 20_000).flatMap(value => {
      const sample = row(value), date = text(sample.date), at = date ? Date.parse(date) : NaN, charger = row(sample.charger_details)
      if (!date || !sameEnergyInstant(date, date) || !Number.isFinite(at) || at < start || at > end) return []
      return [{ date, batteryLevel: num(sample.battery_level, 0, 100), powerKw: num(charger.charger_power), voltageV: num(charger.charger_voltage, 0),
        currentA: num(charger.charger_actual_current), temperatureC: temperature(sample.outside_temp, unit(data, 'unit_of_temperature')) }]
    }).sort((a, b) => Date.parse(a.date) - Date.parse(b.date))
    return { charge, points, summary, source: charge.source, qualityState: charge.qualityState, pointsComplete: false,
      reason: samples.length > 20_000 ? 'charge_sample_limit' : ongoing ? 'ongoing_response_samples_only' : 'response_samples_only' }
  },

  async getParkedDetail(car: Car, olderDriveId: string, newerDriveId: string, options: AnalysisOptions = {}): Promise<{ detail: ParkedDetail | null; meta: AnalysisMeta }> {
    if (![olderDriveId, newerDriveId].every(value => /^[1-9]\d*$/.test(value) && Number.isSafeInteger(Number(value))) || olderDriveId === newerDriveId) failed()
    let data: Row
    try { data = await read(car, `/api/matelink/v1/cars/${car.id}/parked/${olderDriveId}/${newerDriveId}`, options) }
    catch (error) {
      if (error instanceof ApiError && error.code === 'parked_history_bounds_unavailable') return { detail: null,
        meta: { availability: 'unavailable', source: null, qualityState: null, qualityReason: error.code, observedAt: null } }
      throw error
    }
    // Exact endpoint returns the window directly under data, not an invented parked wrapper.
    if (data.older_drive_id == null && data.newer_drive_id == null) failed()
    if (id(data.older_drive_id) !== olderDriveId || id(data.newer_drive_id) !== newerDriveId) failed('vehicle_not_found')
    return { detail: qualifyParked(data, data), meta: meta(data) }
  },

  async getStandby(car: Car, options: AnalysisOptions = {}): Promise<{ windows: ParkedDetail[]; meta: AnalysisMeta }> {
    const data = await read(car, `/api/matelink/v1/cars/${car.id}/standby`, options)
    if (!Array.isArray(data.windows) || data.windows.length > 500) failed()
    return { windows: data.windows.map(value => { assertVehicle(value, car.id, car); return qualifyParked(row(value), data) }), meta: meta(data) }
  },

  async getHistoryContext(car: Car, options: AnalysisOptions = {}): Promise<{ carId: number; stableId: string; capabilityVersion: number }> {
    const data = await read(car, `/api/matelink/v1/cars/${car.id}/history-context`, options)
    if (data.car_id !== car.id || data.vehicle_uid !== car.stableId || data.capability_version !== 1) failed('vehicle_not_found')
    return { carId: car.id, stableId: car.stableId, capabilityVersion: 1 }
  },

  async getCurrentCharge(car: Car, options: AnalysisOptions = {}): Promise<Charge | null> {
    const check = guard(car, options)
    try { const charge = await matelinkApi.getCurrentCharge(car.id, car); check(); return charge } catch (error) { check(); throw error }
  },
}
