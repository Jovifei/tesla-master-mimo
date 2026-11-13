import { useMemo, useState } from 'react'
import type { WebCharge } from '../api/matelink-types'
import { chargeShade, dailyChargeCells } from './presentation-policy'
export function ChargeCalendar({ rows }: { rows: readonly WebCharge[] }) {
  const [month, setMonth] = useState(() => { const now=new Date();return new Date(now.getFullYear(),now.getMonth(),1) })
  const [selected,setSelected]=useState<string|null>(null)
  const year=month.getFullYear(), m=month.getMonth()
  const first=`${year}-${String(m+1).padStart(2,'0')}-01`, last=`${year}-${String(m+1).padStart(2,'0')}-${new Date(year,m+1,0).getDate()}`
  const cells=useMemo(() => dailyChargeCells(rows,first,last),[rows,first,last])
  const leading=(new Date(first+'T12:00:00Z').getUTCDay()+6)%7
  const picked=cells.find(c => c.key===selected)
  return <section className="apple-card ml-charge-calendar" aria-label="按日充入电量">
    <div className="ml-section-heading"><h2>按日充入电量</h2><div className="ml-month-nav"><button aria-label="上一月" onClick={() => {setMonth(new Date(year,m-1,1));setSelected(null)}}>‹</button><span>{year} 年 {m+1} 月</span><button aria-label="下一月" onClick={() => {setMonth(new Date(year,m+1,1));setSelected(null)}}>›</button></div></div>
    <p className="text-secondary">一格一天 · kWh · 60 及以上为最深色；— 尚未读到，不代表未充电。</p>
    <div className="ml-calendar-grid">{['一','二','三','四','五','六','日'].map(d => <span className="ml-weekday" key={d}>{d}</span>)}{Array.from({length:leading},(_,i)=><span aria-hidden key={'blank'+i}/>)}
      {cells.map(cell => <button key={cell.key} className="ml-charge-cell" aria-pressed={selected===cell.key} aria-label={`${cell.key} ${cell.value==null?'尚未读到':cell.value.toFixed(2)+' kWh'}，合格 ${cell.known}/${cell.count} 条`}
        style={{background:`color-mix(in srgb, var(--apple-label-primary) ${Math.round(chargeShade(cell.value)*22+4)}%, var(--apple-surface))`}} onClick={()=>setSelected(cell.key)}>
        <span>{cell.key.slice(8)}</span><strong>{cell.value==null?'—':cell.value.toFixed(1)}</strong></button>)}
    </div><p aria-live="polite" className="ml-calendar-caption">{picked ? `${picked.key} · ${picked.value==null?'尚未读到充入电量':picked.value.toFixed(2)+' kWh'} · ${picked.known}/${picked.count} 条有电量值` : '点选小格查看数值及覆盖情况'}</p>
  </section>
}
