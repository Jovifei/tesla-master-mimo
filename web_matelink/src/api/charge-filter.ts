import type { WebCharge } from './matelink-types'

/**
 * Charge list filters, mirroring the miniprogram's charges page:
 * by AC/DC mode, by location (address), and cost-bearing only.
 */
export type ChargeFilter = {
  mode: 'all' | 'ac' | 'dc'
  address: string | null
  withCostOnly: boolean
}

export const EMPTY_CHARGE_FILTER: ChargeFilter = { mode: 'all', address: null, withCostOnly: false }

export function filterCharges(charges: WebCharge[], filter: ChargeFilter): WebCharge[] {
  return charges.filter(charge => {
    if (filter.mode !== 'all' && charge.chargeMode !== filter.mode) return false
    if (filter.address != null && charge.address !== filter.address) return false
    if (filter.withCostOnly && !(charge.cost != null && charge.cost > 0)) return false
    return true
  })
}

export function chargeAddresses(charges: WebCharge[]): string[] {
  return [...new Set(charges.map(charge => charge.address).filter((value): value is string => value != null && value !== ''))].sort()
}
