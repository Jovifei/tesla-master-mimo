import { describe, expect, it } from 'vitest'
import { isAnalysisEligible, normalizeHistoryQuality, qualityLabel } from './quality'

describe('history quality', () => {
  it('only uses observed and derived rows for analysis', () => {
    expect(isAnalysisEligible('observed')).toBe(true)
    expect(isAnalysisEligible('derived')).toBe(true)
    expect(isAnalysisEligible('incomplete')).toBe(false)
    expect(isAnalysisEligible('quarantined')).toBe(false)
    expect(isAnalysisEligible(null)).toBe(false)
  })

  it('keeps unknown values visible as unknown', () => {
    expect(normalizeHistoryQuality('future_quality')).toBe('unknown')
    expect(qualityLabel('future_quality')).toBe('来源未知')
  })
})
