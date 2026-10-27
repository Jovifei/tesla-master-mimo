import { Button, Input, Map, Picker, Slider, Switch, Text, View } from '@tarojs/components'
const APP_MINIPROGRAM_VERSION: string = process.env.TARO_APP_MINIPROGRAM_VERSION ?? '未确认'
import Taro, { useDidHide, useDidShow, useRouter, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import MetricValue from '../../components/MetricValue'
import TpmsPanel from '../../components/TpmsPanel'
import { ApiError, getApiOrigin, getApiSessionGeneration, matelinkApi } from '../../services/api'
import { analysisApi } from '../../services/analysis_api'
import type { SoftwareUpdate } from '../../services/analysis_types'
import { readAppSession } from '../../services/session'
import { carSelectionStorageKey, type Car, type CarStatus, type Charge, type Drive } from '../../services/types'
import { readTpmsCache } from '../../services/tpms_cache'
import { canStoreCache } from '../../services/cache_budget'
import { annualReport, historyCsv, historyJson, reportPdf } from '../../domain/reports'
import { historyDateTime } from '../../domain/history_query'
import { mapCoordinate } from '../../domain/map_coordinate'
import { DEFAULT_TARIFF_PERIODS, parseTariffPeriods, validTariffPeriods, readTariff, tariffKey } from '../../domain/tariff'
import { overlayChargeCosts } from '../../services/charge_cost'
import { emptyHistoryQuery, filterHistory, normalizeHistoryQuery } from '../../domain/history_query'
import { isHistoryQuarantined } from '../../domain/history'
import { buildParkedWindows } from '../../domain/analysis'
import TpmsSettingsPanel from '../../components/TpmsSettingsPanel'
import './index.scss'

const titles: Record<string, string> = { settings: '设置', tariff: '分时电价', location: '车辆位置', temperature: '温度趋势', tpms: '胎压历史', tpmsSettings: '胎压提醒设置',
  sentry: '哨兵历史', updates: '软件更新', export: '数据导出', report: '年度报告', countries: '到访国家', regions: '到访地区', whereWasI: '当时在哪里', about: '关于 MateLink', vehiclePreview: '车辆预览' }
const blankTariff = () => ({ currency: '', peak: '', flat: '', valley: '', enabled: true, defaultPrice: '', peakRanges: DEFAULT_TARIFF_PERIODS.peak, flatRanges: DEFAULT_TARIFF_PERIODS.flat, valleyRanges: DEFAULT_TARIFF_PERIODS.valley })
const noFetch = new Set(['settings', 'tariff', 'about', 'vehiclePreview'])
function previewTouches(event: unknown): Array<{ clientX: number; clientY: number }> {
  const value = event as { touches?: Array<{ clientX: number; clientY: number }>; detail?: { touches?: Array<{ clientX: number; clientY: number }> } }
  return (value.touches || value.detail?.touches || []).slice(0, 2).filter(touch => Number.isFinite(touch.clientX) && Number.isFinite(touch.clientY))
}

export default function ToolsPage() {
  const params = useRouter().params
  const requested = params.kind || 'settings'
  const kind = Object.prototype.hasOwnProperty.call(titles, requested) ? requested : 'settings'
  const [car, setCar] = useState<Car | null>(null), [status, setStatus] = useState<CarStatus | null>(null)
  const [drives, setDrives] = useState<Drive[]>([]), [charges, setCharges] = useState<Charge[]>([])
  const [updates, setUpdates] = useState<SoftwareUpdate[]>([]), [page, setPage] = useState(1)
  const [complete, setComplete] = useState(false), [loading, setLoading] = useState(false), [error, setError] = useState<string | null>(null)
  const [year, setYear] = useState(new Date().getFullYear()), [angle, setAngle] = useState(0)
  const [zoom, setZoom] = useState(1), [pan, setPan] = useState({ x: 0, y: 0 })
  const [previewOpen, setPreviewOpen] = useState({ doors: false, frunk: false, port: false })
  const gesture = useRef({ x: 0, y: 0, distance: 0, zoom: 1, pan: { x: 0, y: 0 } })
  const [tariff, setTariff] = useState(blankTariff)
  const [exportQuery, setExportQuery] = useState(emptyHistoryQuery())
  const [exportFormat, setExportFormat] = useState<'csv' | 'json'>('csv'), [exportType, setExportType] = useState<'both' | 'drives' | 'charges'>('both')
  const target = params.timestamp && Number.isFinite(Date.parse(params.timestamp)) ? historyDateTime(params.timestamp) : historyDateTime(new Date().toISOString())
  const [targetDate, setTargetDate] = useState(target.slice(0, 10)), [targetTime, setTargetTime] = useState(target.slice(11, 16))
  const [historicPosition, setHistoricPosition] = useState<{ latitude: number; longitude: number; at: string | null; source: string | null; label: string } | null>(null)
  const [historicReason, setHistoricReason] = useState<string | null>(null)
  const epoch = useRef(0), owner = useRef<string | null>(null)
  const invalidate = () => { epoch.current++; setLoading(false) }
  const scopeKey = () => { const session = readAppSession(Taro); return session?.userId ? JSON.stringify([getApiOrigin(), session.userId, getApiSessionGeneration()]) : null }
  const load = async (nextPage = 1) => {
    const operation = ++epoch.current
    let loadScope: string | null | undefined
    const isCurrentOwner = () => { if (epoch.current !== operation) return false; try { return loadScope === undefined || scopeKey() === loadScope } catch { return false } }
    setError(null); setLoading(true)
    if (nextPage === 1) { setStatus(null); setDrives([]); setCharges([]); setUpdates([]); setCar(null); setComplete(false); setHistoricPosition(null); setHistoricReason(null) }
    try {
      const scope = scopeKey(); loadScope = scope; owner.current = scope
      if (!scope && !noFetch.has(kind)) throw new Error('请先在更多中完成特斯拉账号登录')
      if (noFetch.has(kind)) {
        if (kind === 'tariff') {
          const session = readAppSession(Taro), saved = session?.userId ? readTariff(Taro, session.userId, getApiOrigin()) : null
          setTariff(saved ? { currency: saved.currency, peak: String(saved.peak), flat: String(saved.flat), valley: String(saved.valley), enabled: saved.enabled !== false, defaultPrice: saved.defaultPrice == null ? '' : String(saved.defaultPrice), peakRanges: saved.peakHours?.map(range => range.join('-')).join(',') ?? DEFAULT_TARIFF_PERIODS.peak, flatRanges: saved.flatHours?.map(range => range.join('-')).join(',') ?? DEFAULT_TARIFF_PERIODS.flat, valleyRanges: saved.valleyHours?.map(range => range.join('-')).join(',') ?? DEFAULT_TARIFF_PERIODS.valley } : blankTariff())
        }
        setLoading(false); return
      }
      const valid = () => { try { return epoch.current === operation && scopeKey() === scope } catch { return false } }
      const cars = await matelinkApi.getCars()
      if (!valid()) return
      const session = readAppSession(Taro)!, preferred = Taro.getStorageSync(carSelectionStorageKey(session.userId))
      const selected = cars.find(item => item.stableId === preferred) || cars[0]
      if (!selected) throw new Error('当前账号暂无车辆')
      setCar(selected)
      if (kind === 'updates') {
        const result = await analysisApi.getUpdates(selected, nextPage, { isCurrent: valid })
        if (!valid()) return
        setUpdates(result.items); setPage(nextPage)
      } else if (['location', 'temperature', 'tpms', 'tpmsSettings', 'sentry'].includes(kind)) {
        const result = await matelinkApi.getCarStatus(selected.id, selected)
        if (!valid()) return
        setStatus(result)
      } else {
        const [tripResult, chargeResult] = await Promise.all([analysisApi.getHistory(selected, 'drives', { isCurrent: valid }), analysisApi.getHistory(selected, 'charges', { isCurrent: valid })])
        if (!valid()) return
        setDrives(tripResult.items); setCharges(chargeResult.items); setComplete(tripResult.complete && chargeResult.complete)
      }
    } catch (reason) {
      if (isCurrentOwner()) setError(reason instanceof ApiError ? reason.message : reason instanceof Error && ['请先在更多中完成特斯拉账号登录', '当前账号暂无车辆'].includes(reason.message) ? reason.message : '当前数据读取失败，请稍后重试')
    } finally { if (isCurrentOwner()) setLoading(false) }
  }
  useDidShow(() => { void load() }); useDidHide(invalidate); useUnload(invalidate)
  const session = readAppSession(Taro)
  const displayCharges = car && session?.userId ? overlayChargeCosts(Taro, { userId: session.userId, origin: getApiOrigin(), stableId: car.stableId }, charges) : charges
  const report = annualReport(drives, displayCharges, year)
  const exportedDrives = exportType === 'charges' ? [] : filterHistory(drives, exportQuery), exportedCharges = exportType === 'drives' ? [] : filterHistory(displayCharges, exportQuery)
  const reportLines = [`MateLink ${year} 年度报告`, `时区：本机显示时区；${complete ? '当前响应已翻页完成' : '部分历史：最多500条/25页'}`,
    `行程 ${report.trips.length} 次；充电 ${report.sessions.length} 次`, `已知距离 ${report.drive.distance.value ?? '未知'} km（${report.drive.distance.known}/${report.drive.distance.total}）`,
    `已知净能量 ${report.drive.energy.value ?? '未知'} kWh（${report.drive.energy.known}/${report.drive.energy.total}）`, `配对平均能耗 ${report.drive.efficiency.value ?? '未知'} kWh/100km（${report.drive.efficiency.known}/${report.drive.efficiency.total}）`,
    ...report.costs.map(cost => `已知费用 ${cost.value.toFixed(2)} ${cost.currency}（${cost.count}条）`), `本机手填费用 ${report.sessions.filter(row => (row as {costProvenance?:string}).costProvenance === 'manual').length} 条；不改服务端账单`]
  const exportFile = async (pdf = false) => {
    try {
      if (loading || error || !car || owner.current !== scopeKey()) return
      const filePath = `${Taro.env.USER_DATA_PATH}/matelink-${pdf ? 'report' : 'history'}-${Date.now()}.${pdf ? 'pdf' : exportFormat}`
      const fs = Taro.getFileSystemManager()
      if (pdf) fs.writeFileSync(filePath, reportPdf(reportLines))
      else fs.writeFileSync(filePath, exportFormat === 'csv' ? historyCsv(exportedDrives, exportedCharges) : historyJson(exportedDrives, exportedCharges, complete), 'utf8')
      if (pdf) await Taro.openDocument({ filePath, fileType: 'pdf', showMenu: true })
      else await Taro.shareFileMessage({ filePath, fileName: `MateLink-history.${exportFormat}` })
    } catch { setError('文件未能打开或分享，请在微信中重试；原历史数据已保留') }
  }
  const saveTariff = () => {
    const session = readAppSession(Taro)
    if (!session?.userId) { setError('请先登录后保存当前账号设置'); return }
    if (owner.current !== scopeKey()) { setError('账号已切换，请重新打开设置后填写'); return }
    const numbers = [tariff.peak, tariff.flat, tariff.valley].map(value => value.trim() ? Number(value) : NaN)
    if (!/^[A-Z]{3}$/.test(tariff.currency) || numbers.some(value => !Number.isFinite(value) || value < 0 || value > 100)) { setError('请输入币种代码及有效的峰、平、谷价格，真实0允许保存'); return }
    const key = tariffKey(session.userId, getApiOrigin())
    const periods = [tariff.peakRanges, tariff.flatRanges, tariff.valleyRanges].map(parseTariffPeriods)
    if (periods.some(value => value === null) || !validTariffPeriods(periods[0]!, periods[1]!, periods[2]!)) { setError('时段需覆盖24小时且互不重叠，例如10-15,18-21；跨午夜请分成两段'); return }
    const defaultPrice = tariff.defaultPrice.trim() ? Number(tariff.defaultPrice) : undefined
    if ((!tariff.enabled && defaultPrice === undefined) || (defaultPrice !== undefined && (!Number.isFinite(defaultPrice) || defaultPrice < 0 || defaultPrice > 100))) { setError('请输入有效默认电价，真实0允许保存'); return }
    let timeZone: string
    try { timeZone = new Intl.DateTimeFormat().resolvedOptions().timeZone; if (!timeZone) throw Error() } catch { setError('无法确认电价时区，本次设置未保存'); return }
    const value = { ...tariff, peak: numbers[0], flat: numbers[1], valley: numbers[2], timeZone, ...(defaultPrice === undefined ? {} : { defaultPrice }), peakHours: periods[0]!, flatHours: periods[1]!, valleyHours: periods[2]!, source: 'user_configured', estimateOnly: true }
    try { const existing = Taro.getStorageSync(key); if (existing != null && existing !== '' && !readTariff(Taro, session.userId, getApiOrigin())) throw Error('unknown_archive'); if (!canStoreCache(Taro, key, value)) throw Error(); Taro.setStorageSync(key, value); setError(null); Taro.showToast({ title: '本机电价设置已保存', icon: 'success' }) }
    catch { setError('本机设置未保存，已有数据保留') }
  }
  const findHistoricPosition = async () => {
    if (!car || loading || owner.current !== scopeKey()) return
    const at = Date.parse(`${targetDate}T${targetTime}:00`)
    if (!Number.isFinite(at)) { setError('请选择有效的日期和时刻'); return }
    const scope = scopeKey(), operation = ++epoch.current
    const valid = () => { try { return epoch.current === operation && scopeKey() === scope } catch { return false } }
    setLoading(true); setHistoricPosition(null); setHistoricReason(null); setError(null)
    try {
      const within = (row: Drive | Charge) => !isHistoryQuarantined(row) && Date.parse(row.startDate || '') <= at && Date.parse(row.endDate || '') >= at
      const drive = drives.find(within), charge = displayCharges.find(within)
      if (drive) {
        const evidence = await analysisApi.getDriveEvidence(car, drive.id, { isCurrent: valid })
        if (!valid()) return
        const nearest = evidence.points.filter(point => mapCoordinate(point.latitude, point.longitude)).sort((a, b) => Math.abs(Date.parse(a.date) - at) - Math.abs(Date.parse(b.date) - at))[0]
        if (nearest) setHistoricPosition({ latitude: nearest.latitude!, longitude: nearest.longitude!, at: nearest.date, source: evidence.source, label: '最近已有的行程位置采样（非插值）' })
        else setHistoricReason('该行程未提供合格的位置采样，不能重建请求时刻的位置。')
      } else if (charge) {
        const evidence = await analysisApi.getChargeEvidence(car, charge.id, { isCurrent: valid })
        if (!valid()) return
        const point = evidence.charge
        if (point && mapCoordinate(point.latitude, point.longitude)) setHistoricPosition({ latitude: point.latitude!, longitude: point.longitude!, at: null, source: point.source, label: '充电记录的地点（未提供逐点位置时间）' })
        else setHistoricReason('该充电记录没有有效的地点坐标。')
      } else {
        const parked = buildParkedWindows(drives, charges, complete).find(window => Date.parse(window.startDate) <= at && Date.parse(window.endDate) >= at)
        if (parked) {
          const detail = await matelinkApi.getDriveDetail(car.id, parked.olderId, car)
          if (!valid()) return
          if (detail && mapCoordinate(detail.endLatitude, detail.endLongitude)) setHistoricPosition({ latitude: detail.endLatitude!, longitude: detail.endLongitude!, at: detail.endDate, source: detail.source, label: '相邻行程间可用的起点位置报告（非请求时刻独立位置）' })
          else setHistoricReason('相邻行程之间缺少合格位置，不能推断驻车时刻位置。')
        } else setHistoricReason('当前已读历史没有覆盖该时刻的合格记录；不代表当时没有行程。')
      }
    } catch (reason) { if (valid()) setError(reason instanceof ApiError ? reason.message : '历史位置暂时无法读取') }
    finally { if (valid()) setLoading(false) }
  }
  const coordinate = mapCoordinate(status?.location?.latitude, status?.location?.longitude)
  const historicCoordinate = mapCoordinate(historicPosition?.latitude, historicPosition?.longitude)
  const profileEpoch = epoch.current
  return <View className="page tools-page">
    <View className="app-toolbar"><Text className="title">{titles[kind]}</Text><Button className="toolbar-button" onClick={() => Taro.navigateBack()}>返回</Button></View>
    {!noFetch.has(kind) ? <Button className="button button-secondary" loading={loading} onClick={() => { void load() }}>刷新数据</Button> : null}
    {error ? <Text className="error">{error}</Text> : null}
    {kind === 'settings' ? <View className="card">
      <Text className="section-title">账号与连接</Text><Text className="muted">{session ? '当前微信会话可用' : '尚未登录'} · Tesla 关联与车辆权限独立确认</Text>
      <Button className="button button-secondary" onClick={() => Taro.switchTab({ url: '/pages/me/index' })}>账号与授权</Button>
      <Text className="section-title">显示</Text><Text className="muted">跟随系统明暗；距离 km、速度 km/h、温度 °C、胎压 bar。按当前响应单位归一后显示。</Text>
      <Button className="button button-secondary" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=tariff' })}>分时电价设置</Button><Button className="button button-secondary" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=tpmsSettings' })}>胎压提醒设置</Button>
      <Text className="section-title">提醒</Text><Text className="muted">前台新行程/充电提示自动去重。微信订阅消息尚无已验证模板和服务端发送能力，订阅同意/拒绝与真实送达尚未验收。</Text>
      <Button className="button button-secondary" onClick={() => Taro.openSetting()}>查看微信权限设置</Button>
      <Button className="button button-secondary" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=about' })}>关于 MateLink</Button>
    </View> : null}
    {kind === 'tariff' ? <View className="card"><Text className="muted">可按 App 编辑峰、平、谷价格与时段；本机配置仅作估算依据，不覆盖服务端实际费用。</Text>
      {(['currency', 'peak', 'flat', 'valley'] as const).map(key => <View className="tariff-field" key={key}><Text>{({ currency: '币种（例如 CNY）', peak: '峰价 / kWh', flat: '平价 / kWh', valley: '谷价 / kWh' })[key]}</Text><Input value={tariff[key]} type={key === 'currency' ? 'text' : 'digit'} onInput={event => setTariff({ ...tariff, [key]: key === 'currency' ? event.detail.value.toUpperCase() : event.detail.value })} /></View>)}
      <View className="status"><Text>启用分时电价</Text><Switch checked={tariff.enabled} onChange={event => setTariff({ ...tariff, enabled: event.detail.value })} /></View><View className="tariff-field"><Text>默认充电电价 / kWh（缺账单估算，关闭分时时也使用）</Text><Input value={tariff.defaultPrice} type="digit" onInput={event => setTariff({ ...tariff, defaultPrice: event.detail.value })} /></View>{(['peakRanges', 'flatRanges', 'valleyRanges'] as const).map(key => <View className="tariff-field" key={key}><Text>{({ peakRanges: '峰段小时', flatRanges: '平段小时', valleyRanges: '谷段小时' })[key]}</Text><Input value={tariff[key]} onInput={event => setTariff({ ...tariff, [key]: event.detail.value })} /></View>)}<Text className="muted">时段左闭右开、覆盖24小时且不重叠；跨午夜分两段。电价记录保存时本机IANA时区，估算不随设备移动改时区。关闭分时后只按明确默认电价估算。</Text><Button className="button button-secondary" onClick={() => setTariff({ ...tariff, currency: 'CNY', peak: '1', flat: '.7', valley: '.3', peakRanges: DEFAULT_TARIFF_PERIODS.peak, flatRanges: DEFAULT_TARIFF_PERIODS.flat, valleyRanges: DEFAULT_TARIFF_PERIODS.valley, enabled: true })}>恢复 App 默认时段和参考价格</Button>
      <Button className="button" onClick={saveTariff}>保存本机电价</Button></View> : null}
    {kind === 'about' ? <View className="card"><Text className="section-title">MateLink 车迹</Text><Text>小程序 {APP_MINIPROGRAM_VERSION}</Text><Text className="metric-source">构建：{process.env.TARO_APP_SOURCE_REVISION?.slice(0, 12) || '未确认'}{process.env.TARO_APP_SOURCE_REVISION?.endsWith('-dirty') ? ' · 含未提交改动' : ''}</Text><Text className="muted">车辆、行程、充电与历史分析。记录的来源、时间和已知覆盖均单独展示。</Text><Text className="section-title">本版更新</Text><Text className="muted">0.4.9 全面加固全页面导航几何防穿透底线；分析与设置页返回按钮规范化对齐 App build 70 触控标准；深度核实字体阶梯、色系及各主屏与详情卡片布局比例。</Text><Button className="button button-secondary" onClick={() => Taro.navigateTo({ url: '/pages/legal/index?kind=privacy' })}>隐私指引</Button><Button className="button button-secondary" onClick={() => Taro.navigateTo({ url: '/pages/legal/index?kind=terms' })}>服务条款</Button><Button className="button button-secondary" onClick={() => { void Taro.setClipboardData({ data: 'https://github.com/Jovifei/tesla-master-mimo/issues' }) }}>复制问题反馈地址</Button></View> : null}
    {kind === 'location' ? coordinate ? <View className="card"><Map onError={() => setError("地图暂时不可用，请检查微信权限后重试")} className="vehicle-map" latitude={coordinate.latitude} longitude={coordinate.longitude} scale={14} markers={[{ id: 1, ...coordinate, iconPath: '/assets/tabs/vehicle-selected.png', width: 24, height: 24, title: car?.displayName || '车辆位置' }]} /><Text className="metric-source">状态报告时间（非位置独立观测时间） · {status?.observedAt || '未提供'}</Text><Text className="muted">地图使用一次转换后的坐标；位置仅在线显示，不写入本机历史缓存。</Text><Button className="button button-secondary" onClick={() => Taro.navigateTo({ url: '/pages/tools/index?kind=whereWasI' })}>查询历史时刻的位置</Button></View> : <View className="card"><Text>暂无有效的位置观测</Text></View> : null}
    {kind === 'whereWasI' ? <View className="card"><Text className="muted">查询时刻使用本机显示时区；仅展示已有报告和实际采样，不插值或生成未知位置。{complete ? '已读历史已翻页完成。' : '当前为有界部分历史。'}</Text><View className="tools-actions"><Picker mode="date" value={targetDate} onChange={event => setTargetDate(event.detail.value)}><Text>{targetDate} ⌄</Text></Picker><Picker mode="time" value={targetTime} onChange={event => setTargetTime(event.detail.value)}><Text>{targetTime} ⌄</Text></Picker></View><Button className="button" loading={loading} disabled={!car} onClick={() => { void findHistoricPosition() }}>查询这个时刻</Button>{historicReason ? <Text className="muted">{historicReason}</Text> : null}{historicCoordinate && historicPosition ? <><Map className="vehicle-map" onError={() => setError('地图暂时不可用，位置报告已保留')} latitude={historicCoordinate.latitude} longitude={historicCoordinate.longitude} scale={14} markers={[{ id: 1, ...historicCoordinate, iconPath: '/assets/tabs/vehicle-selected.png', width: 24, height: 24 }]} /><Text className="muted">{historicPosition.label}</Text><Text className="metric-source">{historicPosition.at || '独立位置观测时间未知'} · {historicPosition.source || '来源未标明'}</Text></> : null}</View> : null}
    {kind === 'tpmsSettings' && car && session?.userId ? <TpmsSettingsPanel scope={{ origin: getApiOrigin(), userId: session.userId, stableId: car.stableId }} snapshot={status?.tpms} history={readTpmsCache(Taro, session.userId, getApiOrigin(), car.stableId)} isCurrent={() => { try { return epoch.current === profileEpoch && owner.current === scopeKey() } catch { return false } }} /> : null}
    {kind === 'tpms' ? <TpmsPanel snapshot={status?.tpms} report={status?.tpmsReport} history={car && session?.userId ? readTpmsCache(Taro, session.userId, getApiOrigin(), car.stableId) : null} /> : null}
    {kind === 'temperature' ? <View className="card"><View className="metric-grid"><MetricValue label="车内温度" value={status?.insideTemp} unit="°C" /><MetricValue label="车外温度" value={status?.outsideTemp} unit="°C" /></View><Text className="muted">当前接口未提供逐点温度历史与独立观测时间，不能以状态报告时间生成历史曲线。</Text></View> : null}
    {kind === 'sentry' ? <View className="card"><MetricValue label="当前哨兵状态" value={status?.sentryMode == null ? null : status.sentryMode ? '开启' : '关闭'} /><Text className="muted">当前状态不能还原告警事件。App 本机保存的哨兵历史未通过服务端共享，当前小程序没有已验证事件历史。</Text></View> : null}
    {kind === 'updates' ? <View className="card">{updates.length ? updates.map(update => <View className="status" key={update.id}><Text>{update.version || '版本未提供'}</Text><Text>{historyDateTime(update.startDate)}</Text></View>) : <Text>当前页暂无已记录的软件更新</Text>}<Text className="muted">仅当前响应记录，不代表全部软件历史。</Text><View className="tools-actions">{page > 1 ? <Button onClick={() => { void load(page - 1) }}>上一页</Button> : null}{updates.length === 20 ? <Button onClick={() => { void load(page + 1) }}>下一页</Button> : null}</View></View> : null}
    {(kind === 'report' || kind === 'export') && car && !error && !loading ? <View className="card"><Text className="muted">{complete ? '当前历史响应翻页完成' : '部分历史覆盖 · 自动读取最多500条/25页'}；日期按本机显示时区分组。</Text>
      {kind === 'report' ? <><Picker mode="date" fields="year" value={String(year)} onChange={event => setYear(Number(event.detail.value))}><View className="status"><Text>报告年份</Text><Text>{year} ⌄</Text></View></Picker><View className="metric-grid"><MetricValue label="行程次数" value={report.trips.length} unit="次" /><MetricValue label="充电次数" value={report.sessions.length} unit="次" /><MetricValue label="已知距离" value={report.drive.distance.value} unit="km" /><MetricValue label="配对平均能耗" value={report.drive.efficiency.value} unit="kWh/100km" negative={(report.drive.efficiency.value ?? 0) < 0} /></View><Text className="muted">配对覆盖 {report.drive.efficiency.known}/{report.drive.efficiency.total}；能量与距离独立计数。含本机手填费用 {report.sessions.filter(row => (row as { costProvenance?: string }).costProvenance === 'manual').length} 条，原始账单仍保留。</Text>{report.costs.map(cost => <MetricValue key={cost.currency} label={`${cost.count}条已知费用`} value={cost.value} unit={cost.currency} />)}<Button className="button" disabled={loading || Boolean(error) || !car} onClick={() => { void exportFile(true) }}>导出年度 PDF</Button></> : <><Text className="section-title">导出当前已读取历史</Text><View className="tools-actions"><Picker mode="selector" range={['CSV', 'JSON']} value={exportFormat === 'csv' ? 0 : 1} onChange={event => setExportFormat(Number(event.detail.value) === 1 ? 'json' : 'csv')}><Text>格式：{exportFormat.toUpperCase()} ⌄</Text></Picker><Picker mode="selector" range={['行程和充电', '仅行程', '仅充电']} value={['both', 'drives', 'charges'].indexOf(exportType)} onChange={event => setExportType((['both', 'drives', 'charges'] as const)[Number(event.detail.value)] || 'both')}><Text>内容：{({ both: '行程和充电', drives: '仅行程', charges: '仅充电' })[exportType]} ⌄</Text></Picker></View><View className="tools-actions"><Picker mode="date" value={exportQuery.startDate} onChange={event => setExportQuery(normalizeHistoryQuery({ ...exportQuery, startDate: event.detail.value }))}><Text>开始：{exportQuery.startDate || '不限'}</Text></Picker><Picker mode="date" value={exportQuery.endDate} onChange={event => setExportQuery(normalizeHistoryQuery({ ...exportQuery, endDate: event.detail.value }))}><Text>结束：{exportQuery.endDate || '不限'}</Text></Picker></View><Text className="muted">行程 {exportedDrives.length} 条 · 充电 {exportedCharges.length} 条。导出原始日期、已知指标、来源与质量；手填费用和原始账单分列，不含车辆身份、精确位置、轨迹或令牌。</Text><Button className="button" disabled={loading || Boolean(error) || !car} onClick={() => { void exportFile() }}>导出文件并分享</Button></>}
    </View> : null}
    {kind === 'countries' || kind === 'regions' ? <View className="card"><Text className="section-title">已读取的地点记录</Text><Text className="muted">缺少可信国家/行政区编码和已授权地理解析合同，不能从地点文字推断到访国家或地区；当前仅列已有历史地点，不代表完整足迹。</Text>{[...new Set(drives.flatMap(drive => [drive.startAddress, drive.endAddress]).filter(Boolean))].slice(0, 100).map(address => <View className="status" key={address!}><Text>{address}</Text></View>)}</View> : null}
    {kind === 'vehiclePreview' ? <View className="card"><View className="vehicle-wireframe-stage" onTouchStart={event => {
        const [first, second] = previewTouches(event)
        if (first) gesture.current = { x: first.clientX, y: first.clientY, distance: second ? Math.hypot(second.clientX - first.clientX, second.clientY - first.clientY) : 0, zoom, pan }
      }} onTouchMove={event => {
        const [first, second] = previewTouches(event), initial = gesture.current
        if (!first) return
        if (second && initial.distance > 0) setZoom(Math.max(.5, Math.min(3, initial.zoom * Math.hypot(second.clientX - first.clientX, second.clientY - first.clientY) / initial.distance)))
        else setPan({ x: Math.max(-120, Math.min(120, initial.pan.x + first.clientX - initial.x)), y: Math.max(-120, Math.min(120, initial.pan.y + first.clientY - initial.y)) })
      }}><View className="vehicle-wireframe" style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom}) perspective(600px) rotateX(55deg) rotateY(${angle}deg)` }}><View className={previewOpen.frunk ? 'preview-open' : ''} /><View className={previewOpen.doors ? 'preview-open' : ''} /><View className={previewOpen.port ? 'preview-open' : ''} /></View></View><Slider min={-180} max={180} value={angle} onChange={event => setAngle(event.detail.value)} /><Slider min={50} max={300} value={zoom * 100} onChange={event => setZoom(event.detail.value / 100)} /><View className="tools-actions">{(['doors', 'frunk', 'port'] as const).map(key => <Button key={key} onClick={() => setPreviewOpen({ ...previewOpen, [key]: !previewOpen[key] })}>{({ doors: '车门示意', frunk: '前备箱示意', port: '充电口示意' })[key]}：{previewOpen[key] ? '开' : '关'}</Button>)}</View><Button className="button button-secondary" onClick={() => { setAngle(0); setZoom(1); setPan({ x: 0, y: 0 }); setPreviewOpen({ doors: false, frunk: false, port: false }) }}>重置预览</Button><Text className="muted">车辆线框示意 · 可旋转、拖移、缩放；示意开关不发送任何车辆命令，不表示已载入实车3D模型。</Text></View> : null}
  </View>
}
