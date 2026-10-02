export type HistoryQuality = 'observed' | 'derived' | 'incomplete' | 'quarantined' | 'unknown'

export function normalizeHistoryQuality(value: unknown): HistoryQuality {
  if (typeof value !== 'string') return 'unknown'
  const normalized = value.trim().toLowerCase() as HistoryQuality
  return ['observed', 'derived', 'incomplete', 'quarantined'].includes(normalized)
    ? normalized
    : 'unknown'
}

export function isAnalysisEligible(qualityState: unknown): boolean {
  const quality = normalizeHistoryQuality(qualityState)
  return quality === 'observed' || quality === 'derived'
}

export function qualityLabel(qualityState: unknown): string {
  switch (normalizeHistoryQuality(qualityState)) {
    case 'observed': return '真实观测'
    case 'derived': return '有依据推导'
    case 'incomplete': return '摘要不完整'
    case 'quarantined': return '已隔离'
    default: return '来源未知'
  }
}
