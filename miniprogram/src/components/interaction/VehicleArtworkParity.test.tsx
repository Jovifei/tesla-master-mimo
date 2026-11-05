import React from 'react'
import renderer, { act } from 'react-test-renderer'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { afterEach, expect, it, vi } from 'vitest'

const effects = vi.hoisted(() => ({ request: vi.fn(), write: vi.fn() }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Image: 'image' }))
vi.mock('@tarojs/taro', () => ({ default: { request: effects.request, setStorageSync: effects.write,
  onAppHide() {}, offAppHide() {}, onAppShow() {}, offAppShow() {},
} }))
import { ConnectionVisual, EmptyHistoryVisual } from './InteractionVisuals'

let view: renderer.ReactTestRenderer | undefined
afterEach(() => {
  if (view) act(() => view!.unmount())
  view = undefined
  expect(effects.request).not.toHaveBeenCalled()
  expect(effects.write).not.toHaveBeenCalled()
})
it('binding and charging empty consumers use the same local public car image as App', () => {
  act(() => { view = renderer.create(<><ConnectionVisual phase="idle" kind="vehicle" /><EmptyHistoryVisual kind="charge" /></>) })
  const images = view!.root.findAllByType('image')
  expect(images).toHaveLength(2)
  expect(images[0].props.src).toContain('vehicle-connection.png')
  expect(images[1].props.src).toBe(images[0].props.src)
  expect(images[0].props.src).not.toMatch(/^https?:/)
  expect(images[0].props.ariaLabel).toContain('不代表当前绑定车辆')
  expect(view!.root.findByProps({ 'data-phase': 'idle' })).toBeTruthy()
})
it('keeps the packaged catalogue byte-for-byte and pins App-compatible public artwork', () => {
  const image = readFileSync(path.resolve('src/assets/interaction/vehicle-connection.png'))
  const original = readFileSync(path.resolve('vehicle-assets/vehicle-assets-5/images/myj_PPSW_WY19P.png'))
  expect(image.equals(original)).toBe(true)
  expect(createHash('sha256').update(image).digest('hex')).toBe('450d729623ffdb4aa7f2936afd6b0522f918f0b1c8de1f81cdb90baf71a56d54')
  expect([image.readUInt32BE(16), image.readUInt32BE(20)]).toEqual([720, 405])
  expect(image.length).toBeLessThan(90 * 1024)
})
