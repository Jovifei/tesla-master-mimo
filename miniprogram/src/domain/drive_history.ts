import type { Drive } from '../services/types'

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

export function buildDriveHistoryItems(drives: readonly Drive[]): DriveHistoryItem[] {
  if (drives.length === 0) return []
  // The list is newest-first; only >= 0.5 km drives qualify as parking
  // boundaries so newly visible unknown/short drives cannot flip inference.
  const eligible = drives.map((drive, index) => ({ drive, index })).filter(row => (row.drive.distanceKm ?? -1) >= 0.5)
  const parkedAfterIndex = new Map<number, DriveHistoryItem>()
  for (let position = 0; position + 1 < eligible.length; position++) {
    const newer = eligible[position].drive, older = eligible[position + 1].drive
    const segment = createParkedSegment(older, newer)
    if (segment) parkedAfterIndex.set(eligible[position].index, segment)
  }
  const items: DriveHistoryItem[] = []
  drives.forEach((drive, index) => {
    items.push({ kind: 'drive', key: `drive-${drive.id}`, drive, dateForIndicator: drive.startDate })
    const parked = parkedAfterIndex.get(index)
    if (parked) items.push(parked)
  })
  return items
}

function createParkedSegment(older: Drive, newer: Drive): DriveHistoryItem | null {
  const start = Date.parse(older.endDate ?? ''), end = Date.parse(newer.startDate ?? '')
  if (!Number.isFinite(start) || !Number.isFinite(end)) return null
  const minutes = Math.floor((end - start) / 60000)
  if (minutes <= 0) return null
  return {
    kind: 'parked',
    key: `parked-${older.id}-${newer.id}`,
    older, newer,
    startDate: older.endDate!,
    endDate: newer.startDate!,
    minutes,
    address: parkedAddressLabel(older.endAddress, newer.startAddress),
    dateForIndicator: older.endDate!,
  }
}
