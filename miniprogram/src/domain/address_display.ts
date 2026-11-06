const HAN = /[\u4e00-\u9fff]/

const ADDRESS_TRANSLATIONS: [string, string][] = [
  // Brands & POIs
  ['Tesla Supercharger', '特斯拉超级充电站'],
  ['Tesla Service Center', '特斯拉服务中心'],
  ['Supercharger', '超级充电站'],
  ['Destination Charger', '目的地充电站'],
  ['Destination Charging', '目的地充电站'],
  ['FamilyMart', '全家便利店'],
  ['Starbucks', '星巴克'],
  ["McDonald's", '麦当劳'],
  ['McDonalds', '麦当劳'],
  ['KFC', '肯德基'],
  ['7-Eleven', '7-Eleven'],
  ['Lawson', '罗森'],

  // Specific POI complexes
  ['Software Park', '软件园'],
  ['Technology Park', '科技园'],
  ['Industrial Park', '工业园'],
  ['High-tech Zone', '高新区'],
  ['Railway Station', '火车站'],
  ['Airport', '机场'],

  // Districts (Hangzhou & surrounding)
  ['Yuhang District', '余杭区'],
  ['Yuhang', '余杭区'],
  ['Xihu District', '西湖区'],
  ['Xihu', '西湖区'],
  ['Gongshu District', '拱墅区'],
  ['Gongshu', '拱墅区'],
  ['Binjiang District', '滨江区'],
  ['Binjiang', '滨江区'],
  ['Xiaoshan District', '萧山区'],
  ['Xiaoshan', '萧山区'],
  ["Lin'an District", '临安区'],
  ['Linan District', '临安区'],
  ["Lin'an", '临安区'],
  ['Fuyang District', '富阳区'],
  ['Fuyang', '富阳区'],
  ['Shangcheng District', '上城区'],
  ['Shangcheng', '上城区'],
  ['Xiacheng District', '下城区'],
  ['Xiacheng', '下城区'],
  ['Jianggan District', '江干区'],
  ['Jianggan', '江干区'],
  ['Qiantang District', '钱塘区'],
  ['Qiantang', '钱塘区'],

  // Cities & Provinces
  ['Hangzhou', '杭州市'],
  ['Ningbo', '宁波市'],
  ['Shaoxing', '绍兴市'],
  ['Jiaxing', '嘉兴市'],
  ['Huzhou', '湖州市'],
  ['Jinhua', '金华市'],
  ['Wenzhou', '温州市'],
  ['Taizhou', '台州市'],
  ['Zhoushan', '舟山市'],
  ['Quzhou', '衢州市'],
  ['Lishui', '丽水市'],
  ['Shanghai', '上海市'],
  ['Suzhou', '苏州市'],
  ['Zhejiang', '浙江省'],
  ['Jiangsu', '江苏省'],
  ['China', '中国'],
]

/** App util/AddressDisplayFormatter.toChineseDisplayAddress: keep the Chinese
 * components of a geocoded address without mutating a fully non-Chinese one. */
export function toChineseDisplayAddress(value: string | null | undefined): string | null {
  const original = value?.trim()
  if (!original) return null
  const parts = original.split(/[,，]/).map(part => part.trim()).filter(Boolean)
  const chineseParts = parts.filter(part => HAN.test(part))
  if (chineseParts.length > 0) {
    return chineseParts.join(' ')
  }

  // Fully non-Chinese address: translate known English POI/city/district terms
  const translatedParts = parts.map(part => {
    let translated = part
    for (const [en, zh] of ADDRESS_TRANSLATIONS) {
      const escaped = en.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      translated = translated.replace(new RegExp(`\\b${escaped}\\b`, 'gi'), zh)
    }
    return translated.trim()
  }).filter(p => p.length > 0 && p !== '中国')

  const hasChinese = translatedParts.some(p => HAN.test(p))
  if (hasChinese) {
    const res = translatedParts.join(' ').trim()
    return res.length > 0 ? res : original
  }
  return original
}

