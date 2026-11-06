import { expect, it } from 'vitest'
import { diagnosticSteps, pairingPhase, pairingPhaseDetail } from './diagnostic'
import type { Car, CarStatus } from '../services/types'

const car: Car = { id: 7, stableId: 'fixture-uid', name: null, model: null, trim: null, exteriorColor: null, wheelType: null, displayName: null }
const status = { batteryLevel: 0, source: 'fleet_native', observedAt: '2026-10-15T05:00:00Z' } as CarStatus
const states = (input: Parameters<typeof diagnosticSteps>[0]) => Object.fromEntries(diagnosticSteps(input).map(step => [step.key, step.state]))

it('defaults every evidence step to UNKNOWN without promoting available server readiness', () => {
  const input = { items: [{ key: 'telemetry', status: 'available', message: null, action: null }] }
  expect(Object.values(states(input))).toEqual(Array(6).fill('UNKNOWN'))
})
it('a permission blocker cannot be overridden by an available pairing field', () => {
  expect(states({ sessionReady: true, pairing: { status: 'available', virtual_key_url: null, config_synced: true, updated_at: null, error_class: null },
    items: [{ key: 'live_status', status: 'permission_required', message: null, action: null }] }).authorization).toBe('WARNING')
})
it('actual zero is reception and display evidence only; missing server authorization and quality stay unknown', () => {
  expect(states({ car, sessionReady: true, status, now: Date.parse(status.observedAt!) })).toEqual({
    authorization: 'UNKNOWN', identity: 'PASS', receive: 'PASS', qualification: 'UNKNOWN', history: 'UNKNOWN', display: 'WARNING',
  })
})
it('marks stale, clock-skewed and offline snapshots without promoting them to online success', () => {
  expect(diagnosticSteps({ car, sessionReady: true, status, now: Date.parse('2026-10-17T05:00:00Z') })[2].freshness).toBe('stale_or_skewed')
  expect(diagnosticSteps({ car, sessionReady: true, status, now: Date.parse('2026-10-14T05:00:00Z') })[2].freshness).toBe('stale_or_skewed')
  const steps = diagnosticSteps({ car, sessionReady: true, status, cached: true, onlineError: true })
  expect(steps[2].state).toBe('WARNING')
  expect(steps[2].detail).toContain('本次在线读取失败')
  expect(steps[5].detail).toContain('订阅通知尚未接入（UNSUPPORTED）')
})
it('historical persistence is independent of qualification and never confers current or Android persistence', () => {
  const input = { car, sessionReady: true, history: { rows: 2, qualified: 0, quarantined: 1, savedAt: null } }
  expect(states(input)).toMatchObject({ receive: 'UNKNOWN', qualification: 'WARNING', history: 'PASS', display: 'UNKNOWN' })
  expect(states({ ...input, history: { ...input.history, qualified: 1 } }).qualification).toBe('PASS')
  expect(diagnosticSteps(input)[4].detail).toContain('胎压仅有本机前台观测历史')
  expect(states({ history: input.history }).history).toBe('UNKNOWN')
})

it.each(['tpms','charges','location'])('capability %s permission overrides aggregate pairing availability',key=>{
 expect(states({sessionReady:true,pairing:{status:'available',virtual_key_url:null,config_synced:true,updated_at:null,error_class:null},items:[{key,status:'permission_required',message:null,action:null}]}).authorization).toBe('WARNING')
})
it('pairing permission error overrides its available status',()=>{
 expect(states({sessionReady:true,pairing:{status:'available',virtual_key_url:null,config_synced:true,updated_at:null,error_class:'permission_required'}}).authorization).toBe('WARNING')
})

it('snapshot metadata alone is not a vehicle observation; real zero and false remain received', () => {
 const metadata = { source: 'fleet_api', observedAt: '2026-10-16T00:00:00Z', fieldSources: { Soc: 'fleet_api' } } as unknown as CarStatus
 expect(states({ car, sessionReady: true, status: metadata }).receive).toBe('UNKNOWN')
 expect(states({ car, sessionReady: true, status: { ...metadata, batteryLevel: 0 } }).receive).toBe('PASS')
 expect(states({ car, sessionReady: true, status: { ...metadata, locked: false } }).receive).toBe('PASS')
 expect(states({ car, sessionReady: true, status: { ...metadata, tpms: { wheels: {} } } as CarStatus }).receive).toBe('UNKNOWN')
})

const pairingFixture = (overrides: Record<string, unknown> = {}) => ({
  status: 'key_confirmed', phase: null, virtual_key_url: null,
  config_synced: false, updated_at: null, error_class: null, ...overrides,
})

it('server-sent pairing phase wins over raw fields', () => {
  expect(pairingPhase(pairingFixture({ phase: 'configuring', config_synced: true }))).toBe('configuring')
  expect(pairingPhase(pairingFixture({ phase: 'ready', status: 'waiting_vehicle' }))).toBe('ready')
  expect(pairingPhase(pairingFixture({ phase: 'error', status: 'collecting' }))).toBe('error')
  expect(pairingPhase(pairingFixture({ phase: 'something_new' }))).toBe('configuring')
})

it('legacy pairing derives the phase from status and config sync', () => {
  expect(pairingPhase(null)).toBeNull()
  expect(pairingPhase(pairingFixture({ status: 'pairing_required' }))).toBe('pairing_required')
  expect(pairingPhase(pairingFixture({ status: 'billing_blocked' }))).toBe('blocked')
  expect(pairingPhase(pairingFixture({ status: 'telemetry_error' }))).toBe('error')
  expect(pairingPhase(pairingFixture({ status: 'collecting' }))).toBe('ready')
  expect(pairingPhase(pairingFixture({ config_synced: true }))).toBe('awaiting_first_event')
  expect(pairingPhase(pairingFixture({ config_synced: false }))).toBe('configuring')
})

it('receive step detail carries the graded onboarding phase', () => {
  const receive = diagnosticSteps({
    sessionReady: true,
    pairing: pairingFixture({ phase: 'awaiting_first_event', config_synced: true }),
  }).find(step => step.key === 'receive')
  expect(receive?.detail).toContain('正在等待车辆首次数据')
  expect(pairingPhaseDetail('configuring')).toContain('正在同步车辆配置')
  expect(pairingPhaseDetail(null)).toBe('')
})
