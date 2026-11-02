from pathlib import Path
import json
r=Path.cwd()
def put(p,s):
 q=r/p; q.parent.mkdir(parents=True,exist_ok=True); q.write_text(s)
def rep(p,a,b,n=1):
 s=(r/p).read_text(); assert s.count(a)==n,(p,a[:80],s.count(a)); put(p,s.replace(a,b))
rep('miniprogram/src/domain/history_query.test.ts',"    expect(historyDetailDateTime('2026-10-27T18:54:00+08:00')).toBe('10-27 18:54')", """    const local = new Date(2026, 9, 27, 18, 54).toISOString()
    expect(historyDetailDateTime(local)).toBe('10-27 18:54')
    expect(historyDetailDateTime('2026-10-27T18:54:00+08:00'))
      .toBe(historyDetailDateTime('2026-10-27T10:54:00Z'))""")
put('miniprogram/src/domain/app_display_contract.ts', '''/** Logical App geometry, independent of viewport width and physical pixel density. */
export const APP_DISPLAY_CONTRACT = Object.freeze({ version: 1,
  appBaseline: '17404cfd0aedb9019384a9c9f1579421832e760b', vehicleHeroHeight: 170, vehicleImageHeight: 160 })
''')
p='miniprogram/src/components/dashboard/DashboardParts.tsx'
put(p,"import { APP_DISPLAY_CONTRACT } from '../../domain/app_display_contract'\n"+(r/p).read_text())
rep(p,'return <View className="dashboard-vehicle-art">','return <View className="dashboard-vehicle-art" style={{ height: `${APP_DISPLAY_CONTRACT.vehicleHeroHeight}px` }}>')
rep(p,"className: 'dashboard-vehicle-image', 'aria-label':", "className: 'dashboard-vehicle-image', style: `height:${APP_DISPLAY_CONTRACT.vehicleImageHeight}px`, 'aria-label':")
rep('miniprogram/src/pages/vehicle/index.scss','  .dashboard-hero .dashboard-vehicle-art { height:470rpx }','  // Hero height is the App logical-pixel contract, supplied at runtime.')
rep('miniprogram/src/pages/vehicle/index.scss','  .dashboard-hero .dashboard-vehicle-image { height:450rpx }','  // Native asset height is the App logical-pixel contract, supplied at runtime.')
put('miniprogram/src/services/cache_budget.ts','''import type { WechatStorage } from './session'

// Total allowance is unchanged; new replaceable caches reserve capacity for user data.
export const CACHE_BUDGET_BYTES = 800 * 1024
export const USER_DATA_RESERVE_BYTES = 128 * 1024
type BudgetStorage = Partial<Pick<WechatStorage, 'getStorageSync' | 'getStorageInfoSync'>>
export type LocalStorageUsage = { totalBytes: number; userBytes: number; cacheBytes: number; budgetBytes: number; reservedUserBytes: number }
export function isProtectedLocalKey(key: string): boolean {
  return ['matelink.charge-cost.', 'matelink.manual-trips.', 'matelink.tariff.',
    'matelink.tpms-profile.', 'matelink.vehicle-appearance.', 'matelink.wechat.',
    'matelink.selected.car.', 'matelink.legal.'].some(prefix => key.startsWith(prefix))
}
export function cacheBytes(value: unknown): number {
  const text = JSON.stringify(value)
  if (text === undefined) return 0
  let size = 0
  for (let index = 0; index < text.length; index++) {
    const code = text.charCodeAt(index)
    if (code < 128) size++
    else if (code < 2048) size += 2
    else if (code >= 0xd800 && code <= 0xdbff && text.charCodeAt(index + 1) >= 0xdc00 && text.charCodeAt(index + 1) <= 0xdfff) { size += 4; index++ }
    else size += 3
  }
  return size
}
/** Counts only: no account keys, records, locations or credentials leave this projection. */
export function localStorageUsage(storage: BudgetStorage): LocalStorageUsage | null {
  if (!storage.getStorageInfoSync || !storage.getStorageSync) return null
  try {
    const keys = storage.getStorageInfoSync().keys
    if (!Array.isArray(keys)) return null
    let userBytes = 0, caches = 0
    for (const key of new Set(keys)) {
      if (typeof key !== 'string' || !key.startsWith('matelink.')) continue
      const size = cacheBytes(key) + cacheBytes(storage.getStorageSync(key))
      if (isProtectedLocalKey(key)) userBytes += size
      else caches += size
    }
    return { totalBytes: userBytes + caches, userBytes, cacheBytes: caches,
      budgetBytes: CACHE_BUDGET_BYTES, reservedUserBytes: USER_DATA_RESERVE_BYTES }
  } catch { return null }
}
export function canStoreCache(storage: BudgetStorage, key: string, value: unknown): boolean {
  if (!storage.getStorageInfoSync || !storage.getStorageSync || !key.startsWith('matelink.')) return false
  try {
    const usage = localStorageUsage(storage)
    if (!usage) return false
    const previous = storage.getStorageSync(key)
    const before = previous == null || previous === '' ? 0 : cacheBytes(previous) + cacheBytes(key)
    const after = cacheBytes(value) + cacheBytes(key)
    const total = usage.totalBytes - before + after
    const userWrite = isProtectedLocalKey(key)
    const users = usage.userBytes + (userWrite ? after - before : 0)
    const caches = total - users
    // Existing full archives are retained. Equal-sized corrections/shrinks never evict another key.
    if (before > 0 && after <= before) return true
    return total <= CACHE_BUDGET_BYTES &&
      (userWrite || caches <= CACHE_BUDGET_BYTES - Math.max(USER_DATA_RESERVE_BYTES, users))
  } catch { return false }
}
''')
p='miniprogram/src/pages/tools/index.tsx'
rep(p,"import { canStoreCache } from '../../services/cache_budget'", "import { canStoreCache, localStorageUsage } from '../../services/cache_budget'")
rep(p,'  const session = readAppSession(Taro)\n  const costScope',"  const session = readAppSession(Taro)\n  const storageUsage = kind === 'settings' ? localStorageUsage(Taro) : null\n  const costScope")
rep(p,"    {kind === 'settings' ? <>\n", '''    {kind === 'settings' ? <>
      <View className="card local-data-status">
        <SectionHeading icon="shield" title="本机数据与升级" />
        <Text className="muted">正常升级保留有效登录、费用、手动旅程和设置；不清空历史，也不要求重新授权。退出登录与删除账号是不同操作。</Text>
        {storageUsage ? <Text className="muted">本机已用 {Math.ceil(storageUsage.totalBytes / 1024)} / {storageUsage.budgetBytes / 1024} KiB；用户资料 {Math.ceil(storageUsage.userBytes / 1024)} KiB；新缓存为用户资料预留 {storageUsage.reservedUserBytes / 1024} KiB。</Text>
          : <Text className="muted">暂时无法读取存储状态；已有资料不会因此删除。</Text>}
        <Text className="muted">空间不足时停止新增缓存并提示，不自动删除手填费用或手动旅程。卸载微信、主动清理存储与授权撤销不属于正常升级；摘要导出不是完整恢复备份。</Text>
      </View>
''')
rep('miniprogram/src/pages/insights/index.tsx','最多20条、64KiB且受共享缓存预算限制；已有记录已保留','最多20条、64KiB且受本机容量限制；已有记录和当前编辑已保留')
p='miniprogram/src/domain/drive_energy.ts'
s=(r/p).read_text();at=s.index('export function resolveChargeEnergy(')
put(p,s[:at]+'''/** Same App partial-counter proof; this is deliberately not a whole-session energy value. */
export function partialChargeBatteryInput(raw: Pick<RawCharge, 'source' | 'start_date' | 'end_date' | 'energy_contract'>): number | null {
  if (raw.source !== 'telemetry_mqtt' || raw.energy_contract == null) return null
  const proof = contract(raw.energy_contract), m = proof.battery_input
  if (!validVersion(proof) || !m || (m.unit === undefined ? 'kWh' : m.unit) !== 'kWh' ||
    m.method !== 'session_counter_delta' || m.measurement_point !== 'battery_input' ||
    m.source !== raw.source || m.source_field !== 'DCChargingEnergyIn' || m.quality !== 'unknown' ||
    m.reason !== 'partial_counter_window' || m.value_kwh != null || m.coverage_kind !== 'endpoints' ||
    m.time_basis !== 'collector_received_at') return null
  if (![raw.start_date, raw.end_date, m.start_date, m.end_date, m.observed_start_at, m.observed_end_at]
    .every(value => typeof value === 'string' && /(?:Z|[+-]\\d{2}:\\d{2})$/i.test(value))) return null
  const start = instant(raw.start_date), end = instant(raw.end_date)
  const ms = instant(m.start_date), me = instant(m.end_date)
  const first = instant(m.observed_start_at), last = instant(m.observed_end_at)
  if (!start || !end || !ms || !me || !first || !last || compare(end, start) <= 0) return null
  const sameMicros = (a: Instant, b: Instant) => a.seconds === b.seconds && Math.floor(a.nanos / 1000) === Math.floor(b.nanos / 1000)
  if (!sameMicros(start, ms) || !sameMicros(end, me) || compare(first, start) < 0 ||
    compare(last, end) > 0 || compare(last, first) <= 0) return null
  const ratio = elapsed(first, last) / elapsed(start, end)
  const declared = m.coverage_ratio == null ? ratio : number(m.coverage_ratio)
  if (declared == null || declared <= 0 || declared >= 1 || !Number.isFinite(ratio) || Math.abs(ratio - declared) > 1e-6) return null
  const amount = number(m.covered_energy_kwh)
  return amount != null && amount >= 0 ? amount : null
}

'''+s[at:])
p='miniprogram/src/services/charge_cost.ts'
rep(p,"import { resolveChargeEnergy, sameEnergyInstant }", "import { partialChargeBatteryInput, resolveChargeEnergy, sameEnergyInstant }")
rep(p,"  estimatedUnitPrice: number | null; estimatePriceSource: ChargeEstimatePrice['source'] | null }", """  estimatedUnitPrice: number | null; estimatePriceSource: ChargeEstimatePrice['source'] | null;
  /** Never part of a whole bill or whole-energy denominator. */
  partialEstimatedCost?: number | null }""")
