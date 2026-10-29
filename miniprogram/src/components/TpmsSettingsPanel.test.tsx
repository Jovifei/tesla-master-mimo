import React from 'react'
import TestRenderer, { act, type ReactTestRenderer } from 'react-test-renderer'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import TpmsSettingsPanel from './TpmsSettingsPanel'
import { readTpmsProfile } from '../services/tpms_profile'
const platform = vi.hoisted(() => ({ values: new Map<string, unknown>() }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Input: 'input', Switch: 'switch' }))
vi.mock('@tarojs/taro', () => ({ default: { getStorageSync: (key: string) => platform.values.get(key), setStorageSync: (key: string, value: unknown) => platform.values.set(key, value), getStorageInfoSync: () => ({ keys: [...platform.values.keys()] }) } }))
const scope = { origin: 'https://api.example.test', userId: 'a', stableId: 'uid' }
const store = { getStorageSync: (key: string) => platform.values.get(key) }
let renderer: ReactTestRenderer | null = null
beforeEach(() => platform.values.clear())
afterEach(async () => { if (renderer) await act(async () => renderer!.unmount()); renderer = null })
async function mount(isCurrent = () => true) { await act(async () => { renderer = TestRenderer.create(<TpmsSettingsPanel scope={scope} isCurrent={isCurrent} />) }) }
async function fill(values = ['2.9', '2.6', '3.4', '.3', '24']) { for (let index = 0; index < values.length; index++) await act(async () => { renderer!.root.findAllByType('input')[index].props.onInput({ detail: { value: values[index] } }) }) }
async function save() { await act(async () => renderer!.root.findByType('button').props.onClick()) }
it('requires explicit pressure input, saves actual bounds and reopens exact profile', async () => {
  await mount(); expect(renderer!.root.findAllByType('input').map(node => node.props.value)).toEqual(['', '', '', '', ''])
  await fill(); await act(async () => renderer!.root.findAllByType('switch' as React.ElementType)[0].props.onChange({ detail: { value: true } }))
  await save(); expect(readTpmsProfile(store, scope)).toMatchObject({ enabled: true, targetBar: 2.9, lowBar: 2.6 })
  await act(async () => renderer!.unmount()); renderer = null; await mount()
  expect(renderer!.root.findAllByType('input')[0].props.value).toBe('2.9')
})
it('zero threshold is invalid per App while .1 and720 bounds remain valid', async () => {
  await mount(); await fill(['2.9', '0', '3.4', '.1', '720']); await save()
  expect(platform.values.size).toBe(0)
  await fill(['2.9', '2.6', '3.4', '.1', '720']); await save()
  expect(readTpmsProfile(store, scope)).toMatchObject({ deltaBar: .1, windowHours: 720 })
})
it('clears drafts on scope changes and rejects stale same-view save', async () => {
  let current = true
  await mount(() => current); await fill(); current = false; await save()
  expect(platform.values.size).toBe(0)
  expect(renderer!.root.findAllByType('input')[0].props.value).toBe('')
  await act(async () => renderer!.update(<TpmsSettingsPanel scope={{ ...scope, userId: 'b' }} isCurrent={() => true} />))
  expect(renderer!.root.findAllByType('input').map(node => node.props.value)).toEqual(['', '', '', '', ''])
})
