import { useEffect, useState } from 'react'
import Taro from '@tarojs/taro'
import { normalizeAppearance, type Appearance } from '../domain/ui_presentation'

export const APPEARANCE_KEY = 'matelink.presentation.v1'
const listeners = new Set<(value: Appearance) => void>()
let volatilePreference: Appearance | null = null
export function readAppearance(): Appearance {
  if (volatilePreference) return volatilePreference
  try { return normalizeAppearance(Taro.getStorageSync(APPEARANCE_KEY)) } catch { return normalizeAppearance(null) }
}
export function writeAppearance(patch: Partial<Appearance>): boolean {
  const value = normalizeAppearance({ ...readAppearance(), ...patch })
  let saved = true
  try { Taro.setStorageSync(APPEARANCE_KEY, value); volatilePreference = null } catch { volatilePreference = value; saved = false }
  listeners.forEach(listener => listener(value))
  return saved
}
export function subscribeAppearance(listener: (value: Appearance) => void): () => void {
  listeners.add(listener); return () => { listeners.delete(listener) }
}
export function useAppearance() {
  const [appearance, setAppearance] = useState(readAppearance)
  useEffect(() => subscribeAppearance(setAppearance), [])
  return { ...appearance, className: `appearance-${appearance.palette}${appearance.reducedMotion ? ' reduce-motion' : ''}` }
}
/** Native navigation chrome uses the same palette; no network or account state. */
export function syncAppearanceChrome(theme?: string): void {
  try {
    const dark = (theme ?? Taro.getSystemInfoSync().theme) === 'dark'
    const cream = readAppearance().palette === 'cream'
    const background = dark ? '#111315' : cream ? '#f3f2ee' : '#f5f5f7'
    void Taro.setNavigationBarColor({ frontColor: dark ? '#ffffff' : '#000000', backgroundColor: background }).catch(() => {})
    void Taro.setTabBarStyle({ color: dark ? '#b7bdc6' : '#6e6e73', selectedColor: dark ? '#f5f6f7' : '#1d1d1f',
      backgroundColor: dark ? '#15181c' : cream ? '#fffefa' : '#ffffff', borderStyle: dark ? 'black' : 'white' }).catch(() => {})
  } catch { /* Native chrome is not available in isolated tests; content remains themed. */ }
}