rep(p,"export function estimateAppDefaultChargeCost(charge: Charge, price = APP_DEFAULT_CHARGE_PRICE_CNY, currency = 'CNY'): number | null {", '''export function qualifiedPartialChargeInput(charge: Charge): number | null {
  if (charge.qualityState === 'quarantined' || !charge.energy?.evidence) return null
  return partialChargeBatteryInput({ source: charge.source, start_date: charge.startDate,
    end_date: charge.endDate, energy_contract: charge.energy.evidence })
}
export function estimateAppDefaultChargeCost(charge: Charge, price = APP_DEFAULT_CHARGE_PRICE_CNY, currency = 'CNY'): number | null {''')
rep(p,"  return finite(battery) && battery >= 0 ? battery * price : null", '''  const value = battery ?? qualifiedPartialChargeInput(charge)
  const amount = finite(value) && value >= 0 ? value * price : null
  return amount != null && Number.isFinite(amount) ? amount : null''')
rep(p,"    return { ...charge, reportedCost, reportedCurrency, manualCostUpdatedAt: manual?.updatedAt ?? null,\n      cost: manual ? manual.amount : actualCost ?? estimate,", '''    const partial = estimate != null && charge.energy?.batteryInputKwh == null && qualifiedPartialChargeInput(charge) != null
    return { ...charge, reportedCost, reportedCurrency, manualCostUpdatedAt: manual?.updatedAt ?? null,
      partialEstimatedCost: partial ? estimate : null,
      cost: manual ? manual.amount : actualCost ?? (partial ? null : estimate),''')
