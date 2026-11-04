/** Presentational state only. No network, authentication, storage or vehicle actions. */
export type ConnectionPhase = 'idle' | 'connecting' | 'action_required' | 'connected' | 'unavailable'
export function connectionVisualPhase({ pending = false, verified = false, actionRequired = false, failed = false }:
  { pending?: boolean; verified?: boolean; actionRequired?: boolean; failed?: boolean }): ConnectionPhase {
  return failed ? 'unavailable' : actionRequired ? 'action_required' : pending ? 'connecting' : verified ? 'connected' : 'idle'
}
export function hasVisualRouteEndpoints(start?: string | null, end?: string | null): boolean {
  return Boolean(start?.trim() && end?.trim())
}
export function visualMotionAllowed(foreground: boolean, reducedMotion: boolean, pending: boolean): boolean {
  return foreground && !reducedMotion && pending
}
export const VISUAL_WHEEL_NAMES = Object.freeze({ fl: '左前轮', fr: '右前轮', rl: '左后轮', rr: '右后轮' })
export const VISUAL_GEOMETRY = Object.freeze({ width: 240, height: 460, transitionMs: 220, signalMs: 1200 })
