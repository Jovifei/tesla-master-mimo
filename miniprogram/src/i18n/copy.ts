export type CopyKey =
  | 'common.summary'
  | 'common.loadingSummary'
  | 'common.refresh'
  | 'common.loadMore'
  | 'common.clearFilters'
  | 'common.noData'
  | 'common.noBattery'
  | 'common.unlimited'
  | 'history.moreFilters'
  | 'history.collapseFilters'
  | 'history.selectDate'
  | 'history.selectEndDate'
  | 'history.all'
  | 'history.today'
  | 'history.lastDays'
  | 'history.lastYear'
  | 'history.localCache'
  | 'history.offlineVehicle'
  | 'history.noMatchedDrives'
  | 'history.noMatchedCharges'
  | 'history.distance'
  | 'history.totalDistance'
  | 'history.duration'
  | 'history.totalTime'
  | 'history.energy'
  | 'history.speed'
  | 'history.cost'
  | 'history.soc'
  | 'drives.title'
  | 'charges.title'
  | 'charges.showShort'
  | 'charges.hideShort'
  | 'charges.modeUnknown'
  | 'charges.ac'
  | 'charges.dc'
  | 'charges.cost'

type Catalogue = Record<CopyKey, string>

const zhCN: Catalogue = {
  'common.summary': '摘要',
  'common.loadingSummary': '正在读取数据摘要…',
  'common.refresh': '刷新',
  'common.loadMore': '加载更多',
  'common.clearFilters': '清除筛选',
  'common.noData': '暂无数据',
  'common.noBattery': '暂无电量',
  'common.unlimited': '不限',
  'history.moreFilters': '更多筛选',
  'history.collapseFilters': '收起筛选',
  'history.selectDate': '选日期',
  'history.selectEndDate': '选择结束日期',
  'history.all': '全部时间',
  'history.today': '今天',
  'history.lastDays': '近 {days} 天',
  'history.lastYear': '近1年',
  'history.localCache': '本机缓存 · 未验证在线车辆',
  'history.offlineVehicle': '车辆暂时无法验证；可查看本机历史，刷新验证后才能读取在线详情或更多记录。',
  'history.noMatchedDrives': '没有符合筛选的行程',
  'history.noMatchedCharges': '没有符合筛选的充电记录',
  'history.distance': '距离',
  'history.totalDistance': '总距离',
  'history.duration': '时长',
  'history.totalTime': '总时间',
  'history.energy': '能量',
  'history.speed': '最高速度',
  'history.cost': '费用',
  'history.soc': '电量',
  'drives.title': '行程记录',
  'charges.title': '充电历史',
  'charges.showShort': '显示短充',
  'charges.hideShort': '隐藏短充',
  'charges.modeUnknown': '模式未确认',
  'charges.ac': 'AC 充电',
  'charges.dc': 'DC 充电',
  'charges.cost': '充电费用',
}

const enUS: Catalogue = {
  ...zhCN,
  'common.summary': 'Summary',
  'common.loadingSummary': 'Loading summary…',
  'common.refresh': 'Refresh',
  'common.loadMore': 'Load more',
  'common.clearFilters': 'Clear filters',
  'common.noData': 'No data',
  'common.noBattery': 'No battery data',
  'common.unlimited': 'No limit',
  'history.moreFilters': 'More filters',
  'history.collapseFilters': 'Collapse filters',
  'history.selectDate': 'Select date',
  'history.selectEndDate': 'Select end date',
  'history.all': 'All time',
  'history.today': 'Today',
  'history.lastDays': 'Last {days} days',
  'history.lastYear': 'Last year',
  'history.localCache': 'Local cache · online vehicle unverified',
  'history.offlineVehicle': 'Vehicle cannot be verified right now; local history remains available.',
  'history.noMatchedDrives': 'No matching trips',
  'history.noMatchedCharges': 'No matching charging sessions',
  'history.distance': 'Distance',
  'history.totalDistance': 'Total distance',
  'history.duration': 'Duration',
  'history.totalTime': 'Total time',
  'history.energy': 'Energy',
  'history.speed': 'Top speed',
  'history.cost': 'Cost',
  'history.soc': 'SOC',
  'drives.title': 'Trips',
  'charges.title': 'Charging',
  'charges.showShort': 'Show short charges',
  'charges.hideShort': 'Hide short charges',
  'charges.modeUnknown': 'Mode unknown',
  'charges.ac': 'AC charging',
  'charges.dc': 'DC charging',
  'charges.cost': 'Charging cost',
}

const catalogues = { 'zh-CN': zhCN, 'en-US': enUS } as const
export type CopyLocale = keyof typeof catalogues

export function copy(key: CopyKey, params: Record<string, string | number> = {}, locale: CopyLocale = 'zh-CN'): string {
  return Object.entries(params).reduce((text, [name, value]) => text.replace(`{${name}}`, String(value)), catalogues[locale][key])
}

export function copyLocale(locale: CopyLocale = 'zh-CN'): Catalogue {
  return { ...catalogues[locale] }
}
