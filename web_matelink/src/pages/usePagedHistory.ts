import { useCallback, useEffect, useRef, useState } from 'react'
import type { HistoryPage } from '../api/matelink'
type State<T> = { owner: number | null; rows: T[]; page: number; totalPages: number; loading: boolean; error: string | null }
const empty = <T,>(owner: number | null): State<T> => ({owner,rows:[],page:0,totalPages:1,loading:owner!=null,error:null})
export function usePagedHistory<T>(carId: number | null, loader: (id: number, page: number, show?: number, signal?: AbortSignal) => Promise<HistoryPage<T>>) {
  const [state,setState]=useState<State<T>>(()=>empty<T>(carId))
  const request=useRef<AbortController|null>(null)
  const load=useCallback(async(page: number, append: boolean)=>{
    if(carId==null || (append && request.current))return
    request.current?.abort()
    const controller=new AbortController();request.current=controller
    setState(s=>({...s,loading:true,error:null}))
    try {
      const result=await loader(carId,page,20,controller.signal)
      if(!controller.signal.aborted)setState(s=>({owner:carId,rows:append&&s.owner===carId?[...s.rows,...result.rows]:result.rows,page,totalPages:result.totalPages,loading:false,error:null}))
    } catch(error) { if(!controller.signal.aborted)setState(s=>({...s,loading:false,error:error instanceof Error?error.message:'读取记录失败。'})) }
    finally { if(request.current===controller)request.current=null }
  },[carId,loader])
  useEffect(()=>{setState(empty<T>(carId));void load(1,false);return()=>request.current?.abort()},[carId,load])
  const current=state.owner===carId?state:empty<T>(carId)
  return {...current,reload:()=>load(1,false),loadMore:()=>load(current.page+1,true)}
