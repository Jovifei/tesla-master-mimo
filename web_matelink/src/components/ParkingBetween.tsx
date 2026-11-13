import { useEffect, useState } from 'react'
import { getParkedBetween } from '../api/matelink'
import type { WebDriveSummary, WebParked } from '../api/matelink-types'
export function ParkingBetween({carId,older,newer}:{carId:number;older:WebDriveSummary;newer:WebDriveSummary}) {
  const [data,setData]=useState<WebParked|null>(null),[open,setOpen]=useState(false),[loaded,setLoaded]=useState(false)
  const from=Date.parse(older.endDate??''),to=Date.parse(newer.startDate??'')
  const valid=Number.isFinite(from)&&Number.isFinite(to)&&to>from&&older.source!=null&&older.source===newer.source&&older.qualityState!=='quarantined'&&newer.qualityState!=='quarantined'&&/^\d+$/.test(older.id)&&/^\d+$/.test(newer.id)
  useEffect(()=>{
    if(!open||!valid)return
    const controller=new AbortController();setLoaded(false);setData(null)
    getParkedBetween(carId,older.id,newer.id,controller.signal).then(value=>{
      if(!controller.signal.aborted){
        const begin=Date.parse(value?.startDate??''),end=Date.parse(value?.endDate??'')
        setData(value&&begin===from&&end===to?value:null);setLoaded(true)
      }
    }).catch(()=>{if(!controller.signal.aborted)setLoaded(true)})
    return()=>controller.abort()
  },[open,valid,carId,older.id,newer.id,from,to])
  if(!valid)return null
  return <details className="ml-parking" onToggle={e=>setOpen(e.currentTarget.open)}><summary><strong>P</strong> 两次行程间驻车 · {Math.round((to-from)/60000)} 分钟 <span>查看 ›</span></summary>
    {open ? !loaded?<p>正在读取同一间隔的驻车记录…</p>:data?<div><p>{data.address??older.endAddress??'地点尚未返回'}</p><p>起止电量 {data.startBatteryLevel??'—'}% → {data.endBatteryLevel??'—'}%</p><p>驻车能耗 {data.energyKwh==null?'尚未发布':data.energyKwh.toFixed(2)+' kWh'}</p></div>:<p>间隔由相邻行程时间得到，尚无匹配的驻车明细；不推算能耗、温度或休眠状态。</p>:null}
  </details>
}