p='miniprogram/src/pages/charges/index.tsx'
s=(r/p).read_text()
# The existing label and description consumers remain; only add the separate partial estimate value.
rep(p,'  if (charge.cost == null) return \'--\'','  if (charge.partialEstimatedCost != null) return `≈${charge.currencyCode === \'CNY\' ? \'¥\' : charge.currencyCode ?? \'\'}${charge.partialEstimatedCost.toFixed(2)}`\n  if (charge.cost == null) return \'--\'')
rep(p,"  if (charge.costProvenance === 'manual') return '手动费用'", "  if (charge.partialEstimatedCost != null) return '仅已采集部分，不是整次账单'\n  if (charge.costProvenance === 'manual') return '手动费用'")
p='miniprogram/src/pages/insights/index.tsx'
rep(p,"  const displayedCosts = costScope ? overlayChargeCosts(Taro, costScope, filteredCharges.filter(isAppChargeAnalysisEligible), chargeEstimatePrice) : []\n  const costs = chargeCosts(displayedCosts)", '''  const costRows = costScope ? overlayChargeCosts(Taro, costScope, filteredCharges, chargeEstimatePrice) : []
  const displayedCosts = costRows.filter(row => isAppChargeAnalysisEligible(row) && row.costProvenance !== 'estimate')
  const costs = chargeCosts(displayedCosts)
  const fullEstimates = chargeCosts(costRows.filter(row => isAppChargeAnalysisEligible(row) && row.costProvenance === 'estimate' && row.partialEstimatedCost == null))
  const partialEstimates = chargeCosts(costRows.filter(row => row.partialEstimatedCost != null)
    .map(row => ({ ...row, cost: row.partialEstimatedCost! })))''')
