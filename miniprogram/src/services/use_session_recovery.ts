import { useEffect, useRef } from 'react'
import { subscribeSessionRecovery } from './api'

/** Re-run the active page after a rejected session starts shared recovery. */
export function useSessionRecoveryReload(reload: () => void): void {
  const latest = useRef(reload)
  latest.current = reload
  useEffect(() => subscribeSessionRecovery(() => latest.current()), [])
}
