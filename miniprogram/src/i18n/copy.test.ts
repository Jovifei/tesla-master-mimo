import { describe, expect, it } from 'vitest'
import { copy, copyLocale } from './copy'

describe('copy catalogue', () => {
  it('interpolates localized history presets without changing the Chinese default', () => {
    expect(copy('history.lastDays', { days: 30 })).toBe('近 30 天')
    expect(copy('history.lastDays', { days: 30 }, 'en-US')).toBe('Last 30 days')
    expect(copy('charges.modeUnknown')).toBe('模式未确认')
  })

  it('keeps locale catalogues structurally aligned', () => {
    expect(Object.keys(copyLocale('zh-CN'))).toEqual(Object.keys(copyLocale('en-US')))
  })
})
