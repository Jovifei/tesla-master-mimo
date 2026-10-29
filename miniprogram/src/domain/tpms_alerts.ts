import { restoreTpmsSnapshot, TPMS_RETENTION_MS, TPMS_WHEELS, tpmsInstant, type TpmsPoint, type TpmsSnapshot, type TpmsWheel } from './tpms'

export type TpmsProfile = { enabled: boolean; targetBar: number; lowBar: number; highBar: number; changeEnabled: boolean; deltaBar: number; windowHours: number }
export function validTpmsProfile(value: unknown): value is TpmsProfile {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const p = value as TpmsProfile
  return typeof p.enabled === 'boolean' && typeof p.changeEnabled === 'boolean' &&
    [p.targetBar, p.lowBar, p.highBar, p.deltaBar, p.windowHours].every(n => typeof n === 'number' && Number.isFinite(n)) &&
    p.lowBar > 0 && p.lowBar < p.targetBar && p.targetBar < p.highBar && p.deltaBar >= 0.1 && p.deltaBar <= 2 && Number.isInteger(p.windowHours) && p.windowHours >= 1 && p.windowHours <= 720
}
export type TpmsAlert = { wheel: TpmsWheel; kind: 'low' | 'high' | 'change'; pressureBar: number; observedAt: string; beforeBar: number | null; deltaBar: number | null }
export type TpmsWheelComparison = { wheel: TpmsWheel; state: 'low' | 'high' | 'normal' | 'unknown' | 'disabled'; pressureBar: number | null; observedAt: string | null; beforeBar: number | null; deltaBar: number | null; changed: boolean; reason: string | null }

/** Read-only comparison of this scope's foreground receipts; never claims a delivered notification. */
export function evaluateTpmsAlerts(profile: TpmsProfile | null, snapshot: TpmsSnapshot | null | undefined, points: readonly TpmsPoint[] = [], now = Date.now()): { warnings: TpmsAlert[]; wheels: TpmsWheelComparison[] } {
  const warnings: TpmsAlert[] = [], fresh = restoreTpmsSnapshot(snapshot, now), valid = validTpmsProfile(profile)
  const wheels = TPMS_WHEELS.map(wheel => {
    const reading = fresh.wheels[wheel]
    const base: TpmsWheelComparison = { wheel, state: 'unknown', pressureBar: reading.pressureBar, observedAt: reading.observedAt, beforeBar: null, deltaBar: null, changed: false, reason: null }
    if (!valid) return { ...base, reason: 'profile_not_configured' }
    if (reading.state !== 'observed' || reading.pressureBar === null || !reading.observedAt) return { ...base, reason: reading.reason ?? 'stale_wheel_observation' }
    const currentAt = tpmsInstant(reading.observedAt)!, pressure = reading.pressureBar
    base.state = profile.enabled ? pressure < profile.lowBar ? 'low' : pressure > profile.highBar ? 'high' : 'normal' : 'disabled'
    if (base.state === 'low' || base.state === 'high') warnings.push({ wheel, kind: base.state, pressureBar: pressure, observedAt: reading.observedAt, beforeBar: null, deltaBar: null })
    if (profile.changeEnabled && pressure > 0) {
      const candidates = points.filter(point => {
        const at = tpmsInstant(point.observedAt)
        return point.wheel === wheel && point.source === 'telemetry_mqtt' && point.timeBasis === 'collector_received_at' &&
          typeof point.pressureBar === 'number' && Number.isFinite(point.pressureBar) && point.pressureBar > 0 && point.pressureBar <= 15 &&
          at !== null && at >= now - TPMS_RETENTION_MS && at < currentAt && currentAt - at <= profile.windowHours * 3_600_000
      }).sort((a, b) => tpmsInstant(b.observedAt)! - tpmsInstant(a.observedAt)!)
      const previous = candidates[0]
      if (previous && !candidates.some(point => tpmsInstant(point.observedAt) === tpmsInstant(previous.observedAt) && point.pressureBar !== previous.pressureBar)) {
        base.beforeBar = previous.pressureBar; base.deltaBar = pressure - previous.pressureBar
        base.changed = Math.abs(base.deltaBar) + 1e-9 >= profile.deltaBar
        if (base.changed) warnings.push({ wheel, kind: 'change', pressureBar: pressure, observedAt: reading.observedAt, beforeBar: base.beforeBar, deltaBar: base.deltaBar })
      } else base.reason = previous ? 'conflicting_previous_observation' : 'no_prior_wheel_observation'
    }
    return base
  })
  return { warnings, wheels }
}
