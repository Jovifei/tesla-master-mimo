import React, { useState } from 'react'
import TestRenderer, { act } from 'react-test-renderer'
import { expect, it, vi } from 'vitest'
import { emptyHistoryQuery, normalizeHistoryQuery } from '../domain/history_query'

vi.mock('@tarojs/components', () => ({ Button: 'button', Picker: 'picker', View: 'view' }))
import HistoryDateChips from './HistoryDateChips'

it('preserves the latest selected date when start and end cross', async () => {
  function Harness() {
    const [query, setQuery] = useState(emptyHistoryQuery())
    return <><HistoryDateChips kind="drives" query={query} onChange={patch => setQuery(prev => normalizeHistoryQuery({ ...prev, ...patch }))} /><span>{query.startDate}~{query.endDate}</span></>
  }
  let tree!: TestRenderer.ReactTestRenderer
  await act(async () => { tree = TestRenderer.create(<Harness />) })
  const pickers = tree.root.findAllByType('picker' as React.ElementType)
  expect(pickers).toHaveLength(2)
  await act(async () => { pickers[0].props.onChange({ detail: { value: '2026-10-25' } }) })
  await act(async () => { pickers[1].props.onChange({ detail: { value: '2026-10-15' } }) })
  expect(tree.root.findByType('span').children.join('')).toBe('2026-10-15~2026-10-15')
  await act(async () => { pickers[0].props.onChange({ detail: { value: '2026-10-30' } }) })
  await act(async () => { pickers[1].props.onChange({ detail: { value: '2026-10-31' } }) })
  expect(tree.root.findByType('span').children.join('')).toBe('2026-10-30~2026-10-31')
  await act(async () => tree.unmount())
})

it('exposes Android 90-day and one-year presets with relative-day metadata', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date(2026, 10, 13, 12))
  const onChange = vi.fn()
  let tree!: TestRenderer.ReactTestRenderer
  try {
    await act(async () => { tree = TestRenderer.create(<HistoryDateChips kind="drives" query={emptyHistoryQuery()} onChange={onChange} />) })
    const button = (label: string) => tree.root.findAllByType('button').find(node => node.children.join('') === label)!
    await act(async () => button('近 90 天').props.onClick())
    expect(onChange).toHaveBeenLastCalledWith({ startDate: '2026-08-16', endDate: '2026-11-13', relativeDays: 90 })
    expect(normalizeHistoryQuery({ ...emptyHistoryQuery(), ...onChange.mock.calls[0][0] }).relativeDays).toBe(90)
    await act(async () => button('近1年').props.onClick())
    expect(onChange).toHaveBeenLastCalledWith({ startDate: '2025-11-14', endDate: '2026-11-13', relativeDays: 365 })
  } finally { if (tree) await act(async () => tree.unmount()); vi.useRealTimers() }
})