const PROVINCE_REGEX = /^(?:中国|中华人民共和国)?\s*(北京市|上海市|天津市|重庆市|北京|上海|天津|重庆|河北省|山西省|辽宁省|吉林省|黑龙江省|江苏省|浙江省|安徽省|福建省|江西省|山东省|河南省|湖北省|湖南省|广东省|海南省|四川省|贵州省|云南省|陕西省|甘肃省|青海省|台湾省|内蒙古自治区|内蒙古|广西壮族自治区|广西|西藏自治区|西藏|宁夏回族自治区|宁夏|新疆维吾尔自治区|新疆|香港特别行政区|香港|澳门特别行政区|澳门)\s*/
const MUNICIPALITIES = new Set(['北京', '北京市', '上海', '上海市', '天津', '天津市', '重庆', '重庆市'])

const CITY_REGEX = /^([^省市区县旗街道镇乡\s]+?(?:市|自治州|地区|盟))\s*/
const DISTRICT_REGEX = /^([^省市区县旗街道镇乡\s]+?(?:区|县|县级市|旗))\s*/
const STREET_REGEX = /^([^省市区县旗街道镇乡\s]+?(?:街道办事处|街道|镇|乡))\s*/

export interface ParsedChineseAddress {
  original: string
  province: string | null
  city: string | null
  district: string | null
  street: string | null
  detail: string
  detailOnly: string
  streetAndBeyond: string
  districtAndBeyond: string
  cityAndBeyond: string
}

export function parseChineseAddress(raw: string): ParsedChineseAddress {
  const trimmed = raw.trim()
  if (!trimmed) {
    return {
      original: raw,
      province: null,
      city: null,
      district: null,
      street: null,
      detail: '',
      detailOnly: raw.trim(),
      streetAndBeyond: '',
      districtAndBeyond: '',
      cityAndBeyond: '',
    }
  }

  let current = trimmed
  let province: string | null = null
  let city: string | null = null
  let district: string | null = null
  let street: string | null = null

  const provMatch = current.match(PROVINCE_REGEX)
  if (provMatch) {
    const matchedProv = provMatch[1]
    province = matchedProv
    current = current.slice(provMatch[0].length).trimStart()
    if (MUNICIPALITIES.has(matchedProv)) {
      city = matchedProv
    }
  }

  if (city === null) {
    const cityMatch = current.match(CITY_REGEX)
    if (cityMatch) {
      city = cityMatch[1]
      current = current.slice(cityMatch[0].length).trimStart()
    }
  }

  const distMatch = current.match(DISTRICT_REGEX)
  if (distMatch) {
    district = distMatch[1]
    current = current.slice(distMatch[0].length).trimStart()
  }

  const streetMatch = current.match(STREET_REGEX)
  if (streetMatch) {
    street = streetMatch[1]
    current = current.slice(streetMatch[0].length).trimStart()
  }

  const detail = current

  const detailOnly = detail.trim() || street?.trim() || raw.trim()

  const streetAndBeyond = (() => {
    if (street && detail.trim()) {
      return (raw.includes(' ') && !detail.startsWith(' ')) ? `${street} ${detail}` : `${street}${detail}`
    }
    if (street) return street
    return detail.trim() || raw
  })().trim()

  const districtAndBeyond = (() => {
    if (district && streetAndBeyond) {
      return (raw.includes(' ') && !streetAndBeyond.startsWith(' ')) ? `${district} ${streetAndBeyond}` : `${district}${streetAndBeyond}`
    }
    if (district) return district
    return streetAndBeyond
  })().trim()

  const cityAndBeyond = (() => {
    if (city && districtAndBeyond) {
      return (raw.includes(' ') && !districtAndBeyond.startsWith(' ')) ? `${city} ${districtAndBeyond}` : `${city}${districtAndBeyond}`
    }
    if (city) return city
    return districtAndBeyond
  })().trim()

  return {
    original: raw,
    province,
    city,
    district,
    street,
    detail,
    detailOnly,
    streetAndBeyond,
    districtAndBeyond,
    cityAndBeyond,
  }
}

