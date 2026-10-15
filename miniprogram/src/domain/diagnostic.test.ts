import { expect, it } from 'vitest'
import { diagnosticSteps } from './diagnostic'
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
