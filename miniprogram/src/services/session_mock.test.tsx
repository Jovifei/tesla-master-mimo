import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { runInNewContext } from 'node:vm'

const platform = vi.hoisted(() => ({
  request: vi.fn(), login: vi.fn(), values: new Map<string, unknown>(),
  show: (() => {}) as () => void, hide: (() => {}) as () => void, unload: (() => {}) as () => void,
}))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Picker: 'picker', Image: 'image', Map: 'map', ScrollView: 'scroll-view', Canvas: 'canvas', Slider: 'slider', Checkbox: 'checkbox', CheckboxGroup: 'checkboxgroup' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: platform.request, login: platform.login,
  getStorageSync: (key: string) => platform.values.get(key),
  setStorageSync: (key: string, value: unknown) => platform.values.set(key, value),
  removeStorageSync: (key: string) => platform.values.delete(key),
  getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }),
  navigateTo: vi.fn(), switchTab: vi.fn(), showModal: vi.fn(), hideTabBar: vi.fn(), showTabBar: vi.fn(), pageScrollTo: vi.fn(() => Promise.resolve()),
}, useDidShow: (fn: () => void) => { platform.show = fn }, useDidHide: (fn: () => void) => { platform.hide = fn },
useUnload: (fn: () => void) => { platform.unload = fn }, usePageScroll: () => {} }))

let page: ReactTestRenderer | null = null
beforeEach(() => {
  vi.resetModules()
  vi.stubEnv('TARO_APP_API_BASE_URL', 'https://fixture.invalid/')
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'true')
  platform.values.clear(); platform.request.mockReset(); platform.login.mockReset()
  platform.request.mockImplementation(() => { throw new Error('unexpected request') })
  platform.login.mockImplementation(() => { throw new Error('unexpected login') })
  platform.show = () => {}; platform.hide = () => {}; platform.unload = () => {}
})
afterEach(async () => {
  if (page) { await act(async () => { platform.unload(); page!.unmount() }); page = null }
  vi.unstubAllEnvs()
})

it('fixture session and real read APIs use the configured isolated origin without network', async () => {
  const session = await import('./session')
  const api = await import('./api')
  const read = vi.fn(() => { throw new Error('unexpected session read') })
  expect(session.readAppSession({ getStorageSync: read })).toMatchObject({ apiOrigin: 'https://fixture.invalid', userId: 'mock-fixture-user' })
  expect(read).not.toHaveBeenCalled()
  await api.ensureWechatSession()
  const [car] = await api.matelinkApi.getCars()
  expect(car).toMatchObject({ id: 7, stableId: 'mock-vehicle-uid-7' })
  expect(await api.matelinkApi.getCarSnapshot(car.id, car)).toMatchObject({ source: 'mock_fixture', batteryLevel: 72 })
  const drives = await api.matelinkApi.getDrives(car.id, 1, 20, car)
  const charges = await api.matelinkApi.getCharges(car.id, 1, 20, car)
  expect(drives.items).toHaveLength(20)
  expect(charges.items).toHaveLength(20)
  expect(drives.items.every(row => row.source === 'mock_fixture')).toBe(true)
  expect(charges.items.every(row => row.source === 'mock_fixture')).toBe(true)
  expect(charges.items.every(row => row.energyAddedKwh !== null && row.energyAddedKwh >= 0)).toBe(true)
  expect((await api.matelinkApi.getDriveDetail(car.id, drives.items[0].id, car))?.source).toBe('mock_fixture')
  expect((await api.matelinkApi.getChargeDetail(car.id, charges.items[0].id, car))?.source).toBe('mock_fixture')
  const evidence = await (await import('./analysis_api')).analysisApi.getChargeEvidence(car, charges.items[0].id)
  expect(evidence.points.filter(point => point.batteryLevel !== null)).toHaveLength(11)
  expect(evidence.points.filter(point => point.powerKw !== null)).toHaveLength(10)
  expect(evidence.source).toBe('mock_fixture')
  await expect(api.requestJson('/unmapped-fixture-route')).rejects.toMatchObject({ code: 'mock_fixture_unavailable' })
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.login).not.toHaveBeenCalled()
})

