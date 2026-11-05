import Taro from '@tarojs/taro'
import { useEffect, useState } from 'react'
import { visualMotionAllowed } from '../../domain/interaction_visuals'

/** Listener identity is preserved for cleanup; no polling or authentication side effects. */
export function useVisualMotion(active: boolean, pending: boolean, reducedMotion = false): boolean {
  const [foreground, setForeground] = useState(true)
  useEffect(() => {
    const hide = () => setForeground(false), show = () => setForeground(true)
    const supported = typeof Taro.onAppHide === 'function' && typeof Taro.offAppHide === 'function' &&
      typeof Taro.onAppShow === 'function' && typeof Taro.offAppShow === 'function'
    if (!supported) return
    Taro.onAppHide(hide); Taro.onAppShow(show)
    return () => { Taro.offAppHide(hide); Taro.offAppShow(show) }
  }, [])
  return visualMotionAllowed(active && foreground, reducedMotion, pending)
}