rep(p,'{!costs.groups.length ? <View className="card"><Text className="section-title">暂无可合计的费用','{!costs.groups.length && !fullEstimates.groups.length && !partialEstimates.groups.length ? <View className="card"><Text className="section-title">暂无可合计的费用')
rep(p,'<MetricValue label="总费用" value={costAmount(group.cost, estimateIn(group.currency))}','<MetricValue label="已记录／手动费用" value={costAmount(group.cost, estimateIn(group.currency))}')
rep(p,"    {car && kind === 'cost' ? <>\n", '''    {car && kind === 'cost' ? <>
      {fullEstimates.groups.map(group => <View className="card" key={`full-estimate-${group.currency}`}>
        <SectionHeading icon="money" title="完整记录估算" />
        <MetricValue label="完整记录估算" value={group.cost == null ? null : `≈${displayNumber(group.cost, 2)}`} unit={group.currency} />
        <Text className="muted">{group.known} 次无账单充电的电价估算；不计入已确认账单或账单均价。</Text>
      </View>)}
      {partialEstimates.groups.map(group => <View className="card" key={`partial-estimate-${group.currency}`}>
        <SectionHeading icon="money" title="已采集部分估算" />
        <MetricValue label="已采集部分估算" value={group.cost == null ? null : `≈${displayNumber(group.cost, 2)}`} unit={group.currency} />
        <Text className="muted">{group.known} 次充电的已采集部分；不是整次账单，不计入完整电量或账单均价。</Text>
      </View>)}
''')
p='miniprogram/src/pages/analysis/index.tsx'
rep(p,'  const params = useRouter().params, kind = analysisKind(params.kind)', '''  const params = useRouter().params, kind = analysisKind(params.kind)
  const needsDrives = kind !== 'chargeCurves'
  const needsCharges = !['efficiency', 'curves'].includes(kind)''')
rep(p,"      const driveRows = readHistoryCache<Drive>(Taro, historyCacheKey('drives', session.userId!, vehicle.stableId, origin))", "      const driveRows = needsDrives ? readHistoryCache<Drive>(Taro, historyCacheKey('drives', session.userId!, vehicle.stableId, origin)) : []")
rep(p,"      const chargeRows = readHistoryCache<Charge>(Taro, historyCacheKey('charges', session.userId!, vehicle.stableId, origin))", "      const chargeRows = needsCharges ? readHistoryCache<Charge>(Taro, historyCacheKey('charges', session.userId!, vehicle.stableId, origin)) : []")
rep(p,"      setStatus({ ...initialStatus, offline: true, cached: true, reason: 'cached' })", "      setStatus({ ...initialStatus, offline: true, cached: true, reason: 'cached' })\n      if (driveRows.length || chargeRows.length) setLoading(false)")
rep(p,"      const results = await Promise.allSettled([\n        analysisApi.getHistory(selected, 'drives', { isCurrent: active }),\n        analysisApi.getHistory(selected, 'charges', { isCurrent: active }),\n      ])", """      restore(selected)
      const results = await Promise.allSettled([
        needsDrives ? analysisApi.getHistory(selected, 'drives', { isCurrent: active }) : Promise.resolve(null),
        needsCharges ? analysisApi.getHistory(selected, 'charges', { isCurrent: active }) : Promise.resolve(null),
      ])""")
