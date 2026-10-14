import { expect, it } from 'vitest'
import { readCarsCache, writeCarsCache, clearCarsCacheForAccount } from './cars_cache'
const car = { id: 7, stableId: 'a', name: 'fixture', displayName: null, model: null, trim: null, exteriorColor: null, wheelType: null, remoteVerified: true }
function fixture() {
  const disk = new Map<string, unknown>()
  return { disk, getStorageSync: (key: string) => disk.get(key),
    setStorageSync: (key: string, value: unknown) => disk.set(key, JSON.parse(JSON.stringify(value))),
    removeStorageSync: (key: string) => disk.delete(key) }
}
it('projects only bounded display data and clears only exact owner/origin', () => {
  const storage = fixture()
  expect(writeCarsCache(storage, 'owner', 'https://a.test', [{ ...car, privateToken: 'SECRET' } as typeof car])).toBe(true)
  writeCarsCache(storage, 'other', 'https://a.test', [car])
  writeCarsCache(storage, 'owner', 'https://b.test', [car])
  expect(JSON.stringify([...storage.disk.values()])).not.toContain('SECRET')
  expect(readCarsCache(storage, 'owner', 'https://a.test')[0].remoteVerified).toBe(false)
  clearCarsCacheForAccount(storage, 'owner', 'https://a.test')
  expect(readCarsCache(storage, 'owner', 'https://a.test')).toEqual([])
  expect(readCarsCache(storage, 'other', 'https://a.test')).toHaveLength(1)
  expect(readCarsCache(storage, 'owner', 'https://b.test')).toHaveLength(1)
})
it('rejects oversized/duplicate/corrupt cache and tolerates device quota errors', () => {
  const storage = fixture()
  expect(writeCarsCache(storage, 'owner', 'https://a.test', Array(101).fill(car))).toBe(false)
  expect(writeCarsCache(storage, 'owner', 'https://a.test', [car, car])).toBe(false)
  expect(writeCarsCache({ setStorageSync: () => { throw Error('quota') } }, 'owner', 'https://a.test', [car])).toBe(false)
  writeCarsCache(storage, 'owner', 'https://a.test', [car])
  const key = [...storage.disk.keys()][0]
  storage.disk.set(key, { schema: 1, scope: 'foreign', cars: [car] })
  expect(readCarsCache(storage, 'owner', 'https://a.test')).toEqual([])
})
