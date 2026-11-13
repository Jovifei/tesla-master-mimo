from pathlib import Path
r=Path('web_matelink')
p=r/'src/api/matelink.ts';s=p.read_text()
s=s.replace("const SESSION_KEY = 'matelink-web-session-v1'", """const SESSION_KEY = 'matelink-web-session-v1'
let sessionGeneration = 0
const sessionListeners = new Set<() => void>()
function sessionChanged(): void { sessionGeneration++; for (const fn of sessionListeners) fn() }
if (typeof window !== 'undefined') window.addEventListener('storage', event => { if (event.key === SESSION_KEY || event.key === null) sessionChanged() })
export function sessionScope(): string { return `${sessionGeneration}:${loadSession()?.userId ?? ''}` }
export function subscribeSession(fn: () => void): () => void { sessionListeners.add(fn); return () => { sessionListeners.delete(fn) } }
function assertScope(scope: string): void { if (sessionScope() !== scope) throw new ApiError('会话已变化，已忽略旧响应。', 'stale_session') }
""")
s=s.replace("  storage.setItem(SESSION_KEY, JSON.stringify(session))\n}","  storage.setItem(SESSION_KEY, JSON.stringify(session))\n  sessionChanged()\n}",1)
s=s.replace("  storage.removeItem(SESSION_KEY)\n}", "  storage.removeItem(SESSION_KEY)\n  sessionChanged()\n}",1)
a=s.index('async function request(');b=s.index('/* ---------------- auth flow',a)
s=s[:a]+'''async function request(path: string, init: RequestInit & { accessToken?: string } = {}): Promise<unknown> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (init.accessToken) headers.Authorization = `Bearer ${init.accessToken}`
  const controller = new AbortController()
  const cancel = () => controller.abort()
  if (init.signal?.aborted) controller.abort()
  init.signal?.addEventListener('abort', cancel, { once: true })
  const timeout = setTimeout(cancel, 20_000)
  try {
    const response = await fetch(`${API_BASE}${path}`, { ...init, signal: controller.signal, headers: { ...headers, ...(init.headers ?? {}) } })
    const text = await response.text()
    let payload: unknown = null
    try { payload = text ? JSON.parse(text) : null } catch { /* malformed responses stay unknown */ }
    if (!response.ok) {
      const code = isRecord(payload) && typeof payload.error === 'string' ? payload.error : `http_${response.status}`
      throw new ApiError(`请求失败（${response.status}）：${code}`, code, response.status)
    }
    return payload
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (controller.signal.aborted) throw new ApiError('请求已取消或超时，请重试。', 'request_cancelled')
    throw new ApiError('无法连接服务器，请检查网络后重试。', 'network_error')
  } finally { clearTimeout(timeout); init.signal?.removeEventListener('abort', cancel) }
}

/** A late response or refresh cannot cross an account/logout boundary. */
async function authedRequest(path: string, init: Omit<RequestInit, 'headers'> = {}): Promise<unknown> {
  const scope = sessionScope()
  let session = loadSession()
  if (!session) throw new ApiError('尚未登录。', 'session_required', 401)
  if (!sessionIsUsable(session, Date.now())) session = await refreshSession()
  assertScope(scope)
  if (init.signal?.aborted) throw new ApiError('请求已取消。', 'request_cancelled')
  try {
    const payload = await request(path, { ...init, accessToken: session.accessToken })
    assertScope(scope)
    return payload
  } catch (error) {
    assertScope(scope)
    if (error instanceof ApiError && error.status === 401 && !init.signal?.aborted) {
      const refreshed = await refreshSession()
      assertScope(scope)
      const payload = await request(path, { ...init, accessToken: refreshed.accessToken })
      assertScope(scope)
      return payload
    }
    throw error
  }
}

'''+s[b:]
a=s.index('export async function refreshSession');b=s.index('export async function logout',a)
s=s[:a]+'''let refreshFlight: { scope: string; promise: Promise<WebSession> } | null = null
export function refreshSession(): Promise<WebSession> {
  const scope = sessionScope(), current = loadSession()
  if (!current) return Promise.reject(new ApiError('尚未登录。', 'session_required', 401))
  if (refreshFlight?.scope === scope) return refreshFlight.promise
  const promise = (async () => {
    try {
      const payload = await request('/v1/session/refresh', { method: 'POST', body: JSON.stringify({ refresh_token: current.refreshToken }) })
      assertScope(scope)
      const session = toSession(payload, current.userId)
      if (session.userId !== current.userId) throw new ApiError('刷新身份不匹配。', 'invalid_session_response')
      // Token rotation is not a new login; preserve current request ownership.
      localStorage.setItem(SESSION_KEY, JSON.stringify(session))
      return session
    } catch (error) {
      // A network timeout must not erase a valid session. Never erase a newer login.
      if (sessionScope() === scope && error instanceof ApiError && (error.status === 401 || error.status === 403)) clearSession()
      throw error
    } finally { if (refreshFlight?.scope === scope) refreshFlight = null }
  })()
  refreshFlight = { scope, promise }
  return promise
}

'''+s[b:]
repls=[('getCars():','getCars(signal?: AbortSignal):'),('getCarStatus(carId: number):','getCarStatus(carId: number, signal?: AbortSignal):'),('getDrivesPage(carId: number, page: number, show = 20):','getDrivesPage(carId: number, page: number, show = 20, signal?: AbortSignal):'),('getChargesPage(carId: number, page: number, show = 20):','getChargesPage(carId: number, page: number, show = 20, signal?: AbortSignal):'),('getDriveDetail(carId: number, driveId: string):','getDriveDetail(carId: number, driveId: string, signal?: AbortSignal):'),('getChargeDetail(carId: number, chargeId: string):','getChargeDetail(carId: number, chargeId: string, signal?: AbortSignal):'),('getParkedBetween(carId: number, olderDriveId: string, newerDriveId: string):','getParkedBetween(carId: number, olderDriveId: string, newerDriveId: string, signal?: AbortSignal):'),("authedRequest('/api/v1/cars')", "authedRequest('/api/v1/cars', { signal })"),('authedRequest(`/api/v1/cars/${carId}/status`)','authedRequest(`/api/v1/cars/${carId}/status`, { signal })'),('authedRequest(`/api/v1/cars/${carId}/drives?page=${page}&show=${show}`)','authedRequest(`/api/v1/cars/${carId}/drives?page=${page}&show=${show}`, { signal })'),('authedRequest(`/api/v1/cars/${carId}/charges?page=${page}&show=${show}`)','authedRequest(`/api/v1/cars/${carId}/charges?page=${page}&show=${show}`, { signal })'),('authedRequest(`/api/v1/cars/${carId}/drives/${encodeURIComponent(driveId)}`)','authedRequest(`/api/v1/cars/${carId}/drives/${encodeURIComponent(driveId)}`, { signal })'),('authedRequest(`/api/v1/cars/${carId}/charges/${encodeURIComponent(chargeId)}`)','authedRequest(`/api/v1/cars/${carId}/charges/${encodeURIComponent(chargeId)}`, { signal })'),('authedRequest(`/api/matelink/v1/cars/${carId}/parked/${olderDriveId}/${newerDriveId}`)','authedRequest(`/api/matelink/v1/cars/${carId}/parked/${olderDriveId}/${newerDriveId}`, { signal })'),('  carId: number\n): Promise<{ drives:', '  carId: number, signal?: AbortSignal\n): Promise<{ drives:'),('getDrivesPage(carId, page)\n','getDrivesPage(carId, page, 20, signal)\n'),('getChargesPage(carId, page)\n','getChargesPage(carId, page, 20, signal)\n')]
for a,b in repls:
 assert a in s,a;s=s.replace(a,b)
