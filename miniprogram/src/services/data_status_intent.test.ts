import { expect, it } from 'vitest'
import { consumeDataStatus, requestDataStatus } from './data_status_intent'

it('opens diagnostics once for the same account and API origin', () => {
  const values = new Map<string, unknown>()
  const storage = {
    getStorageSync: (key: string) => values.get(key),
    setStorageSync: (key: string, value: unknown) => values.set(key, value),
    removeStorageSync: (key: string) => values.delete(key),
  }
  requestDataStatus(storage, 'owner-a', 'https://api.example.test', 1000)
  expect(consumeDataStatus(storage, 'owner-a', 'https://api.example.test', 1500)).toBe(true)
  expect(consumeDataStatus(storage, 'owner-a', 'https://api.example.test', 1500)).toBe(false)
  requestDataStatus(storage, 'owner-a', 'https://api.example.test', 1000)
  expect(consumeDataStatus(storage, 'owner-b', 'https://api.example.test', 1500)).toBe(false)
  requestDataStatus(storage, 'owner-a', 'https://api.example.test', 1000)
  expect(consumeDataStatus(storage, 'owner-a', 'https://other.example.test', 1500)).toBe(false)
  requestDataStatus(storage, 'owner-a', 'https://api.example.test', 1000)
  expect(consumeDataStatus(storage, 'owner-a', 'https://api.example.test', 31_001)).toBe(false)
})
