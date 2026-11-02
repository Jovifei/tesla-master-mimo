from pathlib import Path
r=Path.cwd()
def put(p,s):
 q=r/p;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s)
def append(p,s): put(p,(r/p).read_text()+s)
def rep(p,a,b):
 s=(r/p).read_text();assert s.count(a)==1,(p,a[:80],s.count(a));put(p,s.replace(a,b))
p='miniprogram/src/pages/charge_cost_consistency.test.tsx'
rep(p,"it('uses one labelled TeslaMate-zero estimate across charge, statistics, cost, report and export'", "it('keeps one labelled estimate but separates it from confirmed cost-page bill denominators'")
rep(p,"  expect(page.root.findByProps({ ariaLabel: '总费用 ≈11.4 CNY' })).toBeDefined()\n  expect(page.root.findByProps({ ariaLabel: '平均每度 ≈1.14 CNY/kWh' })).toBeDefined()", """  expect(page.root.findByProps({ ariaLabel: '完整记录估算 ≈11.4 CNY' })).toBeDefined()
  expect(page.root.findAllByProps({ ariaLabel: '平均每度 ≈1.14 CNY/kWh' })).toHaveLength(0)
  expect(page.root.findAllByType('view').filter(node => typeof node.props.ariaLabel === 'string' &&
    node.props.ariaLabel.startsWith('已记录／手动费用'))).toHaveLength(0)""")
