import { useCallback, useEffect, useRef, useState } from 'react'
import { getAllHistory } from '../api/matelink'
import type { WebCharge, WebDriveSummary } from '../api/matelink-types'

/**
 * Full-history loader shared by the TeslaMate-reproduction pages.
 * Pages that poll (live charging) pass `pollMs` plus `pollWhen` so the
 * refresh only runs while it is meaningful （充电中）.
 */
export function useHistory(carId: number | null, options?: { pollMs?: number; pollWhen?: boolean }) {
  const [drives, setDrives] = useState<WebDriveSummary[]>([])
  const [charges, setCharges] = useState<WebCharge[]>([])
  const [complete, setComplete] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const seqRef = useRef(0)

  const load = useCallback(async () => {
    if (carId == null) return
    const seq = ++seqRef.current
    setLoading(true)
    try {
      const result = await getAllHistory(carId)
      if (seqRef.current !== seq) return
      setDrives(result.drives)
      setCharges(result.charges)
      setComplete(result.complete)
      setError(null)
    } catch {
      if (seqRef.current === seq) setError('读取历史记录失败，请稍后重试。')
    } finally {
      if (seqRef.current === seq) setLoading(false)
    }
  }, [carId])

  useEffect(() => {
    void load()
  }, [load])

  const pollMs = options?.pollMs
  const pollWhen = options?.pollWhen ?? false
  useEffect(() => {
    if (!pollMs || !pollWhen) return
    const timer = window.setInterval(() => void load(), pollMs)
    return () => window.clearInterval(timer)
  }, [load, pollMs, pollWhen])

  return { drives, charges, complete, loading, error, reload: load }
}
