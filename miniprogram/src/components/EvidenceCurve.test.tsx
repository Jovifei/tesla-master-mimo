import React from 'react'
import TestRenderer, { act } from 'react-test-renderer'
import { afterEach, describe, expect, it, vi } from 'vitest'

const platform = vi.hoisted(() => ({ context: { clearRect: vi.fn(), setStrokeStyle: vi.fn(), setLineWidth: vi.fn(),
  beginPath: vi.fn(), moveTo: vi.fn(), lineTo: vi.fn(), stroke: vi.fn(), setLineDash: vi.fn(), setFillStyle: vi.fn(), arc: vi.fn(), fill: vi.fn(), draw: vi.fn() } }))
vi.mock('@tarojs/components', () => ({ View: 'view', Text: 'text', Button: 'button', Canvas: 'canvas', Slider: 'slider' }))
vi.mock('@tarojs/taro', () => ({ default: {
  createCanvasContext: () => platform.context,
  createSelectorQuery: () => { const query = { select: () => query,
    boundingClientRect: (callback: (rect: { width: number; height: number }) => void) => { callback({ width: 280, height: 180 }); return query }, exec: () => {} }; return query },
} }))

import EvidenceCurve from './EvidenceCurve'
import { historyDateTime } from '../domain/history_query'

type Sample = { date: string; value: number | null }
const select = vi.fn()
const curve = (points: Sample[], selected = 0) => <EvidenceCurve id="example" title="充电功率" unit="kW"
  points={points} valueOf={point => point.value} selected={selected} onSelect={select} isCurrent={() => true} />
const samples: Sample[] = [
  { date: '2026-10-14T00:00:00Z', value: 0 },
  { date: '2026-10-14T00:05:00Z', value: null },
  { date: '2026-10-14T00:10:00Z', value: -5 },
]
let tree: TestRenderer.ReactTestRenderer | null = null
afterEach(async () => { if (tree) await act(async () => tree!.unmount()); tree = null; vi.clearAllMocks() })

describe('EvidenceCurve presentation', () => {
  it.each([
    { points: [] },
    { points: [{ date: '2026-10-14T00:00:00Z', value: null }] },
    { points: [{ date: 'invalid', value: 5 }] },
  ])('shows an empty state without a fullscreen action or canvas', async ({ points }) => {
    await act(async () => { tree = TestRenderer.create(curve(points)) })
    expect(tree!.root.findAllByType('button' as React.ElementType)).toHaveLength(0)
    expect(tree!.root.findAllByType('canvas' as React.ElementType)).toHaveLength(0)
    expect(JSON.stringify(tree!.toJSON())).toContain('暂时无法显示')
  })

  it('keeps the title and fullscreen action together and preserves the selected sample', async () => {
    await act(async () => { tree = TestRenderer.create(curve(samples, 2)) })
    const header = tree!.root.findByProps({ className: 'analysis-curve-header' })
    expect(header.findByProps({ className: 'section-title' }).children.join('')).toBe('充电功率')
    expect(header.findByProps({ className: 'analysis-curve-expand' }).children.join('')).toBe('全屏查看')
    expect(tree!.root.findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('-5 kW')
    const slider = tree!.root.findByType('slider' as React.ElementType)
    expect(slider.props.max).toBe(2)
    await act(async () => slider.props.onChange({ detail: { value: 1 } }))
    expect(select).toHaveBeenCalledWith(1)
    await act(async () => tree!.update(curve(samples, 1)))
    expect(tree!.root.findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('暂无数据')
    await act(async () => tree!.update(curve(samples, 0)))
    const cursor = tree!.root.findByProps({ className: 'analysis-cursor' }).children.join('')
    expect(cursor).toContain('0 kW')
    expect(cursor).toContain(historyDateTime(samples[0].date))
  })

  it('does not draw a line across a missing observation', async () => {
    await act(async () => { tree = TestRenderer.create(curve(samples)) })
    const context = platform.context
    const graphStart = context.setStrokeStyle.mock.invocationCallOrder[1]
    const graphEnd = context.setFillStyle.mock.invocationCallOrder[0]
    expect(context.moveTo.mock.invocationCallOrder.filter(order => order > graphStart && order < graphEnd)).toHaveLength(2)
    expect(context.lineTo.mock.invocationCallOrder.filter(order => order > graphStart && order < graphEnd)).toHaveLength(0)
  })


  it('draws and labels only permitted cross-channel inference as a dashed connection', async () => {
    const points = [
      { date: '2026-10-14T00:00:00Z', value: 0, state: 'reported' as const, other: false },
      { date: '2026-10-14T00:01:00Z', value: null, state: 'absent' as const, other: true },
      { date: '2026-10-14T00:02:00Z', value: -2, state: 'reported' as const, other: false },
      { date: '2026-10-14T00:03:00Z', value: null, state: 'invalid' as const, other: true },
      { date: '2026-10-14T00:04:00Z', value: 7, state: 'reported' as const, other: false },
      { date: '2026-10-14T00:05:00Z', value: 99, state: 'reported' as const, other: false, conflict: true },
    ]
    const draw = (blocked: boolean) => <EvidenceCurve id="inference" title="电量" unit="%" points={points} valueOf={p => p.value}
      provenanceOf={p => ({ fieldState: p.state, otherChannel: p.other, sourceConflict: p.conflict })} forceDisconnected={blocked}
      selected={5} onSelect={select} isCurrent={() => true} />
    await act(async () => { tree = TestRenderer.create(draw(false)) })
    expect(platform.context.setLineDash).toHaveBeenCalledWith([4, 4], 0)
    expect(platform.context.setLineDash).toHaveBeenCalledWith([], 0)
    expect(JSON.stringify(tree!.toJSON())).toContain('虚线连接观测点')
    expect(tree!.root.findByProps({ className: 'analysis-cursor' }).children.join('')).toContain('暂无数据')
    platform.context.setLineDash.mockClear()
    await act(async () => tree!.update(draw(true)))
    expect(platform.context.setLineDash).not.toHaveBeenCalled()
    expect(JSON.stringify(tree!.toJSON())).not.toContain('虚线连接观测点')
  })

  it('leaves fullscreen when its data disappears and does not reopen it implicitly', async () => {
    await act(async () => { tree = TestRenderer.create(curve(samples)) })
    await act(async () => tree!.root.findByProps({ className: 'analysis-curve-expand' }).props.onClick())
    expect(tree!.root.findAllByProps({ className: 'card analysis-curve analysis-curve-fullscreen' })).toHaveLength(1)
    await act(async () => tree!.update(curve([])))
    expect(tree!.root.findAllByProps({ className: 'card analysis-curve analysis-curve-fullscreen' })).toHaveLength(0)
    expect(tree!.root.findAllByType('button' as React.ElementType)).toHaveLength(0)
    await act(async () => tree!.update(curve(samples)))
    expect(tree!.root.findAllByProps({ className: 'card analysis-curve analysis-curve-fullscreen' })).toHaveLength(0)
  })
})
