import { readTariff, tariffKey } from '../domain/tariff'
import { validTpmsProfile } from '../domain/tpms_alerts'
import { validVehicleAppearance } from './vehicle_appearance'
import type { WechatStorage } from './session'

const encode = (value: string) => encodeURIComponent(value).replace(/\./g, '%2E')
const record = (value: unknown): Record<string, unknown> | null => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null
function component(value: string): boolean {
  try { return Boolean(value) && !value.includes('.') && encode(decodeURIComponent(value)) === value } catch { return false }
}

/** Only called after a server-confirmed deletion and current epoch/account check. Logout never calls this. */
export function clearNewAccountCaches(storage: Pick<WechatStorage, 'getStorageSync' | 'getStorageInfoSync' | 'removeStorageSync'>, user: string, origin: string): void {
  if (!user.trim() || !/^https:\/\/[a-z0-9.-]+(?::443)?$/i.test(origin)) return
  const owner = `${encode(origin)}.${encode(user)}.`, tariff = tariffKey(user, origin)
  let keys: string[]
  try { keys = storage.getStorageInfoSync?.().keys ?? [] } catch { return }
  for (const key of keys) {
    try {
      const value = record(storage.getStorageSync(key))
      if (!value) continue
      if (key === tariff) {
        // The existing tariff v1 is a plain known profile, not an envelope. Never guess a new schema.
        if (!('schema' in value) && readTariff(storage, user, origin)) storage.removeStorageSync(key)
        continue
      }
      if (value.schema !== 1 || value.scope !== key) continue
      for (const namespace of ['tpms', 'events', 'manual-trips', 'charge-cost', 'tpms-profile', 'vehicle-appearance'] as const) {
        const prefix = `matelink.${namespace}.v1.${owner}`
        if (!key.startsWith(prefix)) continue
        const suffix = key.slice(prefix.length).split('.')
        const validSuffix = component(suffix[0]) && (namespace === 'tpms' ? suffix.length === 2 && suffix[1] === 'telemetry_mqtt' :
          namespace === 'events' ? suffix.length === 2 && ['drives', 'charges'].includes(suffix[1]) : suffix.length === 1)
        if (!validSuffix) continue
        const known = namespace === 'tpms' ? Array.isArray(value.points) : namespace === 'events' ? record(value.sources) !== null :
          namespace === 'manual-trips' ? Array.isArray(value.trips) : namespace === 'charge-cost' ? Array.isArray(value.rows) :
            namespace === 'tpms-profile' ? value.source === 'user_configured' && validTpmsProfile(value.profile) :
              value.source === 'user_configured' && validVehicleAppearance(value.config)
        if (known) storage.removeStorageSync(key)
      }
    } catch { /* Preserve unreadable/unknown records and continue checking other exact owned keys. */ }
  }
}
