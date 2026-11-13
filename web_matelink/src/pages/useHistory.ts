import { useCallback, useEffect, useRef, useState } from 'react'
import { getAllHistory } from '../api/matelink'
import type { WebCharge, WebDriveSummary } from '../api/matelink-types'
type State = { carId: number | null; drives: WebDriveSummary[]; charges: WebCharge[]; complete: boolean; loading: boolean; error: string | null }
const empty = (carId: number | null): State => ({carId, drives:[], charges:[], complete:false, loading:carId!=null, error:null})
export function useHistory(carId: number | null, options?: { pollMs?: number; pollWhen?: boolean }) {
  const [state, setState] = useState<State>(() => empty(carId))
  const request = useRef<AbortController | null>(null)
  const load = useCallback(async () => {
    if (carId == null) return
    request.current?.abort()
    const controller = new AbortController(); request.current=controller
    setState(s => ({...(s.carId===carId?s:empty(carId)), loading:true, error:null}))
    try {
      const result=await getAllHistory(carId,controller.signal)
      if(!controller.signal.aborted) setState({carId,...result,loading:false,error:null})
    } catch {
      if(!controller.signal.aborted) setState(s => ({...s,loading:false,error:'读取历史记录失败；已有部分保留，不能据此认定期间没有记录。'}))
    } finally { if(request.current===controller) request.current=null }
  },[carId])
  useEffect(() => { setState(empty(carId)); void load(); return () => request.current?.abort() },[carId,load])
  const pollMs=options?.pollMs, pollWhen=options?.pollWhen??false
  useEffect(() => { if(!pollMs||!pollWhen)return;const timer=window.setInterval(()=>{ if(!request.current || request.current.signal.aborted) void load() },pollMs);return()=>window.clearInterval(timer) },[load,pollMs,pollWhen])
  return {...(state.carId===carId?state:empty(carId)),reload:load}
}
