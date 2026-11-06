import { describe, expect, it } from 'vitest'
import {
  formatParkedAddress,
  formatRouteAddresses,
  parseChineseAddress,
  toChineseDisplayAddress,
} from './address_display'

describe('address_display', () => {
  it('removes English administrative suffix when Chinese address is available', () => {
    expect(toChineseDisplayAddress('仓兴街, Yuhang District')).toBe('仓兴街')
  })

  it('translates English POI and district', () => {
    expect(toChineseDisplayAddress('FamilyMart, Yuhang District')).toBe('全家便利店 余杭区')
  })

  it('keeps non-Chinese addresses readable', () => {
    expect(toChineseDisplayAddress('San Jose, CA')).toBe('San Jose, CA')
  })

  it('handles null, empty and whitespace addresses', () => {
    expect(toChineseDisplayAddress(null)).toBeNull()
    expect(toChineseDisplayAddress('')).toBeNull()
    expect(toChineseDisplayAddress('   ')).toBeNull()
  })

  it('preserves synthetic unrecognised text and punctuation anomaly', () => {
    expect(toChineseDisplayAddress('synthetic-address-###')).toBe('synthetic-address-###')
    expect(toChineseDisplayAddress(' ?? ;;; ')).toBe('?? ;;;')
    expect(toChineseDisplayAddress('合成街')).toBe('合成街')
  })

  it('same district and street omits all prefixes showing detail only', () => {
    const start = '浙江省杭州市余杭区仓前街道重庆鸡公煲(万通中心店)万通中心'
    const end = '浙江省杭州市余杭区仓前街道钱腾云都'
    const [dispStart, dispEnd] = formatRouteAddresses(start, end)
    expect(dispStart).toBe('重庆鸡公煲(万通中心店)万通中心')
    expect(dispEnd).toBe('钱腾云都')
  })

  it('same district different streets shows street and beyond', () => {
    const start = '浙江省杭州市余杭区仓前街道好呷温州小吃泡炸万通中心'
    const end = '浙江省杭州市余杭区五常街道西溪印象城'
    const [dispStart, dispEnd] = formatRouteAddresses(start, end)
    expect(dispStart).toBe('仓前街道好呷温州小吃泡炸万通中心')
    expect(dispEnd).toBe('五常街道西溪印象城')
  })

  it('same city different districts shows district and beyond', () => {
    const start = '浙江省杭州市余杭区仓前街道'
    const end = '浙江省杭州市西湖区转塘街道'
    const [dispStart, dispEnd] = formatRouteAddresses(start, end)
    expect(dispStart).toBe('余杭区仓前街道')
    expect(dispEnd).toBe('西湖区转塘街道')
  })

  it('municipality same city omits municipality', () => {
    const start = '北京市朝阳区望京街10号'
    const end = '北京市海淀区中关村南大街1号'
    const [dispStart, dispEnd] = formatRouteAddresses(start, end)
    expect(dispStart).toBe('朝阳区望京街10号')
    expect(dispEnd).toBe('海淀区中关村南大街1号')
  })

  it('same province different cities keeps city only', () => {
    const start = '浙江省杭州市余杭区仓前街道'
    const end = '浙江省宁波市鄞州区首南街道'
    const [dispStart, dispEnd] = formatRouteAddresses(start, end)
    expect(dispStart).toBe('杭州市余杭区仓前街道')
    expect(dispEnd).toBe('宁波市鄞州区首南街道')
  })

  it('different provinces keeps full addresses', () => {
    const start = '浙江省杭州市余杭区仓前街道'
    const end = '江苏省苏州市工业园区金鸡湖大道'
    const [dispStart, dispEnd] = formatRouteAddresses(start, end)
    expect(dispStart).toBe('浙江省杭州市余杭区仓前街道')
    expect(dispEnd).toBe('江苏省苏州市工业园区金鸡湖大道')
  })

  it('single known endpoint is gracefully shortened when other side unknown', () => {
    const start = '浙江省杭州市余杭区仓前街道好呷温州小吃泡炸万通中心'
    const [dispStart, dispEnd] = formatRouteAddresses(start, '未知', '未知')
    expect(dispStart).toBe('好呷温州小吃泡炸万通中心')
    expect(dispEnd).toBe('未知')
  })

  it('parked address strips province city district and street showing detail only', () => {
    const raw = '浙江省杭州市余杭区仓前街道青象传媒杭州精诚电力设备有限公司'
    expect(formatParkedAddress(raw)).toBe('青象传媒杭州精诚电力设备有限公司')
  })

  it('parked address preserves street when no detail', () => {
    const raw = '浙江省杭州市余杭区仓前街道'
    expect(formatParkedAddress(raw)).toBe('仓前街道')
  })

  it('parked address preserves city when no district or street', () => {
    const raw = '杭州市'
    expect(formatParkedAddress(raw)).toBe('杭州市')
  })
})
