from pathlib import Path

def rep(path, old, new, n=1):
    p=Path(path); s=p.read_text()
    assert s.count(old)==n,(path,old[:90],s.count(old),n)
    p.write_text(s.replace(old,new))

p='miniprogram/src/pages/analysis/index.tsx'
rep(p,"const candidate = remoteDrives.status === 'fulfilled' && remoteDrives.value.complete\n          ? buildParkedWindows(remoteDrives.value.items, ch, remoteCharges.status === 'fulfilled' && remoteCharges.value.complete)",
    "const candidate = remoteD?.complete\n          ? buildParkedWindows(remoteD.items, ch, Boolean(remoteC?.complete && !retainedC))")
# Retain absent records, but a newly authenticated reused ID must not include a foreign collector's
# old receipt in the current projection. writeHistoryCache independently retains the raw archive.
rep(p,'      const dr = remoteD ? mergeHistoryByKey(oldDrives, remoteD.items, historyRowKey).items : oldDrives',
    '''      const currentDrives = oldDrives.filter(old => !remoteD?.items.some(row => row.id === old.id && !sameHistoryIdentity(old, row)))
      const dr = remoteD ? mergeHistoryByKey(currentDrives, remoteD.items, historyRowKey).items : oldDrives''')
rep(p,'      const ch = remoteC ? mergeHistoryByKey(oldCharges, remoteC.items, historyRowKey).items : oldCharges',
    '''      const currentCharges = oldCharges.filter(old => !remoteC?.items.some(row => row.id === old.id && !sameHistoryIdentity(old, row)))
      const ch = remoteC ? mergeHistoryByKey(currentCharges, remoteC.items, historyRowKey).items : oldCharges''')

p='miniprogram/src/components/dashboard/AppGeometryContract.test.tsx'
rep(p,"tree.root.findByType('vehicle-asset')", "tree.root.findByProps({ className: 'dashboard-vehicle-image' })")
rep(p,"({ View: 'view', Text: 'text', Button: 'button' })", "({ View: 'view', Text: 'text', Button: 'button', Image: 'image' })")

p='miniprogram/src/services/cache_budget.ts'
rep(p,'    if (before > 0 && after <= before) return true', '    if (!userWrite && before > 0 && after <= before) return true')
p='miniprogram/src/services/history_cache.ts'
rep(p,"import { canStoreCache } from './cache_budget'", "import { cacheBytes, canStoreCache, USER_DATA_RESERVE_BYTES } from './cache_budget'")
rep(p,'function boundedItems<T>(key: string, rows: readonly T[], savedAt: string): T[] {',
    'function boundedItems<T>(key: string, rows: readonly T[], savedAt: string, byteLimit = HISTORY_CACHE_MAX_BYTES): T[] {')
rep(p,'    if (bytes + additional > HISTORY_CACHE_MAX_BYTES) continue','    if (bytes + additional > byteLimit) continue')
rep(p,'    let retained = [...items]', '''    let retained = [...items]
    // New caches share the user reserve; old stored archives retain the original read/write bound.
    // This never shrinks a user's existing archive solely because this version added a reserve.
    let newCache = true''')
rep(p,"      if (stored != null && stored !== '') {", "      if (stored != null && stored !== '') {\n        newCache = false")
rep(p,'    const cache: HistoryCache<T> = { schema: HISTORY_CACHE_SCHEMA, scope: key, items: boundedItems(key, retained, savedAt), savedAt }',
    '''    const byteLimit = newCache ? HISTORY_CACHE_MAX_BYTES - USER_DATA_RESERVE_BYTES - cacheBytes(key) : HISTORY_CACHE_MAX_BYTES
    const cache: HistoryCache<T> = { schema: HISTORY_CACHE_SCHEMA, scope: key, items: boundedItems(key, retained, savedAt, byteLimit), savedAt }''')
# Update only the now-explicit confirmed-bill label, retaining every exact currency/zero/manual amount assertion.
rep('miniprogram/src/pages/insights/insights_page.test.tsx','总费用','已记录／手动费用',5)

p=Path('miniprogram/src/services/history_cache.test.ts')
p.write_text(p.read_text()+'''

it('a normal code upgrade keeps a valid legacy archive above the new-cache reserve readable and unchanged', () => {
  const store = storage(), key = historyCacheKey('drives', 'owner', 'uid', 'https://api.example.test')
  const original = { schema: HISTORY_CACHE_SCHEMA, scope: key, savedAt: '2026-10-14T00:00:00Z',
    items: [{ id: 'retained', address: 'x'.repeat(700 * 1024) }] }
  store.setStorageSync(key, original)
  expect(readHistoryCache<{ id: string; address: string }>(store, key)[0].address.length).toBe(700 * 1024)
  expect(store.values.get(key)).toBe(original)
  const before = JSON.stringify(original)
  expect(writeHistoryCache(store, key, [...original.items, { id: 'growth', address: 'new' }])).toBe(false)
  expect(JSON.stringify(store.values.get(key))).toBe(before)
})
''')
print('Fixed real cache capacity and source-instance regression; preserved all existing numerical assertions.')
