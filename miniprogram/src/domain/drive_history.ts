import type { Drive } from '../services/types'
import { historyRowKey } from './history'
import { isAppDriveAnalysisEligible } from './app_history_projection'

/** App DrivesScreen.buildDriveHistoryItems: list rows alternate drives with the
 * parking windows between eligible neighbours (>= 0.5 km route boundary). */
export type DriveHistoryItem =
  | { kind: 'drive'; key: string; drive: Drive; dateForIndicator: string | null }
  | { kind: 'parked'; key: string; older: Drive; newer: Drive; startDate: string; endDate: string; minutes: number; address: string | null; dateForIndicator: string }

const han = /[\u4e00-\u9fff]/
const separators = /[,，;；\n|｜()（）]/
const englishPrefix = /^[A-Za-z][A-Za-z .'-]*\s+(?=[\u4e00-\u9fff])/
const englishSuffix = /\s+[A-Za-z][A-Za-z .'-]*$/

/** App ParkedAddressPresentation.parkedAddressLabel: prefer the first candidate
 * with Chinese components; a real non-Chinese address still renders. */
export function parkedAddressLabel(...candidates: (string | null | undefined)[]): string | null {
  const available = candidates.map(value => value?.trim()).filter((value): value is string => Boolean(value))
  for (const candidate of available) {
    const chinese = candidate.split(separators)
      .map(part => part.trim().replace(englishPrefix, '').replace(englishSuffix, '').trim())
      .filter((part, index, parts) => han.test(part) && parts.indexOf(part) === index)
    if (chinese.length > 0) return chinese.join(' ')
  }
  return available[0] ?? null
}

/** Both boundaries must come from the same identified collector within this vehicle scope. */
export function sameParkedSource(older: Drive, newer: Drive): boolean {
  const source = older.source?.trim(), instance = older.sourceInstanceId?.trim()
  const oldVehicle = older.sourceVehicleId?.trim() || null, newVehicle = newer.sourceVehicleId?.trim() || null
  return Boolean(source && instance && oldVehicle && source === newer.source?.trim() && instance === newer.sourceInstanceId?.trim() && oldVehicle === newVehicle)
}

function parkedPairKey(older: Drive, newer: Drive): string {
  return JSON.stringify([historyRowKey(older), older.startDate, older.endDate, historyRowKey(newer), newer.startDate, newer.endDate])
}

export function verifiedParkedPairs(fullHistory: readonly Drive[]): Set<string> {
  if (fullHistory.length < 2) return new Set()
  if (fullHistory.some(d => !Number.isFinite(Date.parse(d.startDate ?? '')) || !Number.isFinite(Date.parse(d.endDate ?? '')))) {
    return new Set()
  }
  const sorted = [...fullHistory].sort((a, b) => {
    const diff = Date.parse(b.startDate ?? '') - Date.parse(a.startDate ?? '')
    return diff !== 0 ? diff : String(b.id).localeCompare(String(a.id))
  })
  const pairs = new Set<string>()
  for (let i = 0; i + 1 < sorted.length; i++) {
    const newer = sorted[i], older = sorted[i + 1]
    const bothRoutes = [newer, older].every(d =>
      d.distanceKm != null && Number.isFinite(d.distanceKm) && d.distanceKm >= 0.5 &&
      isAppDriveAnalysisEligible(d)
    )
    if (bothRoutes && sameParkedSource(older, newer) && createParkedSegment(older, newer) != null) {
      pairs.add(parkedPairKey(older, newer))
    }
  }
  return pairs
}

export function buildDriveHistoryItems(
  drives: readonly Drive[],
  parkedPairs: Set<string> = verifiedParkedPairs(drives)
): DriveHistoryItem[] {
  if (drives.length === 0) return []
  const parkedAfterIndex = new Map<number, DriveHistoryItem>()
  for (let i = 0; i + 1 < drives.length; i++) {
    const newer = drives[i], older = drives[i + 1]
    if (parkedPairs.has(parkedPairKey(older, newer))) {
      const segment = createParkedSegment(older, newer)
      if (segment) parkedAfterIndex.set(i, segment)
    }
  }
  const items: DriveHistoryItem[] = []
  drives.forEach((drive, index) => {
    items.push({ kind: 'drive', key: `drive-${historyRowKey(drive)}`, drive, dateForIndicator: drive.startDate })
    const parked = parkedAfterIndex.get(index)
    if (parked) items.push(parked)
  })
  return items
}

/** Preserve original parking adjacency while filtering the route cards. */
export function selectVisibleDriveHistoryItems(
  historySequence: readonly Drive[],
  visibleDrives: readonly Drive[],
  parkedPairs: Set<string> = verifiedParkedPairs(historySequence)
): DriveHistoryItem[] {
  const visibleIds = new Set(visibleDrives.map(historyRowKey))
  return buildDriveHistoryItems(historySequence, parkedPairs).filter(item => {
    if (item.kind === 'drive') return visibleIds.has(historyRowKey(item.drive))
    return visibleIds.has(historyRowKey(item.older)) && visibleIds.has(historyRowKey(item.newer))
  })
}

function createParkedSegment(older: Drive, newer: Drive): DriveHistoryItem | null {
  const start = Date.parse(older.endDate ?? ''), end = Date.parse(newer.startDate ?? '')
  if (!Number.isFinite(start) || !Number.isFinite(end)) return null
  const minutes = Math.floor((end - start) / 60000)
  if (minutes <= 0) return null
  if (!sameParkedSource(older, newer)) return null
  return {
    kind: 'parked',
    key: `parked-${parkedPairKey(older, newer)}`,
    older, newer,
    startDate: older.endDate!,
    endDate: newer.startDate!,
    minutes,
    address: parkedAddressLabel(older.endAddress, newer.startAddress),
    dateForIndicator: older.endDate!,
  }
}
