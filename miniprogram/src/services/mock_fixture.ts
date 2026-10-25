import type { RawCar, RawCharge, RawDrive } from './types'

/** Synthetic review fixture. Every row carries source `mock_fixture` so the UI
 * labels it 本地模拟/合成测试数据 — nothing here may pass as a real Tesla record. */

export const MOCK_FIXTURE_ENABLED: boolean = process.env.TARO_APP_MOCK_FIXTURE === 'true'
export const MOCK_USER_ID = 'mock-fixture-user'
export const MOCK_CAR_ID = 7

function mulberry32(seed: number) {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
const rand = mulberry32(20261025)
const pick = <T,>(rows: readonly T[]): T => rows[Math.floor(rand() * rows.length)]
const between = (low: number, high: number) => low + rand() * (high - low)
const round1 = (value: number) => Math.round(value * 10) / 10

const PLACES = ['余杭塘路 朱庙社区', '访云街 西蔡庙', '景兴路 葛巷社区', '向往社区', '仓前街道 钱腾云都', '龙泉路 仓前工业园', '文一西路 杭师大', '海曙路 未来科技城']
const HOME = PLACES[0]
const iso = (time: number) => new Date(time).toISOString().replace(/\.\d{3}Z$/, 'Z')

// 76 days ending 2026-10-25; weekday commuting + occasional trips, ~2-5 drives/day.
const END = Date.UTC(2026, 9, 25, 14, 0, 0)
const DAY = 86_400_000
const DAYS = 76

type PlannedDrive = { id: number; start: number; end: number; km: number; minutes: number; speedMax: number; kwh: number; from: string; to: string;
  /** Scenario overrides: winter temperature and a net-regen (downhill) drain. */
  outsideTempAvg?: number; netKwh?: number; quarantined?: boolean }
type PlannedCharge = { id: number; start: number; end: number; kwhAdded: number; dc: boolean; free: boolean; teslamateZero: boolean; address: string; socStart: number; socEnd: number }

function planTimeline(): { drives: PlannedDrive[]; charges: PlannedCharge[] } {
  const drives: PlannedDrive[] = [], charges: PlannedCharge[] = []
  let driveId = 6100, chargeId = 9100, soc = 86, clock = END - DAYS * DAY
  for (let day = 0; day < DAYS; day++) {
    const dayStart = clock + day * DAY
    const weekday = new Date(dayStart).getUTCDay()
    // Month boundaries (8/31=day21, 9/1=day22, 9/30=day51, 10/1=day52) always get
    // at least one drive so monthly chart groups never break.
    const monthBoundary = [21, 22, 51, 52].includes(day)
    const coldWeek = day >= 36 && day <= 41
    const trips = monthBoundary ? Math.max(1, weekday === 0 || weekday === 6 ? 1 : Math.floor(between(2, 4)))
      : coldWeek ? Math.max(2, weekday === 0 || weekday === 6 ? 2 : Math.floor(between(2, 4)))
      : weekday === 0 || weekday === 6 ? Math.floor(between(0, 3)) : Math.floor(between(2, 6))
    for (let trip = 0; trip < trips; trip++) {
      if (soc < 22) {
        // Overnight-ish AC top-up most days; a DC stop roughly every fifth stop.
        const dc = charges.length % 5 === 4
        const kwhAdded = dc ? between(38, 58) : between(16, 34)
        const socStart = Math.round(soc), socEnd = Math.min(100, Math.round(soc + kwhAdded / 0.6))
        const minutes = Math.round(dc ? between(24, 42) : between(95, 190))
        const start = dayStart + between(1, 12) * 3_600_000
        // Deterministic coverage: every third DC stop is free supercharging, every
        // fifth AC stop carries an unfilled TeslaMate zero bill for the estimate path.
        const dcIndex = charges.filter(item => item.dc).length, acIndex = charges.length - dcIndex
        charges.push({ id: chargeId++, start, end: start + minutes * 60_000, kwhAdded: round1(kwhAdded), dc,
          free: dc && dcIndex % 3 === 2, teslamateZero: !dc && acIndex % 5 === 4, address: dc ? '访云街 西蔡庙' : HOME, socStart, socEnd })
        soc = socEnd
      }
      const km = round1(between(2.2, 44)), speed = weekday === 0 ? between(38, 88) : between(52, 118)
      const minutes = Math.max(4, Math.round(km / (speed / 60) + between(2, 9)))
      const start = dayStart + (trip + between(0.4, 0.9)) * (12 * 3_600_000 / Math.max(trips, 1))
      const from = trip === 0 ? (charges.length && rand() < 0.6 ? HOME : pick(PLACES)) : drives[drives.length - 1].to
      const to = pick(PLACES.filter(place => place !== from))
      const kwh = round1(km * between(128, 186) / 1000)
      // Winter-morning scenario: 12 drives around mid-September carry sub-zero
      // outside temperatures (negative values must render unclamped).
      const coldDay = day >= 36 && day <= 41
      const outsideTempAvg = coldDay ? Math.round(between(-8.2, -0.4) * 10) / 10 : undefined
      // Downhill scenario: two drives with genuine net regeneration (negative drain).
      const regen = drives.filter(item => (item.netKwh ?? 0) < 0).length
      const netKwh = regen < 2 && km < 8 && trip === trips - 1 ? -round1(between(0.2, 0.9)) : undefined
      drives.push({ id: driveId++, start, end: start + minutes * 60_000, km, minutes, speedMax: Math.round(speed), kwh, from, to, outsideTempAvg, netKwh })
      soc = Math.max(6, soc - Math.max(kwh, 0) / 0.6)
    }
  }
  // Quarantine scenario: exactly one quarantined row that list/stat consumers must skip.
  drives.push({ id: driveId++, start: END + 3_600_000, end: END + 3_600_000 + 9 * 60_000, km: 5.5, minutes: 9,
    speedMax: 62, kwh: 1.1, from: PLACES[1], to: PLACES[3], quarantined: true })
  return { drives: drives.reverse(), charges: charges.reverse() }
}

/** Battery degradation steps aligned with the software-update timeline. */
function currentCapacityFor(asOf: number): number {
  if (asOf >= Date.UTC(2026, 8, 15)) return 59.2
  if (asOf >= Date.UTC(2026, 7, 12)) return 59.6
  return 60
}

/** Standby windows from adjacent planned drives (>= 60 min). Rows keep
 * source=mock_fixture, so qualifyParked honestly reports unqualified energy. */
function standbyWindows(): Record<string, unknown>[] {
  const ordered = planned.drives
  const windows: Record<string, unknown>[] = []
  for (let index = 0; index + 1 < ordered.length && windows.length < 10; index++) {
    const newer = ordered[index], older = ordered[index + 1]
    const start = older.end, end = newer.start
    const minutes = (end - start) / 60_000
    if (!Number.isFinite(minutes) || minutes < 60) continue
    const socStart = Math.round(100 - Math.max(older.kwh, 0) / 0.6), socEnd = Math.round(100 - Math.max(older.kwh, 0) / 0.6 - Math.max(newer.kwh, 0) / 0.6)
    windows.push({ ...{ car_id: MOCK_CAR_ID, vehicle_uid: 'mock-vehicle-uid-7' },
      older_drive_id: older.id, newer_drive_id: newer.id,
      start_date: iso(older.end), end_date: iso(newer.start),
      address: older.to, start_battery_level: socStart, end_battery_level: socEnd,
      battery_delta: socEnd - socStart, inside_temp_average: older.outsideTempAvg ?? 24.5, outside_temp_average: older.outsideTempAvg ?? 23.8,
      sample_count: 0, coverage_seconds: Math.round((end - start) / 1000), coverage_ratio: 0,
      linked_charge: null, source: 'mock_fixture', energy_contract: { version: 1, stored_change: { reason: 'no_qualified_parked_energy' } } })
  }
  return windows
}

const planned = planTimeline()

function routePoints(drive: PlannedDrive) {
  const rows: { date: string; latitude: number; longitude: number; speed: number; power: number; battery_level: number }[] = []
  const lat0 = 30.276 + (hashCode(drive.from) % 90) / 3000, lon0 = 119.985 + (hashCode(drive.to) % 120) / 4000
  const steps = Math.max(8, Math.min(36, Math.round(drive.minutes / 1.5)))
  const socDrain = drive.kwh / 0.6
  for (let step = 0; step <= steps; step++) {
    const fraction = step / steps
    const speed = Math.round(drive.speedMax * Math.sin(Math.PI * Math.min(1, fraction * 1.15)) * between(0.55, 0.95))
    rows.push({ date: iso(drive.start + fraction * drive.minutes * 60_000),
      latitude: round1((lat0 + fraction * 0.012) * 10000) / 10000, longitude: round1((lon0 + fraction * 0.014) * 10000) / 10000,
      speed, power: Math.round(speed > 4 ? between(6, 42) * (1 - fraction * 0.3) : -between(2, 18)),
      battery_level: Math.round(100 - socDrain * fraction) })
  }
  return rows
}

function hashCode(value: string) {
  let hash = 0
  for (let index = 0; index < value.length; index++) hash = (hash * 31 + value.charCodeAt(index)) | 0
  return Math.abs(hash)
}

function rawDrive(row: PlannedDrive, withRoute: boolean): RawDrive {
  return {
    drive_id: row.id, session_id: `mock-session-${row.id}`,
    start_date: iso(row.start), end_date: iso(row.end),
    start_address: row.from, end_address: row.to,
    duration_min: row.minutes,
    speed_max: row.speedMax, speed_avg: Math.round(row.speedMax * 0.62),
    speed_sample_count: Math.max(6, row.minutes), speed_metrics_source: 'observed_route_samples', speed_unit: 'km/h',
    odometer_details: { odometer_distance: row.km },
    battery_details: { start_battery_level: Math.round(100 - (row.kwh / 0.6)), end_battery_level: Math.round(100 - (row.kwh / 0.6) - row.kwh / 0.6) },
    energy_consumed_net: row.netKwh ?? row.kwh, consumption_net: Math.round((row.netKwh ?? row.kwh) * 1000 / row.km),
    outside_temp_avg: row.outsideTempAvg ?? null,
    source: 'mock_fixture', quality_state: row.quarantined ? 'quarantined' : 'observed',
    quality_reason: row.quarantined ? 'mock_fixture_quarantine_scenario' : 'mock_fixture_observed',
    ...(withRoute ? { drive_details: routePoints(row) } : {}),
  }
}

function rawCharge(row: PlannedCharge): RawCharge {
  return {
    charge_id: row.id, session_id: `mock-charge-${row.id}`,
    start_date: iso(row.start), end_date: iso(row.end),
    address: row.address, duration_min: Math.round((row.end - row.start) / 60_000),
    charge_energy_added: row.kwhAdded, charge_energy_used: round1(row.kwhAdded * between(1.03, 1.12)),
    cost: row.free ? 0 : row.teslamateZero ? 0 : round1(row.kwhAdded * (row.dc ? 1.6 : 1.14)),
    currency: 'CNY', currency_code: 'CNY', cost_currency: 'CNY',
    latitude: 30.281, longitude: 119.991,
    charger_power: row.dc ? Math.round(between(96, 138)) : 7,
    charge_type: row.dc ? 'dc' : 'ac', fast_charger_present: row.dc,
    charger_phases: row.dc ? null : 3,
    source: 'mock_fixture', quality_state: 'observed', quality_reason: 'mock_fixture_observed',
    battery_details: { start_battery_level: row.socStart, end_battery_level: row.socEnd },
    energy_contract: { version: 1, charge_mode: row.dc ? 'dc' : 'ac', charge_mode_evidence: 'mock_fixture_boundary_modes',
      battery_input: { value_kwh: row.kwhAdded, source: 'mock_fixture', quality: 'reported', coverage_ratio: 1, coverage_seconds: Math.round((row.end - row.start) / 1000) } },
  }
}

const car: RawCar = {
  car_id: MOCK_CAR_ID, vehicle_uid: 'mock-vehicle-uid-7', display_name: '合成测试车',
  model: 'Y', car_details: { model: 'Y', trim_badging: '50' },
  car_exterior: { exterior_color: '珍珠白', wheel_type: '19" Gemini' },
  car_settings: { free_supercharging: true },
  current_capacity: 60, battery_health: { current_capacity: 60 }, capacity_source_field: 'mock_fixture',
}

const status = {
  state: 'online', battery_level: 72, rated_range: 297, odometer: 20118,
  speed: 0, power: 0, charge_limit_soc: 90,
  locked: true, plugged_in: false, is_climate_on: false, sentry_mode: true,
  is_charging: false, charging_state: 'disconnected', time_to_full_charge: null,
  inside_temp: 25.1, outside_temp: 23.5,
  latitude: 30.2901, longitude: 120.0021,
  shift_state: 'P', charger_voltage: null, charger_actual_current: null, charge_energy_added: null, charger_power: null,
  // Wire shape mirrors parseTpmsDetails: flat tpms_pressure_* keys inside tpms_details.
  tpms_details: {
    tpms_pressure_fl: 2.8, tpms_pressure_fl_observed_at: iso(END - 3600_000),
    tpms_pressure_fr: 2.8, tpms_pressure_fr_observed_at: iso(END - 3600_000),
    tpms_pressure_rl: 2.8, tpms_pressure_rl_observed_at: iso(END - 3600_000),
    tpms_pressure_rr: 2.7, tpms_pressure_rr_observed_at: iso(END - 3600_000),
    pressure_unit: 'bar', source: 'mock_fixture', time_basis: 'collector_received_at',
  },
  observed_at: iso(END - 120_000), source: 'mock_fixture',
  field_sources: { battery_level: 'mock_fixture', location: 'mock_fixture', climate: 'mock_fixture' },
}

const readiness = {
  items: [
    { key: 'telemetry', status: 'available', source: 'mock_fixture', last_observed_at: iso(END - 120_000), message: null, action: null, message_key: null },
    { key: 'tpms', status: 'available', source: 'mock_fixture', last_observed_at: iso(END - 3600_000), message: null, action: null, message_key: null },
  ],
}

function page<T>(kind: 'drives' | 'charges', rows: T[], page: number, show: number) {
  const start = (page - 1) * show
  return { data: { [kind]: rows.slice(start, start + show), meta: { page, show, total: rows.length, total_pages: Math.max(1, Math.ceil(rows.length / show)), availability: 'available', source: 'mock_fixture', quality_state: 'observed' } } }
}

/** Wire-shaped response router; `null` means "no fixture for this path". */
export function mockRequest(path: string, enabled: boolean = MOCK_FIXTURE_ENABLED): Record<string, unknown> | null {
  if (!enabled) return null
  const carPrefix = `/api/v1/cars/${MOCK_CAR_ID}`
  if (path === '/api/v1/cars') return { data: { cars: [car] } }
  if (path === `${carPrefix}/snapshot`) return { data: { status, observed_at: status.observed_at, source: status.source, units: { unit_of_length: 'km', unit_of_temperature: 'C' }, field_sources: status.field_sources } }
  // Trend tools and insights read the legacy status payload; same evidence.
  if (path === `${carPrefix}/status`) return { data: { status, observed_at: status.observed_at, source: status.source, units: { unit_of_length: 'km', unit_of_temperature: 'C' }, field_sources: status.field_sources } }
  if (path === `${carPrefix}/data-readiness`) return { data: readiness }
  if (path === `${carPrefix}/telemetry/pairing`) return { data: { status: 'paired', source: 'mock_fixture' } }
  if (path === `${carPrefix}/charges/current`) return { data: { charge: null } }
  // Analysis endpoints (analysis_api.ts shapes). car_id/vehicle_uid satisfy assertVehicle.
  const vehicleKeys = { car_id: MOCK_CAR_ID, vehicle_uid: 'mock-vehicle-uid-7' }
  if (path === `${carPrefix}/battery-health`) return { data: { ...vehicleKeys, battery_health: { ...vehicleKeys,
    max_range: 298, current_range: 289, max_capacity: 60, current_capacity: currentCapacityFor(END), rated_efficiency: 142, battery_health_percentage: 99.3 },
    units: { unit_of_length: 'km' } } }
  if (/^\/api\/v1\/cars\/\d+\/updates\?page=(\d+)&show=20$/.test(path)) {
    const updates = [
      { ...vehicleKeys, update_id: 'mock-update-3', version: '2026.38.2', start_date: iso(Date.UTC(2026, 9, 8, 2, 10)), end_date: iso(Date.UTC(2026, 9, 8, 3, 5)) },
      { ...vehicleKeys, update_id: 'mock-update-2', version: '2026.32.1', start_date: iso(Date.UTC(2026, 8, 15, 1, 30)), end_date: iso(Date.UTC(2026, 8, 15, 2, 25)) },
      { ...vehicleKeys, update_id: 'mock-update-1', version: '2026.26.3', start_date: iso(Date.UTC(2026, 7, 12, 3, 0)), end_date: iso(Date.UTC(2026, 7, 12, 3, 55)) },
    ]
    const match = /page=(\d+)/.exec(path), updatePage = match ? Number(match[1]) : 1
    return { data: { ...vehicleKeys, updates: updatePage === 1 ? updates : [] }, meta: { page: updatePage, show: 20, total: updates.length, total_pages: 1, source: 'mock_fixture' } }
  }
  if (path === `/api/matelink/v1/cars/${MOCK_CAR_ID}/history-context`) return { data: { ...vehicleKeys, capability_version: 1 } }
  if (path === `${carPrefix.replace('/api/v1', '/api/matelink/v1')}/standby`) return { data: { ...vehicleKeys, windows: standbyWindows(), units: { unit_of_length: 'km', unit_of_temperature: 'C' } } }
  if (path.startsWith('/api/v1/tesla/authorization') || path.includes('/authorization/status')) return { authorized: true, state: 'authorized', source: 'mock_fixture' }
  const history = /^\/api\/v1\/cars\/\d+\/(drives|charges)\?page=(\d+)&show=(\d+)$/.exec(path)
  if (history) {
    const [, kind, pageText, showText] = history
    if (kind === 'drives') return page('drives', planned.drives.map(row => rawDrive(row, false)), Number(pageText), Number(showText))
    return page('charges', planned.charges.map(rawCharge), Number(pageText), Number(showText))
  }
  const driveDetail = /^\/api\/v1\/cars\/\d+\/drives\/(\d+)$/.exec(path)
  if (driveDetail) {
    const row = planned.drives.find(item => String(item.id) === driveDetail[1])
    return row ? { data: { drive: rawDrive(row, true) } } : { error: 'history_unavailable' }
  }
  const chargeDetail = /^\/api\/v1\/cars\/\d+\/charges\/(\d+)$/.exec(path)
  if (chargeDetail) {
    const row = planned.charges.find(item => String(item.id) === chargeDetail[1])
    return row ? { data: { charge: rawCharge(row) } } : { error: 'history_unavailable' }
  }
  const parked = /^\/api\/matelink\/v1\/cars\/\d+\/parked\/(\d+)\/(\d+)$/.exec(path)
  if (parked) {
    const older = planned.drives.find(item => String(item.id) === parked[1])
    const newer = planned.drives.find(item => String(item.id) === parked[2])
    if (!older || !newer) return { error: 'parked_history_bounds_unavailable' }
    const minutes = Math.round((Date.parse(iso(newer.start)) - Date.parse(iso(older.end))) / 60_000)
    return { data: { older_drive_id: older.id, newer_drive_id: newer.id, start_date: iso(older.end), end_date: iso(newer.start),
      address: older.to, start_battery_level: Math.round(100 - older.kwh / 0.6), end_battery_level: Math.round(100 - older.kwh / 0.6 - newer.kwh / 0.6),
      battery_delta: Math.round(newer.kwh / 0.6 - older.kwh / 0.6), energy_kwh: null, average_power_kw: null, peak_power_kw: null,
      inside_temp_average: null, outside_temp_average: null, sample_count: 0, coverage_seconds: 0, coverage_ratio: 0,
      linked_charge: null, source: 'mock_fixture' } }
  }
  return null
}

export const mockFixtureStats = { drives: planned.drives.length, charges: planned.charges.length, days: DAYS }
export const mockPlanned = planned
export const mockIso = iso
