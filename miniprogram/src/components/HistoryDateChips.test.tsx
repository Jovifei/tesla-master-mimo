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
