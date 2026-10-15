import type { Charge, Drive } from '../services/types'
import { aggregateDriveHistory, coveredTotal, isHistoryQuarantined } from './history'

export function reportYear<T extends { startDate: string | null }>(rows: readonly T[], year: number): T[] {
  return rows.filter(row => row.startDate && Number.isFinite(Date.parse(row.startDate)) && new Date(row.startDate).getFullYear() === year)
}
export function annualReport(drives: readonly Drive[], charges: readonly Charge[], year: number) {
  const trips = reportYear(drives, year).filter(row => !isHistoryQuarantined(row))
  const sessions = reportYear(charges, year).filter(row => !isHistoryQuarantined(row))
  const costs = new Map<string, { value: number; count: number }>()
  for (const charge of sessions) {
    const currency = charge.currencyCode || charge.currency
    if (!currency || charge.cost == null || !Number.isFinite(charge.cost)) continue
    const old = costs.get(currency) || { value: 0, count: 0 }
    costs.set(currency, { value: old.value + charge.cost, count: old.count + 1 })
  }
  return { year, trips, sessions, drive: aggregateDriveHistory(trips),
    battery: coveredTotal(sessions, row => row.energyAddedKwh), costs: [...costs].map(([currency, amount]) => ({ currency, ...amount })) }
}
function csvCell(value: unknown): string {
  if (value == null) return ''
  const numeric = typeof value === 'number' && Number.isFinite(value)
  let text = numeric ? String(value) : String(value).replace(/[\r\n]+/g, ' ')
  if (!numeric && /^[\s]*[=+@-]/.test(text)) text = `'${text}`
  return `"${text.replace(/"/g, '""')}"`
}
/** Explicit non-location export projection; a raw DTO or session is never serialized. */
function exportRows(drives: readonly Drive[], charges: readonly Charge[]): unknown[][] {
  const rows: unknown[][] = [['type', 'start_date', 'end_date', 'distance_km', 'duration_min', 'qualified_net_kwh', 'battery_added_kwh', 'cost', 'currency', 'source', 'quality', 'cost_provenance', 'reported_cost', 'reported_currency']]
  for (const drive of drives.filter(row => !isHistoryQuarantined(row))) rows.push(['drive', drive.startDate, drive.endDate, drive.distanceKm, drive.durationMinutes,
    drive.energy && drive.energy.quality !== 'unknown' ? drive.energy.energyKwh : null, null, null, null, drive.source, drive.energy?.quality ?? 'unknown', null, null, null])
  for (const charge of charges.filter(row => !isHistoryQuarantined(row))) {
    const cost = charge as Charge & { costProvenance?: string; reportedCost?: number | null; reportedCurrency?: string | null }
    rows.push(['charge', charge.startDate, charge.endDate, null, charge.durationMinutes, null, charge.energyAddedKwh, charge.cost,
      charge.currencyCode || charge.currency, charge.source, charge.qualityState, cost.costProvenance || (charge.cost == null ? 'unknown' : 'actual'),
      cost.reportedCost === undefined ? charge.cost : cost.reportedCost, cost.reportedCurrency === undefined ? charge.currencyCode || charge.currency : cost.reportedCurrency])
  }
  return rows
}
export function historyCsv(drives: readonly Drive[], charges: readonly Charge[]): string {
  return '\uFEFF' + exportRows(drives, charges).map(row => row.map(csvCell).join(',')).join('\r\n')
}
export function historyJson(drives: readonly Drive[], charges: readonly Charge[], complete = false): string {
  const [header, ...rows] = exportRows(drives, charges)
  const records = rows.map(row => Object.fromEntries(header.map((key, index) => [String(key), row[index] ?? null])))
  return JSON.stringify({ schema: 1, coverage: { complete, basis: 'current_bounded_history', includesPrivateLocations: false },
    drives: records.filter(row => row.type === 'drive'), charges: records.filter(row => row.type === 'charge') }, null, 2)
}

/** Small offline PDF using the standard CJK CID font; no remote PDF service. */
export function reportPdf(lines: readonly string[]): ArrayBuffer {
  const hex = (text: string) => [...text].map(char => {
    const code = char.codePointAt(0)!
    return code <= 0xffff ? code.toString(16).padStart(4, '0') : '003f'
  }).join('')
  const wrapped: string[] = []
  for (const line of lines.flatMap(text => text.split(/[\r\n]+/))) {
    let text = '', units = 0
    for (const char of line) {
      const width = char.charCodeAt(0) > 255 ? 2 : 1
      if (units + width > (wrapped.length === 0 ? 52 : 78)) { wrapped.push(text); text = ''; units = 0 }
      text += char; units += width
    }
    wrapped.push(text)
  }
  const pages: string[][] = []
  for (let index = 0; index < wrapped.length; index += 35) pages.push(wrapped.slice(index, index + 35))
  if (!pages.length) pages.push(['MateLink'])
  const pageIds = pages.map((_, index) => 6 + index * 2)
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Count ${pages.length} /Kids [${pageIds.map(id => `${id} 0 R`).join(' ')}] >>`,
    `<< /Type /Font /Subtype /Type0 /BaseFont /STSong-Light /Encoding /UniGB-UCS2-H /DescendantFonts [4 0 R] /ToUnicode ${6 + pages.length * 2} 0 R >>`,
    '<< /Type /Font /Subtype /CIDFontType0 /BaseFont /STSong-Light /CIDSystemInfo << /Registry (Adobe) /Ordering (GB1) /Supplement 4 >> /FontDescriptor 5 0 R /DW 1000 /W [1 95 500] >>',
    '<< /Type /FontDescriptor /FontName /STSong-Light /Flags 4 /FontBBox [0 -250 1000 880] /ItalicAngle 0 /Ascent 880 /Descent -120 /CapHeight 700 /StemV 80 >>',
  ]
  pages.forEach((page, index) => {
    const stream = `BT /F1 12 Tf 18 TL 48 790 Td\n${page.map((line, lineIndex) => `${lineIndex ? 'T* /F1 12 Tf 0.12 0.16 0.22 rg ' : index === 0 ? '/F1 18 Tf 0.082 0.478 0.525 rg ' : ''}<${hex(line)}> Tj`).join('\n')}\nET`
    objects.push(`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents ${pageIds[index] + 1} 0 R >>`)
    objects.push(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`)
  })
  const unicode = '/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /MateLinkUnicode def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n1 beginbfrange\n<0000> <FFFF> <0000>\nendbfrange\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend'
  objects.push(`<< /Length ${unicode.length} >>\nstream\n${unicode}\nendstream`)
  let output = '%PDF-1.4\n', offsets = [0]
  objects.forEach((object, index) => { offsets.push(output.length); output += `${index + 1} 0 obj\n${object}\nendobj\n` })
  const xref = output.length
  output += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n${offsets.slice(1).map(offset => `${String(offset).padStart(10, '0')} 00000 n \n`).join('')}trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF`
  return Uint8Array.from(output, char => char.charCodeAt(0)).buffer
}
