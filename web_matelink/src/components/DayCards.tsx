import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useAppearance } from './Appearance'
import { dayCaption, daySwipeTarget } from './presentation-policy'
export type DayGroup<T> = { key: string; items: T[] }
export function DayCards<T>({ groups, children }: { groups: DayGroup<T>[]; children: (day: DayGroup<T>) => ReactNode }) {
  const { value } = useAppearance()
  const [selected, setSelected] = useState<string | null>(null)
  const start = useRef<{ x: number; y: number; id: number; axis: string } | null>(null)
  useEffect(() => {
    if(selected!=null && !groups.some(g=>g.key===selected))setSelected(null)
    start.current=null
  },[groups,selected,value.dayAxis])
  const index = Math.max(0, groups.findIndex(g => g.key === selected))
  const choose = (i: number) => setSelected(groups[Math.max(0, Math.min(groups.length - 1, i))]?.key ?? null)
  if (!groups.length) return <p>尚无可展示日期。</p>
  const pageStart = Math.floor(index / 7)*7
  const rear = groups.slice(pageStart+1,pageStart+7).map((g,i) => ({g, i:pageStart+i+1, depth:i+1})).reverse()
  return <section className={`ml-day-stack axis-${value.dayAxis}`} aria-label="每日行程面板">
    {rear.map(({g,i,depth},order) => <button className="ml-day-rear" key={g.key} style={{marginInline:depth*3,zIndex:order+1}} aria-pressed={i===index} onClick={() => choose(i===index ? 0 : i)}>{dayCaption(g.key)}</button>)}
    <div className="ml-day-front">
      <div className="ml-day-header" tabIndex={0} role="group" aria-label="滑动日期抬头切换日期"
        style={{touchAction: value.dayAxis==='horizontal' ? 'pan-y' : 'pan-x'}}
        onKeyDown={e => { const older=value.dayAxis==='horizontal'?'ArrowRight':'ArrowDown', newer=value.dayAxis==='horizontal'?'ArrowLeft':'ArrowUp'; if(e.key===older || e.key===newer) {e.preventDefault();choose(index+(e.key===older?1:-1))} }}
        onPointerDown={e => { if(!e.isPrimary || e.button!==0) {start.current=null;return}; start.current={x:e.clientX,y:e.clientY,id:e.pointerId,axis:value.dayAxis};e.currentTarget.setPointerCapture(e.pointerId) }}
        onPointerCancel={() => { start.current=null }}
        onPointerUp={e => { const p=start.current;start.current=null;if(p && p.id===e.pointerId && p.axis===value.dayAxis)choose(daySwipeTarget(index,groups.length,e.clientX-p.x,e.clientY-p.y,value.dayAxis)) }}>
        <h2 aria-live="polite">{dayCaption(groups[index].key)}</h2><span className="text-secondary">{value.dayAxis==='horizontal'?'左右':'上下'}切日期 · 点下层日期展开，再点收回</span>
      </div>
      <div className="ml-day-nav"><button disabled={!index} onClick={() => choose(index-1)}>较新日期</button><span>{index+1} / {groups.length} 天</span><button disabled={index===groups.length-1} onClick={() => choose(index+1)}>较旧日期</button></div>
      <div key={groups[index].key} className="ml-day-content">{groups[index].items.length ? children(groups[index]) : <p className="ml-empty">当天尚无已返回记录，不代表没有行程。</p>}</div>
      {index>0 ? <button className="ml-day-close" onClick={() => choose(0)}>收回至 {dayCaption(groups[0].key)}</button> : null}
    </div>
  </section>
}