it('fixture home renders the synthetic vehicle using isolated session and platform mocks', async () => {
  const Home = (await import('../pages/vehicle/index')).default
  await act(async () => { page = TestRenderer.create(React.createElement(Home)) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  await vi.waitFor(() => expect(JSON.stringify(page!.toJSON())).toContain('合成测试车'))
  await vi.waitFor(() => expect(page!.root.findByProps({ className: 'dashboard-soc-value' }).children.join('')).toBe('72%'))
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.login).not.toHaveBeenCalled()
})

it.each(['drives', 'charges'] as const)('fixture %s page renders synthetic history without network', async kind => {
  const { historyCacheKey } = await import('./history_cache')
  const key = historyCacheKey(kind, 'mock-fixture-user', 'mock-vehicle-uid-7', 'https://fixture.invalid')
  platform.values.set(`${key}.query`, { startDate: '', endDate: '', minDistanceKm: 0 })
  const Page = kind === 'drives' ? (await import('../pages/drives/index')).default : (await import('../pages/charges/index')).default
  await act(async () => { page = TestRenderer.create(React.createElement(Page)) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  if (kind === 'drives') {
    await vi.waitFor(() => expect(page!.root.findAllByProps({ ariaLabel: '较旧日期' })).toHaveLength(1))
    if (page!.root.findAllByProps({ className: 'history-record' }).length === 0) {
      const navigation = ['较旧日期', '较新日期'].map(label => page!.root.findByProps({ ariaLabel: label })).find(button => !button.props.disabled)
      expect(navigation).toBeDefined()
      await act(async () => { navigation!.props.onClick(); await new Promise(resolve => setTimeout(resolve, 0)) })
    }
  }
  await vi.waitFor(() => expect(page!.root.findAllByProps({ className: 'history-record' }).length).toBeGreaterThan(0))
  expect(page!.root.findByProps({ className: 'card fixture-data-notice' }).findByType('text' as React.ElementType).children.join('')).toBe('合成测试数据，未连接真实车辆')
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.login).not.toHaveBeenCalled()
})

it('fixture history exposes adjacent source-matched parking candidates and proof without network', async () => {
  const api = await import('./api')
  const { collectHistoryInterval } = await import('../domain/history_controller')
  const { verifiedParkedPairs, buildDriveHistoryItems } = await import('../domain/drive_history')
  const { historyRowKey } = await import('../domain/history')
  const { createParkedProofVerifier } = await import('./parked_proof')
  const [car] = await api.matelinkApi.getCars()
  const first = await api.matelinkApi.getDrives(car.id, 1, 20, car)
  const raw = [...first.items]
  const snapshot = await collectHistoryInterval(first, async pageNumber => {
    const response = await api.matelinkApi.getDrives(car.id, pageNumber, 20, car)
    raw.push(...response.items)
    return response
  }, () => true)
  expect(snapshot).not.toBeNull()
  expect(snapshot?.complete, snapshot?.reason ?? '').toBe(true)
  expect(new Set(raw.map(historyRowKey)).size).toBe(raw.length)
  const items = snapshot!.items
  expect(buildDriveHistoryItems(first.items.filter(row => row.qualityState !== 'quarantined'), verifiedParkedPairs(items)).some(item => item.kind === 'parked')).toBe(true)
  const parked = buildDriveHistoryItems(items, verifiedParkedPairs(items)).find(item => item.kind === 'parked')
  expect(parked?.kind).toBe('parked')
  if (!parked || parked.kind !== 'parked') throw new Error('no fixture parking candidate')
  const candidate = { olderId: parked.older.id, newerId: parked.newer.id, startDate: parked.startDate, endDate: parked.endDate, source: 'mock_fixture' }
  const verifier = createParkedProofVerifier(car, () => true)
  expect((await verifier.verify([candidate])).size).toBe(1)
  expect(verifier.get(candidate)).toMatchObject({ source: 'mock_fixture', energyKwh: null })
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.login).not.toHaveBeenCalled()
})

it('fixture drives page shows verified synthetic parking with unknown energy and no network', async () => {
  const { historyCacheKey } = await import('./history_cache')
  const history = await import('../domain/drive_history')
  const pairs = vi.spyOn(history, 'verifiedParkedPairs')
  const verifierModule = await import('./parked_proof')
  const makeVerifier = verifierModule.createParkedProofVerifier
  const verifiedCounts: number[] = []
  const verifier = vi.spyOn(verifierModule, 'createParkedProofVerifier').mockImplementation((...args) => {
    const instance = makeVerifier(...args), verify = instance.verify
    instance.verify = async (...verifyArgs) => {
      const result = await verify(...verifyArgs)
      verifiedCounts.push(result.size)
      return result
    }
    return instance
  })
  const key = historyCacheKey('drives', 'mock-fixture-user', 'mock-vehicle-uid-7', 'https://fixture.invalid')
  platform.values.set(`${key}.query`, { startDate: '', endDate: '', minDistanceKm: 0 })
  const Drives = (await import('../pages/drives/index')).default
  await act(async () => { page = TestRenderer.create(React.createElement(Drives)) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  await vi.waitFor(() => expect(pairs).toHaveBeenCalled(), { timeout: 3000 })
  expect(pairs.mock.results.some(result => result.value instanceof Set && result.value.size > 0)).toBe(true)
  await vi.waitFor(() => expect(verifier).toHaveBeenCalled(), { timeout: 3000 })
  await vi.waitFor(() => expect(verifiedCounts.length).toBeGreaterThan(0), { timeout: 3000 })
  expect(verifiedCounts.some(count => count > 0), `verified counts ${verifiedCounts.join(',')}`).toBe(true)
  // The day stack shows one day at a time; a verified window may be on a later card.
  for (let day = 0; day < 20 && page!.root.findAllByProps({ className: 'history-record history-parked' }).length === 0; day++) {
    const older = page!.root.findAllByProps({ ariaLabel: '较旧日期' })[0]
    if (!older || older.props.disabled) break
    await act(async () => { older.props.onClick() })
  }
  expect(page!.root.findAllByProps({ className: 'history-record history-parked' }).length).toBeGreaterThan(0)
  const row = page!.root.findAllByProps({ className: 'history-record history-parked' })[0]
  await act(async () => { row.props.onClick(); await new Promise(resolve => setTimeout(resolve, 0)) })
  const detail = page!.root.findByProps({ ariaLabel: '驻车详情' })
  expect(detail.findAllByProps({ className: 'card fixture-data-notice' })).toHaveLength(1)
  expect(detail.findAllByType('text' as React.ElementType).some(node => node.children.join('').includes('合成测试数据'))).toBe(true)
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.login).not.toHaveBeenCalled()
}, 10000)

it('fixture charge detail labels observed synthetic quality without network', async () => {
  const { historyCacheKey } = await import('./history_cache')
  const key = historyCacheKey('charges', 'mock-fixture-user', 'mock-vehicle-uid-7', 'https://fixture.invalid')
  platform.values.set(`${key}.query`, { startDate: '', endDate: '', minDistanceKm: 0 })
  const Charges = (await import('../pages/charges/index')).default
  await act(async () => { page = TestRenderer.create(React.createElement(Charges)) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  await vi.waitFor(() => expect(page!.root.findAllByProps({ className: 'history-record' }).length).toBeGreaterThan(0))
  await act(async () => { page!.root.findAllByProps({ className: 'history-record' })[0].props.onClick(); await new Promise(resolve => setTimeout(resolve, 0)) })
  expect(JSON.stringify(page!.toJSON())).toContain('合成观测')
  expect(page!.root.findByProps({ ariaLabel: '充电详情' }).findAllByProps({ className: 'card fixture-data-notice' })).toHaveLength(1)
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.login).not.toHaveBeenCalled()
})

it('fixture More does not read or change real consent, session, pending authorization or recovery keys', async () => {
  const origin = 'https://fixture.invalid'
  const protectedKeys = ['matelink.consent.v1', 'matelink.wechat.session.v1', 'matelink.wechat.tesla-auth.v1',
    'matelink.wechat.link.v1', `matelink.wechat.recovery.v1.${encodeURIComponent(origin)}`]
  for (const key of protectedKeys) platform.values.set(key, { sentinel: key })
  const before = JSON.stringify([...platform.values])
  const Taro = (await import('@tarojs/taro')).default
  const reads = vi.spyOn(Taro, 'getStorageSync')
  const Page = (await import('../pages/me/index')).default
  await act(async () => { page = TestRenderer.create(React.createElement(Page)) })
  await act(async () => { platform.show(); await new Promise(resolve => setTimeout(resolve, 0)) })
  for (const key of protectedKeys) expect(reads).not.toHaveBeenCalledWith(key)
  expect(JSON.stringify([...platform.values])).toBe(before)
  expect(platform.request).not.toHaveBeenCalled()
  expect(platform.login).not.toHaveBeenCalled()
  reads.mockRestore()
})

it('fixture history cache misses never enumerate real keys and current fixture cache remains readable', async () => {
  const { historyCacheKey, readHistoryCache } = await import('./history_cache')
  const key = historyCacheKey('drives', 'mock-fixture-user', 'mock-vehicle-uid-7', 'https://fixture.invalid')
  const realSentinel = { sentinel: true }
  const values = new Map<string, unknown>([['matelink.history.real-sentinel', realSentinel]])
  const storage = { getStorageSync: vi.fn((name: string) => values.get(name)),
    setStorageSync: vi.fn((name: string, value: unknown) => { values.set(name, value) }),
    getStorageInfoSync: vi.fn(() => ({ keys: [...values.keys()] })) }
  expect(readHistoryCache(storage, key)).toEqual([])
  expect(storage.getStorageInfoSync).not.toHaveBeenCalled()
  expect(storage.getStorageSync).not.toHaveBeenCalledWith('matelink.history.real-sentinel')
  const fixture = { schema: 3, scope: key, items: [{ id: 'synthetic' }], savedAt: '2026-01-01T00:00:00Z' }
  values.set(key, fixture)
  expect(readHistoryCache(storage, key)).toEqual([{ id: 'synthetic' }])
  expect(values.get('matelink.history.real-sentinel')).toBe(realSentinel)
  expect(storage.setStorageSync).not.toHaveBeenCalled()
})

it('fixture preview read, write and reset stay separate from a same-origin real preview', async () => {
  const scope = { origin: 'https://fixture.invalid', userId: null, stableId: null }
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'false'); vi.resetModules()
  const real = await import('./vehicle_appearance')
  const realKey = real.vehicleAppearanceKey(scope)!
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'true'); vi.resetModules()
  const budget = await import('./cache_budget')
  const allowWrites = vi.spyOn(budget, 'canStoreCache').mockReturnValue(true)
  try {
    const fixture = await import('./vehicle_appearance')
    const fixtureKey = fixture.vehicleAppearanceKey(scope)!
    expect(fixtureKey).not.toBe(realKey)
    const config = { model: 'Y', variant: 'myj', exteriorColor: 'PX02', wheelType: 'WY19P' }
    const sentinel = { schema: 1, scope: realKey, source: 'user_configured', config }
    const values = new Map<string, unknown>([[realKey, sentinel]])
    const storage = { getStorageSync: vi.fn((name: string) => values.get(name)),
      setStorageSync: vi.fn((name: string, value: unknown) => { values.set(name, value) }),
      removeStorageSync: vi.fn((name: string) => { values.delete(name) }),
      getStorageInfoSync: vi.fn(() => ({ keys: [...values.keys()] })) }
    expect(fixture.readVehicleAppearance(storage, scope)).toBeNull()
    expect(fixture.writeVehicleAppearance(storage, scope, config)).toBe(true)
    expect(fixture.readVehicleAppearance(storage, scope)).toEqual(config)
    expect(fixture.clearVehicleAppearance(storage, scope)).toBe(true)
    expect(values.get(realKey)).toBe(sentinel)
    expect(storage.getStorageSync).not.toHaveBeenCalledWith(realKey)
    expect(storage.setStorageSync).not.toHaveBeenCalledWith(realKey, expect.anything())
    expect(storage.removeStorageSync).not.toHaveBeenCalledWith(realKey)
  } finally { allowWrites.mockRestore() }
})

it('fixture session fails closed without an HTTPS configured origin', async () => {
  vi.stubEnv('TARO_APP_API_BASE_URL', '')
  const { readAppSession } = await import('./session')
  const read = vi.fn()
  expect(readAppSession({ getStorageSync: read })).toBeNull()
  expect(read).not.toHaveBeenCalled()
})

it('real mode still reads only a session matching the configured origin', async () => {
  vi.stubEnv('TARO_APP_MOCK_FIXTURE', 'false')
  const { readAppSession } = await import('./session')
  const stored = { apiOrigin: 'https://fixture.invalid', accessToken: 'synthetic-access', refreshToken: null, expiresAt: null, userId: 'test-user', linkRequired: false }
  const storage = { getStorageSync: () => stored }
  expect(readAppSession(storage)).toMatchObject({ apiOrigin: stored.apiOrigin, userId: stored.userId })
  expect(readAppSession({ getStorageSync: () => ({ ...stored, apiOrigin: 'https://different.invalid' }) })).toBeNull()
})

it('fixture session origin survives actual webpack DefinePlugin without a process global', async () => {
  const require = createRequire(join(process.cwd(), 'package.json'))
  const webpack = require('webpack'), ts = require('typescript')
  const { createFsFromVolume, Volume } = require('memfs')
  const transpile = (name: string) => ts.transpileModule(readFileSync(join(process.cwd(), 'src/services', name), 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext },
  }).outputText as string
  const fixtureUri = `data:text/javascript;base64,${Buffer.from(transpile('mock_fixture.ts')).toString('base64')}`
  const sessionSource = transpile('session.ts').replace("from './mock_fixture'", `from '${fixtureUri}'`)
  const memory = createFsFromVolume(new Volume())
  const compiler = webpack({
    mode: 'none', target: 'node', cache: false,
    entry: `data:text/javascript;base64,${Buffer.from(sessionSource).toString('base64')}`,
    output: { path: '/virtual', filename: 'session.js', library: { type: 'commonjs2' } },
    plugins: [new webpack.DefinePlugin({
      'process.env.TARO_APP_MOCK_FIXTURE': JSON.stringify('true'),
      'process.env.TARO_APP_API_BASE_URL': JSON.stringify('https://fixture.invalid/'),
    })],
  })
  compiler.outputFileSystem = memory
  await new Promise<void>((resolve, reject) => compiler.run((error: Error | null, stats: { hasErrors: () => boolean }) => {
    if (error) reject(error)
    else if (stats.hasErrors()) reject(new Error('session webpack compilation failed'))
    else resolve()
  }))
  const sandbox = { module: { exports: {} as { readAppSession: (storage: { getStorageSync: () => never }) => { apiOrigin: string } } }, exports: {} }
  runInNewContext(memory.readFileSync('/virtual/session.js', 'utf8'), sandbox)
  expect(sandbox.module.exports.readAppSession({ getStorageSync: () => { throw new Error('unexpected session read') } }).apiOrigin).toBe('https://fixture.invalid')
  await new Promise<void>((resolve, reject) => compiler.close((error: Error | null) => error ? reject(error) : resolve()))
})