rep(p,"      const oldDrives = readHistoryCache<Drive>(Taro, dkey), oldCharges = readHistoryCache<Charge>(Taro, ckey)\n      const dr = remoteDrives.status === 'fulfilled' ? remoteDrives.value.items.map(row => mergeHistoryByKey(oldDrives.filter(old => sameHistoryIdentity(old, row)), [row], historyRowKey).items[0]) : oldDrives\n      const ch = remoteCharges.status === 'fulfilled' ? remoteCharges.value.items.map(row => mergeHistoryByKey(oldCharges.filter(old => sameHistoryIdentity(old, row)), [row], historyRowKey).items[0]) : oldCharges", """      const oldDrives = needsDrives ? readHistoryCache<Drive>(Taro, dkey) : []
      const oldCharges = needsCharges ? readHistoryCache<Charge>(Taro, ckey) : []
      const remoteD = remoteDrives.status === 'fulfilled' ? remoteDrives.value : null
      const remoteC = remoteCharges.status === 'fulfilled' ? remoteCharges.value : null
      const dr = remoteD ? mergeHistoryByKey(oldDrives, remoteD.items, historyRowKey).items : oldDrives
      const ch = remoteC ? mergeHistoryByKey(oldCharges, remoteC.items, historyRowKey).items : oldCharges
      const retainedD = oldDrives.some(old => !remoteD?.items.some(row => sameHistoryIdentity(old, row)))
      const retainedC = oldCharges.some(old => !remoteC?.items.some(row => sameHistoryIdentity(old, row)))""")
rep(p,"      setParkingDrives(remoteDrives.status === 'fulfilled' && remoteDrives.value.complete ? remoteDrives.value.items : [])", "      setParkingDrives(remoteD?.complete ? remoteD.items : [])")
rep(p,"      if (remoteDrives.status === 'fulfilled') saved = writeHistoryCache(Taro, dkey, mergeHistoryByKey(oldDrives, dr, historyRowKey).items) && saved\n      if (remoteCharges.status === 'fulfilled') saved = writeHistoryCache(Taro, ckey, mergeHistoryByKey(oldCharges, ch, historyRowKey).items) && saved\n      setStatus({ drivesComplete: remoteDrives.status === 'fulfilled' && remoteDrives.value.complete,\n        chargesComplete: remoteCharges.status === 'fulfilled' && remoteCharges.value.complete,\n        offline: false, cached: remoteDrives.status === 'rejected' || remoteCharges.status === 'rejected',\n        reason: remoteDrives.status === 'fulfilled' ? remoteDrives.value.reason ?? (remoteCharges.status === 'fulfilled' ? remoteCharges.value.reason : 'request_failed') : 'request_failed' })", """      if (remoteD) saved = writeHistoryCache(Taro, dkey, dr) && saved
      if (remoteC) saved = writeHistoryCache(Taro, ckey, ch) && saved
      setStatus({ drivesComplete: !needsDrives || Boolean(remoteD?.complete && !retainedD),
        chargesComplete: !needsCharges || Boolean(remoteC?.complete && !retainedC),
        offline: false, cached: retainedD || retainedC || remoteDrives.status === 'rejected' || remoteCharges.status === 'rejected',
        reason: remoteDrives.status === 'rejected' || remoteCharges.status === 'rejected' ? 'request_failed'
          : retainedD || retainedC ? 'cached' : remoteD?.reason ?? remoteC?.reason ?? null })""")
rep('miniprogram/package.json','"version": "0.6.3"','"version": "0.6.4"')
p='miniprogram/package-lock.json'; d=json.loads((r/p).read_text()); assert d['version']=='0.6.3' and d['packages']['']['version']=='0.6.3'; d['version']='0.6.4'; d['packages']['']['version']='0.6.4'; put(p,json.dumps(d,indent=2,ensure_ascii=False)+'\n')
rep('miniprogram/src/pages/tools/index.tsx','0.6.3 将充电历史日期筛选对齐 App（近7天/近30天/全部时间/今天），并沿用 0.6.2 的子页面顶栏与 Android 式分组；既有数据计算与授权流程保持不变。','0.6.4 将账单、完整估算与已采集部分估算分开展示，按 App 逻辑尺寸显示车图，并为本机用户资料预留容量；正常升级保留会话与已有资料。')
print('Scoped Mini consumers prepared. No backend, credentials, storage namespace or existing archive is replaced.')
