import React from 'react'
import renderer, { act } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const fake = vi.hoisted(() => ({ request: vi.fn(), values: new Map<string, unknown>() }))
vi.mock('@tarojs/components', () => ({ Text: 'text', View: 'view', Button: 'button' }))
vi.mock('@tarojs/taro', () => ({ default: {
  request: fake.request, getStorageSync: (key: string) => fake.values.get(key),
  setStorageSync: (key: string, value: unknown) => { fake.values.set(key, value) }, removeStorageSync: (key: string) => { fake.values.delete(key) },
  getStorageInfoSync: () => ({ keys: [...fake.values.keys()] }),
} }))
import TpmsPanel from '../components/TpmsPanel'
import { observeTpmsCache, readTpmsCache } from './tpms_cache'

const now = Date.parse('2026-10-15T02:00:00Z')
beforeEach(() => {
  vi.resetModules(); fake.request.mockReset(); fake.values.clear()
  vi.stubEnv('TARO_APP_API_BASE_URL', 'https://api.example.test')
  vi.spyOn(Date, 'now').mockReturnValue(now)
})
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllEnvs() })

it('actual status API adapter carries each wheel contract into cache rebuild and rendered consumer', async () => {
  const { matelinkApi } = await import('./api')
  fake.request.mockResolvedValueOnce({ statusCode: 200, header: {}, data: { source: 'fleet_api', observed_at: '2026-10-15T02:00:00Z',
    status: { tpms_details: { pressure_unit: 'bar', source: 'telemetry_mqtt', time_basis: 'collector_received_at',
      tpms_pressure_fl: 0, tpms_pressure_fl_observed_at: '2026-10-15T01:59:00Z',
      tpms_pressure_fr: 2.9, tpms_pressure_fr_observed_at: '2026-10-14T01:00:00Z', tpms_pressure_rl: 3.1 } } } })
  const status = await matelinkApi.getCarStatus(7)
  expect(status.tpms?.wheels.fl.pressureBar).toBe(0)
  expect(status.tpms?.wheels.rl.reason).toBe('missing_or_invalid_wheel_time')
  const values = new Map<string, unknown>()
  const storage = { getStorageSync: (key: string) => values.get(key), setStorageSync: (key: string, value: unknown) => { values.set(key, JSON.parse(JSON.stringify(value))) }, getStorageInfoSync: () => ({ keys: [...values.keys()] }) }
  expect(observeTpmsCache(storage, 'owner', 'https://api.example.test', 'uid', status.tpms!, now).written).toBe(true)
  const reopened = readTpmsCache({ getStorageSync: key => values.get(key) }, 'owner', 'https://api.example.test', 'uid', now)
  expect(reopened.points).toHaveLength(2)
  const tree = renderer.create(<TpmsPanel snapshot={status.tpms} history={reopened} now={now} />)
  act(() => tree.root.findByType('button').props.onClick())
  const shown = tree.root.findAllByType('text').map(node => node.children.join('')).join('\n')
  expect(shown).toContain('旧观测 · 2026-10-14T01:00:00.000Z')
  expect(shown).toContain('缺少有效逐轮观测时间')
  expect(shown).toContain('2 点')
  expect(tree.root.findAllByProps({ className: 'metric-value' })[0].children).toEqual(['0'])
  tree.unmount()
})
