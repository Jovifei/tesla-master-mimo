import { expect, it } from 'vitest'
import { clearNewAccountCaches } from './account_cache_cleanup'
const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
it('deletes only known schema and exact encoded owner/UID suffixes, retaining ambiguous and future archives', () => {
  const origin = 'https://api.example.test', owner = `${encode(origin)}.${encode('a.b')}`
  const good = `matelink.events.v1.${owner}.uid.charges`, malformed = `matelink.events.v1.${owner}.uid.extra.charges`, future = `matelink.manual-trips.v1.${owner}.uid`
  const collision = `matelink.events.v1.${encode(origin)}.a.b.uid.charges`, foreign = `matelink.events.v1.${encode('https://other.test')}.${encode('a.b')}.uid.charges`
  const values = new Map<string, unknown>([[good, { schema: 1, scope: good, sources: {} }], [malformed, { schema: 1, scope: malformed, sources: {} }], [future, { schema: 9, scope: future, trips: [] }], [collision, { schema: 1, scope: collision, sources: {} }], [foreign, { schema: 1, scope: foreign, sources: {} }]])
  clearNewAccountCaches({ getStorageSync: key => values.get(key), getStorageInfoSync: () => ({ keys: [...values.keys()] }), removeStorageSync: key => { values.delete(key) } }, 'a.b', origin)
  expect(values.has(good)).toBe(false)
  expect([malformed, future, collision, foreign].every(key => values.has(key))).toBe(true)
})
it('preserves valid-looking tariff records carrying an unrecognized schema', () => {
  const origin = 'https://api.example.test', key = `matelink.tariff.v1.${encode(origin)}.a`, value = { schema: 2, currency: 'USD', peak: 0, flat: 1, valley: 0 }
  const values = new Map([[key, value]])
  clearNewAccountCaches({ getStorageSync: name => values.get(name), getStorageInfoSync: () => ({ keys: [...values.keys()] }), removeStorageSync: name => { values.delete(name) } }, 'a', origin)
  expect(values.get(key)).toBe(value)
})