put('miniprogram/src/domain/partial_charge_cost.test.ts', '''import { describe, expect, it, vi } from 'vitest'
import type { EnergyContract, RawCharge } from '../services/types'
import { partialChargeBatteryInput, resolveChargeEnergy } from './drive_energy'
import { chargeCosts } from './insights'
import { overlayChargeCosts, writeChargeCostOverride } from '../services/charge_cost'
vi.mock('@tarojs/taro', () => ({ default: {} }))
const scope = { userId: 'synthetic-user', stableId: 'synthetic-vehicle', origin: 'https://example.test' }
const price = { amount: 1.5, currency: 'CNY', source: 'saved_default' } as const
function raw(energy = 10): RawCharge {
  return { charge_id: 7, source: 'telemetry_mqtt', source_instance_id: 'collector-a',
    start_date: '2026-01-01T00:00:00Z', end_date: '2026-01-01T01:00:00Z', currency_code: 'CNY',
    quality_state: 'incomplete', energy_contract: { version: 1, battery_input: {
      method: 'session_counter_delta', measurement_point: 'battery_input', source: 'telemetry_mqtt',
      source_field: 'DCChargingEnergyIn', quality: 'unknown', reason: 'partial_counter_window',
      start_date: '2026-01-01T00:00:00Z', end_date: '2026-01-01T01:00:00Z',
      observed_start_at: '2026-01-01T00:05:00Z', observed_end_at: '2026-01-01T00:55:00Z',
      time_basis: 'collector_received_at', coverage_kind: 'endpoints', coverage_ratio: 5 / 6,
      covered_energy_kwh: energy } } }
}
function storage() {
 const values = new Map<string, unknown>()
 return { values, getStorageSync: (key: string) => values.get(key),
   getStorageInfoSync: () => ({ keys: [...values.keys()] }),
   setStorageSync: (key: string, value: unknown) => { values.set(key, JSON.parse(JSON.stringify(value))) },
   removeStorageSync: (key: string) => { values.delete(key) } }
}
describe('same App partial-counter evidence through actual Mini normalizer and cost consumer', () => {
  it('keeps partial energy out of full energy and bill totals, including real zero', async () => {
    const { normalizeCharge } = await import('../services/api')
    for (const amount of [10, 0]) {
      const wire = raw(amount), charge = normalizeCharge(wire)!
      expect(partialChargeBatteryInput(wire)).toBe(amount)
      expect(resolveChargeEnergy(wire).batteryInputKwh).toBeNull()
      const display = overlayChargeCosts(storage(), scope, [charge], price)[0]
      expect(display.partialEstimatedCost).toBe(amount * 1.5)
      expect(display.cost).toBeNull()
      expect(display.costProvenance).toBe('estimate')
      expect(display.energy?.batteryInputKwh).toBeNull()
      expect(chargeCosts([display]).groups).toHaveLength(0)
    }
  })
  it('manual confirmed zero overrides the estimate and survives storage reopening', async () => {
    const { normalizeCharge } = await import('../services/api')
    const charge = normalizeCharge(raw())!, store = storage()
    expect(writeChargeCostOverride(store, scope, charge, 0, 'CNY', () => true)).toBe(true)
    const reopened = { getStorageSync: (key: string) => JSON.parse(JSON.stringify(store.values.get(key) ?? null)) }
    expect(overlayChargeCosts(reopened, scope, [charge], price)[0]).toMatchObject({
      cost: 0, costProvenance: 'manual', partialEstimatedCost: null })
    expect(overlayChargeCosts(reopened, { ...scope, userId: 'other' }, [charge], price)[0].costProvenance).toBe('estimate')
  })
  it('rejects unknown, reversed, source/unit/window conflict, nonfinite and unqualified evidence', async () => {
    const base = raw(), proof = base.energy_contract as EnergyContract
    const changes = [{ source: 'fleet_telemetry' }, { unit: 'Wh' }, { quality: 'reported' },
      { covered_energy_kwh: -1 }, { covered_energy_kwh: NaN }, { covered_energy_kwh: Infinity },
      { value_kwh: 99 }, { coverage_ratio: 1 }, { coverage_ratio: 0.1 },
      { observed_start_at: null }, { observed_end_at: '2025-12-31T23:00:00Z' },
      { end_date: '2026-01-02T01:00:00Z' }, { time_basis: 'unknown' }]
    for (const change of changes) expect(partialChargeBatteryInput({ ...base,
      energy_contract: { ...proof, battery_input: { ...proof.battery_input, ...change } } })).toBeNull()
    expect(partialChargeBatteryInput({ ...base, source: 'fleet_telemetry' })).toBeNull()
    expect(partialChargeBatteryInput({ ...base, energy_contract: { ...proof, version: 2 } })).toBeNull()
    expect(partialChargeBatteryInput({ ...base, start_date: '2026-01-01T00:00:00' })).toBeNull()
    const { normalizeCharge } = await import('../services/api')
    const isolated = normalizeCharge({ ...base, quality_state: 'quarantined' })
    if (isolated) expect(overlayChargeCosts(storage(), scope, [isolated], price)[0].partialEstimatedCost).toBeNull()
  })
})
''')
append('miniprogram/src/services/cache_budget.test.ts','''

it('reserves capacity for a real manual fee while refusing cache growth without deleting anything', async () => {
  const { USER_DATA_RESERVE_BYTES, localStorageUsage } = await import('./cache_budget')
  const { writeChargeCostOverride, readChargeCostOverride } = await import('./charge_cost')
  const values = new Map<string, unknown>()
  const s = { values, getStorageSync: (key: string) => values.get(key),
    getStorageInfoSync: () => ({ keys: [...values.keys()] }),
    setStorageSync: (key: string, value: unknown) => { values.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync: (key: string) => { values.delete(key) } }
  const key = 'matelink.history.v3.synthetic', original = 'x'.repeat(CACHE_BUDGET_BYTES - USER_DATA_RESERVE_BYTES - 100)
  values.set(key, original)
  expect(canStoreCache(s, 'matelink.history.v3.next', { data: 'x'.repeat(500) })).toBe(false)
  const scope = { userId: 'owner', origin: 'https://example.test', stableId: 'v1' }
  const charge = { id: '1', source: 'teslamate_archive', qualityState: 'observed',
    startDate: '2026-01-01T00:00:00Z', endDate: '2026-01-01T01:00:00Z' } as import('./types').Charge
  expect(writeChargeCostOverride(s, scope, charge, 0, 'CNY', () => true)).toBe(true)
  expect(readChargeCostOverride(s, scope, charge)?.amount).toBe(0)
  expect(values.get(key)).toBe(original)
  const usage = localStorageUsage(s)!
  expect(usage.userBytes).toBeGreaterThan(0)
  expect(usage.totalBytes).toBeLessThan(CACHE_BUDGET_BYTES)
  expect(Object.keys(usage).sort()).toEqual(['budgetBytes', 'cacheBytes', 'reservedUserBytes', 'totalBytes', 'userBytes'])
})

it('retains a legacy over-budget archive and permits a same-size correction but no growth', () => {
  const s = storage(), key = 'matelink.history.v3.old', value = 'x'.repeat(CACHE_BUDGET_BYTES + 1)
  s.values.set(key, value)
  expect(canStoreCache(s, key, value.replace('x', 'y'))).toBe(true)
  expect(canStoreCache(s, key, value + 'x')).toBe(false)
  expect(s.values.get(key)).toBe(value)
})
''')
append('miniprogram/src/pages/wechat_startup.test.tsx','''

it('normal code upgrade reload preserves valid session and user data without Tesla or WeChat relogin', async () => {
  const session = { apiOrigin: 'https://api.example.test', accessToken: 'synthetic-valid',
    refreshToken: 'synthetic-refresh', userId: 'synthetic-owner', expiresAt: '2099-01-01T00:00:00Z', linkRequired: false }
  platform.values.set('matelink.wechat.session.v1', session)
  platform.values.set('matelink.charge-cost.v1.synthetic', { schema: 1, rows: [{ amount: 0, currency: 'CNY' }] })
  platform.values.set('matelink.manual-trips.v1.synthetic', { schema: 1, trips: [{ id: 'user-created' }] })
  platform.values.set('matelink.history.v3.synthetic', { schema: 3, items: [{ id: 'saved-history' }] })
  const before = JSON.stringify([...platform.values.entries()])
  await (await import('../services/api')).ensureWechatSession()
  vi.resetModules()
  await (await import('../services/api')).ensureWechatSession()
  expect(JSON.stringify([...platform.values.entries()])).toBe(before)
  expect(platform.login).not.toHaveBeenCalled()
  expect(platform.request).not.toHaveBeenCalled()
  expect((await import('../services/session')).readAppSession({ getStorageSync: key => platform.values.get(key) })?.userId).toBe('synthetic-owner')
})
''')
put('miniprogram/src/components/dashboard/AppGeometryContract.test.tsx','''import React from 'react'
import TestRenderer, { act } from 'react-test-renderer'
import { expect, it, vi } from 'vitest'
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button' }))
import { DashboardVehicleArt } from './DashboardParts'
import { APP_DISPLAY_CONTRACT } from '../../domain/app_display_contract'
it.each([320, 375, 430])('uses App logical geometry at %ipx without width-dependent rpx scaling', width => {
  let tree!: TestRenderer.ReactTestRenderer
  act(() => { tree = TestRenderer.create(<div style={{ width }}><DashboardVehicleArt car={{ model: 'Y' }}
    imageSrc="/synthetic-vehicle.png" imageLoading={false} imageError={false} onRetryImage={() => {}} /></div>) })
  expect(tree.root.findByProps({ className: 'dashboard-vehicle-art' }).props.style.height).toBe('170px')
  expect(tree.root.findByType('vehicle-asset').props.style).toBe('height:160px')
  expect(APP_DISPLAY_CONTRACT.vehicleHeroHeight).toBe(170)
  act(() => tree.unmount())
})
''')
append('miniprogram/src/pages/charge_cost_consistency.test.tsx','''

it('shows qualified partial charge estimates separately across list, cost and cold module reopen', async () => {
  const now = Date.now(), start = new Date(now - 3600000).toISOString(), end = new Date(now - 60000).toISOString()
  const first = new Date(now - 3300000).toISOString(), last = new Date(now - 120000).toISOString()
  const charge = { charge_id: 5, session_id: 'partial-charge', source: 'telemetry_mqtt', source_instance_id: 'source-a',
    source_vehicle_id: 'vehicle-a', source_record_id: 'record-a', quality_state: 'incomplete',
    start_date: start, end_date: end, cost: null, currency_code: 'CNY', duration_min: 59,
    energy_contract: { version: 1, battery_input: { method: 'session_counter_delta', measurement_point: 'battery_input',
      source: 'telemetry_mqtt', source_field: 'DCChargingEnergyIn', quality: 'unknown', reason: 'partial_counter_window',
      start_date: start, end_date: end, observed_start_at: first, observed_end_at: last,
      time_basis: 'collector_received_at', coverage_kind: 'endpoints',
      coverage_ratio: (Date.parse(last) - Date.parse(first)) / (Date.parse(end) - Date.parse(start)), covered_energy_kwh: 10 } } }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.endsWith('/current')) return response({ charge: null })
    if (url.endsWith('/snapshot')) return response({ status: {} })
    if (url.endsWith('/charges/5')) return response({ charge })
    if (url.includes('/charges?')) return response({ charges: [charge], meta: { page: 1, show: 20, total_pages: 1 } })
    if (url.includes('/drives?')) throw Error('cost must not depend on drive history')
    throw Error('unexpected synthetic route')
  })
  let page = await mount('charges')
  expect(page.root.findByProps({ ariaLabel: '充电费用 ≈¥11.40' })).toBeDefined()
  await unmount()
  page = await mount('cost')
  expect(page.root.findByProps({ ariaLabel: '已采集部分估算 ≈11.4 CNY' })).toBeDefined()
  const labels = page.root.findAllByType('view').map(node => node.props.ariaLabel).filter(Boolean)
  expect(labels.some(label => String(label).startsWith('已记录／手动费用'))).toBe(false)
  expect(labels.some(label => String(label).startsWith('平均每度'))).toBe(false)
  await unmount()
  vi.resetModules()
  platform.request.mockRejectedValue(new Error('synthetic offline after upgrade'))
  page = await mount('cost')
  expect(page.root.findByProps({ ariaLabel: '已采集部分估算 ≈11.4 CNY' })).toBeDefined()
})

it('efficiency analysis never invokes the unrelated charging endpoint', async () => {
  platform.params = { kind: 'efficiency' }
  platform.request.mockImplementation(async ({ url }: { url: string }) => {
    if (url.endsWith('/cars')) return response({ cars: [{ car_id: 7, vehicle_uid: 'uid7' }] })
    if (url.includes('/drives?')) return response({ drives: [], meta: { page: 1, show: 20, total_pages: 1 } })
    if (url.includes('/charges')) throw Error('unrelated charges requested')
    throw Error('unexpected test route')
  })
  const Page = (await import('./analysis/index')).default
  await act(async () => { tree = TestRenderer.create(<Page />) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  expect(platform.request.mock.calls.some(([request]) => request.url.includes('/drives?'))).toBe(true)
  expect(platform.request.mock.calls.some(([request]) => request.url.includes('/charges'))).toBe(false)
})
''')
put('docs/REMOTE-AUDIT-MINI-20261103.md','''# Mini review implementation — 2026-11-03

Repository `Jovifei/tesla-master-mimo`, branch `codex/miniprogram-e-migration-20261029`.
Baseline `46ff7cda3f082a250c6a1468fa9aac6772767497`. Candidate 0.6.4. App reference `17404cfd0aedb9019384a9c9f1579421832e760b`.

## Wired consumers

Charge list and cost page consume same-source/window-qualified partial-counter observations. Partial approximate amounts have a separate field/category, never a whole invoice, energy or bill unit-price denominator. Confirmed/manual zero wins; whole estimates remain distinct. Raw receipts are unchanged.

Efficiency/drive-curve pages do not request charging history; charge curves do not request drives. Verified local history renders before provider completion and survives successful empty responses without claiming refreshed coverage. Existing identity/cancellation fences and parked proof remain.

The actual dashboard hero/native-asset frame uses App logical 170/160 CSS-pixel heights instead of width-scaled 470/450rpx. This source geometry contract is not every native pixel or platform navigation acceptance.

Total local admission allowance remains 800KiB; new replaceable caches reserve 128KiB for user records/identity preferences. Existing full archives are retained, never evicted to manufacture space. Equal-size corrections and shrink remain possible. Settings displays anonymous counts and honest upgrade/backup limits. Actual manual-fee writes are tested against this budget.

Existing optional WeChat binding/refresh/restore is preserved. Module-reload upgrade tests retain a valid session and local records without another login request. No storage key/schema/credential is reset on version change. Date tests now prove local-calendar behavior independent of runner timezone. No WeChat upload/release occurs.

## Verification and receive

Full Mini tests, types, native/resource asset tests and clean WeChat build are mandatory. Repeat calendar/partial-cost/session/page/storage consumers in UTC, Asia/Shanghai, Asia/Tokyo and America/Los_Angeles. Read actual XML/artifacts and full repository CI at the terminal fixed SHA.

Local Codex receives exact App then Mini SHA/tree/parent in independent clean worktrees; no edits to the original E workspace, Owner files, phone, production or main. Do not replace one branch's backend with the other. Test real native visual equivalence with synthetic data first; real credentials/configuration, Tesla consent and installation require separate explicit approval. Return one consolidated sanitized GitHub evidence receipt, not business-code implementation.

## Separate scope and gates

This is not a full encrypted backup/restore library, unlimited phone storage, shared App/WeChat private storage, or all 39 App routes at native pixel parity. Summary export is not a complete recovery backup. Real Fleet events/TPMS/notifications, optional WeChat configuration, signed installation, device visuals and production rollback remain separate evidence. No production DDL, historical repairs/backfills, account deletion, vehicle wakeups, live bridge upgrades or main merge are authorized here.
''')
print('Actual Mini API/page/storage/upgrade/geometry regression files prepared.')
