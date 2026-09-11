import { describe, expect, it } from 'vitest'
import { mergeHistoryByKey } from './history'

describe('history merge', () => {
  it('retains local rows when the cloud page is empty', () => {
    const result = mergeHistoryByKey(
      [{ id: 'local-1', distance: null }],
      [],
      row => row.id,
    )
    expect(result.items).toEqual([{ id: 'local-1', distance: null }])
    expect(result.retainedLocalCount).toBe(1)
    expect(result.addedRemoteCount).toBe(0)
  })

  it('does not duplicate a stable row key', () => {
    const result = mergeHistoryByKey(
      [{ id: 'drive-1', distance: 10 }],
      [{ id: 'drive-1', distance: 99 }, { id: 'drive-2', distance: 20 }],
      row => row.id,
    )
    expect(result.items).toEqual([
      { id: 'drive-1', distance: 10 },
      { id: 'drive-2', distance: 20 },
    ])
  })
})
