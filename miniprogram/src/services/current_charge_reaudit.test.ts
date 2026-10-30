import { beforeEach, expect, it, vi } from 'vitest'

const fake = vi.hoisted(() => ({ request: vi.fn(), storage: new Map<string, unknown>() }))
vi.mock('@tarojs/taro', () => ({ default: { request: fake.request,
  getStorageSync: (key: string) => fake.storage.get(key), setStorageSync: (key: string, value: unknown) => fake.storage.set(key, value),
  removeStorageSync: (key: string) => fake.storage.delete(key), getStorageInfoSync: () => ({ keys: [...fake.storage.keys()] }) } }))
const response = (data: unknown) => ({ statusCode: 200, data: { data }, header: {} })
beforeEach(() => { vi.resetModules(); fake.storage.clear(); fake.request.mockReset(); vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test') })

it('parses nested live SOC, energy, observation and preserves snapshot versus historical meaning', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ car: { car_id: 7 }, charge: { charge_id: 42, source: 'fleet_api',
    quality_state: 'observed', quality_reason: 'live_provider_snapshot', start_date: '2026-10-30T01:00:00Z',
    observed_at: '2026-10-30T01:00:00Z', charge_energy_added: 5, charger_power: 0, is_charging: true,
    battery_details: { current_battery_level: 66 } } }))
  await expect(matelinkApi.getCurrentCharge(7)).resolves.toMatchObject({ startDate: null, currentIsSnapshot: true,
    currentObservedAt: '2026-10-30T01:00:00Z', currentEnergyAddedKwh: 5, energyAddedKwh: null,
    currentBatteryLevel: 66, chargerPowerKw: 0, isCharging: true, currentVehicleIdentityVerified: true })
})

it('retains actual session start for an open session and does not expose live energy as closed-history evidence', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ car: { car_id: 7 }, charge: { charge_id: 5, source: 'telemetry_mqtt',
    quality_state: 'observed', start_date: '2026-10-30T00:00:00Z', observed_at: '2026-10-30T01:00:00Z',
    charge_energy_added: 0, is_charging: true, battery_details: { current_battery_level: 0 } } }))
  await expect(matelinkApi.getCurrentCharge(7)).resolves.toMatchObject({ startDate: '2026-10-30T00:00:00Z', currentIsSnapshot: false,
    currentEnergyAddedKwh: 0, energyAddedKwh: null, currentBatteryLevel: 0, isCharging: true })
})

it('uses a live snapshot start_date only as its fallback observation timestamp', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ car: { car_id: 7 }, charge: { charge_id: 42, source: 'fleet_api',
    quality_reason: 'live_provider_snapshot', start_date: '2026-10-30T01:00:00Z', is_charging: true } }))
  await expect(matelinkApi.getCurrentCharge(7)).resolves.toMatchObject({ startDate: null,
    currentIsSnapshot: true, currentObservedAt: '2026-10-30T01:00:00Z' })
})

it('rejects foreign nested car identity and records missing identity as unverified', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ car: { car_id: 9 }, charge: { charge_id: 42 } }))
  await expect(matelinkApi.getCurrentCharge(7)).rejects.toMatchObject({ code: 'vehicle_not_found' })
  fake.request.mockResolvedValueOnce(response({ charge: { charge_id: 42 } }))
  await expect(matelinkApi.getCurrentCharge(7)).resolves.toMatchObject({ currentVehicleIdentityVerified: false })
})

it('recognizes a matching stable vehicle UID and rejects a mismatched one', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ cars: [{ car_id: 7, vehicle_uid: 'uid-7' }] }))
  const [car] = await matelinkApi.getCars()
  fake.request.mockResolvedValueOnce(response({ car: { vehicle_uid: 'uid-7' }, charge: { charge_id: 42 } }))
  await expect(matelinkApi.getCurrentCharge(7, car)).resolves.toMatchObject({ currentVehicleIdentityVerified: true })
  fake.request.mockResolvedValueOnce(response({ car: { vehicle_uid: 'uid-other' }, charge: { charge_id: 42 } }))
  await expect(matelinkApi.getCurrentCharge(7, car)).rejects.toMatchObject({ code: 'vehicle_not_found' })
})

it('does not report a malformed current-session response as a valid empty session', async () => {
  const { matelinkApi } = await import('./api')
  for (const invalid of [{ car: 'unexpected', charge: { charge_id: 42 } }, { car: { car_id: 7 }, charge: 'unexpected' }, { car: { car_id: 7 } }]) {
    fake.request.mockResolvedValueOnce(response(invalid))
    await expect(matelinkApi.getCurrentCharge(7)).rejects.toMatchObject({ code: 'invalid_api_response' })
  }
})

it('continues a raw full page despite an invalid row and reports the discarded observation', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce(response({ drives: [...Array.from({ length: 19 }, (_, i) => ({ drive_id: i + 1 })), {}] }))
  const page = await matelinkApi.getDrives(7, 1, 20)
  expect(page.items).toHaveLength(19)
  expect(page.meta).toMatchObject({ hasMore: true, discardedRows: 1 })
})

it('maps the backend no-active-charge sentinel to an absent session without masking other errors', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce({ statusCode: 200, data: { data: null, error: 'No active charging in progress.' }, header: {} })
  await expect(matelinkApi.getCurrentCharge(7)).resolves.toBeNull()
  fake.request.mockRejectedValueOnce(new Error('network'))
  await expect(matelinkApi.getCurrentCharge(7)).rejects.toMatchObject({ code: 'network_error' })
})
