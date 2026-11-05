/** Test-only DOM adapter. Not imported by the native WeChat production bundle. */
import React from 'react'
const props = (input: any) => {
  const { ariaLabel, mode, ...rest } = input
  return { ...rest, ...(ariaLabel ? { 'aria-label': ariaLabel } : {}) }
}
export const View = (p: any) => <div {...props(p)} />
export const Text = (p: any) => <span {...props(p)} />
export const Button = (p: any) => <button type="button" {...props(p)} />
export const Image = (p: any) => <img {...props(p)} alt={p.ariaLabel ?? ''} style={{ objectFit: 'contain', ...p.style }} />
const hide = new Set<() => void>(), show = new Set<() => void>()
const forbidden = () => { throw new Error('The visual fixture cannot request or persist account/vehicle data') }
export const foreground = (active: boolean) => (active ? show : hide).forEach(cb => cb())
const Taro = { onAppHide: (cb: () => void) => hide.add(cb), offAppHide: (cb: () => void) => hide.delete(cb),
  onAppShow: (cb: () => void) => show.add(cb), offAppShow: (cb: () => void) => show.delete(cb),
  request: forbidden, setStorageSync: forbidden, getStorageSync: forbidden, removeStorageSync: forbidden }
export default Taro