function normalizeAdminName(name: string | null | undefined): string | null {
  if (!name || !name.trim()) return null
  return name.trim().replace(/(省|市|自治区|特别行政区|自治州|地区|盟|区|县|县级市|旗|街道办事处|街道|镇|乡)$/, '').trim()
}

/**
 * Formats a route start and end address pair for display.
 * - If start and end are in the same province, city, district, and street, hides common prefixes showing detail only.
 * - If same district but different streets, hides province, city, and district, showing street & detail.
 * - If same city but different districts, hides province and city, showing district & beyond.
 * - If same province but different cities, hides province only, keeping city & beyond.
 * - If across different provinces, shows full addresses with province.
 */
export function formatRouteAddresses(
  startAddress: string | null | undefined,
  endAddress: string | null | undefined,
  fallback = ''
): [string, string] {
  const cleanStart = toChineseDisplayAddress(startAddress) ?? fallback
  const cleanEnd = toChineseDisplayAddress(endAddress) ?? fallback
  if (!cleanStart.trim() || !cleanEnd.trim()) {
    return [cleanStart, cleanEnd]
  }

  if (cleanStart === fallback && cleanEnd === fallback) {
    return [fallback, fallback]
  }

  if (cleanStart === fallback) {
    const compB = parseChineseAddress(cleanEnd)
    const shortEnd = compB.detail.trim() || compB.streetAndBeyond
    return [fallback, shortEnd]
  }

  if (cleanEnd === fallback) {
    const compA = parseChineseAddress(cleanStart)
    const shortStart = compA.detail.trim() || compA.streetAndBeyond
    return [shortStart, fallback]
  }

  const compA = parseChineseAddress(cleanStart)
  const compB = parseChineseAddress(cleanEnd)

  const provA = normalizeAdminName(compA.province)
  const provB = normalizeAdminName(compB.province)
  const cityA = normalizeAdminName(compA.city)
  const cityB = normalizeAdminName(compB.city)
  const distA = normalizeAdminName(compA.district)
  const distB = normalizeAdminName(compB.district)
  const streetA = normalizeAdminName(compA.street)
  const streetB = normalizeAdminName(compB.street)

  const provConflict = Boolean(provA && provB && provA !== provB)
  const cityConflict = Boolean(cityA && cityB && cityA !== cityB)
  const distConflict = Boolean(distA && distB && distA !== distB)
  const streetConflict = Boolean(streetA && streetB && streetA !== streetB)

  const sameDist = !provConflict && !cityConflict && !distConflict && Boolean(distA && distB && distA === distB)
  const sameCity = !provConflict && !cityConflict && (sameDist || Boolean(cityA && cityB && cityA === cityB))
  const sameProv = !provConflict && (sameCity || Boolean(provA && provB && provA === provB))
  const sameStreet = sameDist && !streetConflict && Boolean(streetA && streetB && streetA === streetB)

  if (sameStreet) {
    return [compA.detailOnly, compB.detailOnly]
  }
  if (sameDist) {
    return [compA.streetAndBeyond, compB.streetAndBeyond]
  }
  if (sameCity) {
    return [compA.districtAndBeyond, compB.districtAndBeyond]
  }
  if (sameProv) {
    return [compA.cityAndBeyond, compB.cityAndBeyond]
  }
  return [cleanStart, cleanEnd]
}

/**
 * Formats a parked location address for display.
 * If detail is present after street, shows detail only.
 * Otherwise falls back to street, district, or city.
 */
export function formatParkedAddress(
  parkedAddress: string | null | undefined,
  fallback = ''
): string {
  const clean = toChineseDisplayAddress(parkedAddress) ?? fallback
  if (!clean.trim() || clean === fallback) return clean

  const comp = parseChineseAddress(clean)
  if (comp.detail.trim()) {
    return comp.detail.trim()
  }
  if (comp.street && comp.street.trim()) {
    return comp.street.trim()
  }
  if (comp.district && comp.district.trim()) {
    return comp.district.trim()
  }
  return comp.city?.trim() || clean
}
