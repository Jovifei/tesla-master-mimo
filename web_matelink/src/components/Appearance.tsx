import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { normalizeAppearance, type Appearance } from './presentation-policy'
const KEY = 'matelink-web-appearance-v1'
const Context = createContext({ value: normalizeAppearance(null), save: (_: Partial<Appearance>) => {}, failed: false })
function read(): Appearance { try { return normalizeAppearance(JSON.parse(localStorage.getItem(KEY) ?? 'null')) } catch { return normalizeAppearance(null) } }
export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [value, setValue] = useState(read), [failed, setFailed] = useState(false)
  useEffect(() => { document.documentElement.dataset.palette = value.palette; document.documentElement.dataset.reduceMotion = String(value.reducedMotion) }, [value])
  useEffect(() => { const f = (e: StorageEvent) => { if (e.key === KEY) setValue(read()) }; window.addEventListener('storage', f); return () => window.removeEventListener('storage', f) }, [])
  const save = (patch: Partial<Appearance>) => {
    const next = normalizeAppearance({ ...value, ...patch }); setValue(next)
    try { localStorage.setItem(KEY, JSON.stringify(next)); setFailed(false) } catch { setFailed(true) }
  }
  return <Context.Provider value={{value, save, failed}}>{children}</Context.Provider>
}
export function useAppearance() { return useContext(Context) }
export function AppearanceSettings() {
  const { value, save, failed } = useAppearance()
  return <section className="apple-card ml-appearance" aria-label="外观与交互">
    <h2>外观与交互</h2><p className="text-secondary">只影响本机显示，不改变账号、车辆、历史与数据计算。</p>
    <fieldset><legend>主题</legend>{([['neutral','C · 中性白'],['cream','A · 奶白']] as const).map(([palette,label]) => <label key={palette}><input type="radio" name="palette" checked={value.palette===palette} onChange={() => save({palette})}/>{label}</label>)}</fieldset>
    <fieldset><legend>日期卡片切换</legend>{([['horizontal','左右滑动（默认）'],['vertical','上下滑动']] as const).map(([dayAxis,label]) => <label key={dayAxis}><input type="radio" name="day-axis" checked={value.dayAxis===dayAxis} onChange={() => save({dayAxis})}/>{label}</label>)}</fieldset>
    <p className="text-secondary">滑动仅作用于日期抬头；行程正文正常上下滚动。</p>
    <label><input type="checkbox" checked={value.reducedMotion} onChange={e => save({reducedMotion:e.target.checked})}/>减少动画</label>
    {failed ? <p role="status" className="text-error">本次已切换，但浏览器未允许保存；重开后可能恢复默认。</p> : null}
  </section>
}