p.write_text(s)
p=r/'src/App.tsx';s=p.read_text().replace('useEffect, useRef, useState, type ReactNode','useEffect, useRef, useState, useSyncExternalStore, type ReactNode').replace('loadSession, logout }','loadSession, logout, sessionScope, subscribeSession }')
s=s.replace('  if (!loadSession()) return <Navigate',"  const scope = useSyncExternalStore(subscribeSession, sessionScope, () => '')\n  if (!loadSession()) return <Navigate").replace('return <Shell>{children}</Shell>','return <Shell key={scope}>{children}</Shell>');p.write_text(s)
(r/'src/pages/useHistory.ts').write_text('''import { useCallback, useEffect, useRef, useState } from 'react'
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
''')
p=r/'src/pages/useCar.ts';s=p.read_text().replace('useCallback, useEffect, useState','useEffect, useState');a=s.index('  const load =');b=s.index('  const car =',a)
s=s[:a]+'''  useEffect(() => {
    const controller=new AbortController()
    getCars(controller.signal).then(list => {
      if(controller.signal.aborted)return
      setCars(list)
      setCarId(current => current!=null && list.some(car=>car.id===current) ? current : list[0]?.id??null)
      setError(list.length?null:'当前账号暂无车辆。')
    }).catch(()=>{if(!controller.signal.aborted)setError('读取车辆列表失败，请稍后重试。')})
    return()=>controller.abort()
  }, [])

'''+s[b:];p.write_text(s)
p=r/'src/pages/DataInfoPage.tsx';s=p.read_text().replace('CarPicker, ErrorNote, GapPanel, LoadingNote','CarPicker, ErrorNote, LoadingNote');a=s.index('        <GapPanel');b=s.index('        />',a)+len('        />')
s=s[:a]+'''        <details className="ml-disclosure"><summary>为什么还没有新充电记录？</summary><div>
          <p>这里仅统计当前账号和车辆已返回的记录。接口可访问、Tesla 授权有效和完整历史已同步，是三个不同状态。</p>
          <p>先核对充电页的筛选和最新记录日期，再加载更多或刷新。未返回记录不能解释为未充电；需要服务端同步证据才能判断数据链路。</p>
          <button className="apple-btn-secondary" disabled={history.loading} onClick={() => void history.reload()}>重新读取历史</button>
        </div></details>
        <details className="ml-disclosure"><summary>高级诊断与数据库信息</summary><div>
          <p>网页当前接口不提供数据库表行数、同步队列、证书或 Fleet 配置的完整报告。未检查不等于故障，也不会显示为已通过。</p>
          <p>不会通过此页面唤醒车辆、补造充电或修改历史。</p>
        </div></details>'''+s[b:];p.write_text(s)
