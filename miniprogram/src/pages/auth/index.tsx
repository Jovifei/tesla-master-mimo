import { Button, Text, View, WebView } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useLoad, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, getApiSessionGeneration, isTrustedAuthorizationURL, matelinkApi } from '../../services/api'
import { readAppSession, readPendingTeslaAuthorization } from '../../services/session'
import LoadingIndicator from '../../components/LoadingIndicator'
import SubpageHeader from '../../components/SubpageHeader'

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) {
    switch (reason.code) {
      case 'api_not_configured': return '服务地址尚未配置，请返回后重新开始'
      case 'session_changed': return '会话已变化，请返回“更多”重新开始'
      case 'network_error': return '网络暂时不可用，请稍后检查'
      case 'authorization_expired': return '授权事务已过期，请重新开始'
      case 'authorization_cancelled': return '授权已取消，请重新开始'
      case 'authorization_consumed': return '本次授权已使用，请返回重新登录特斯拉账号'
      case 'authorization_failed': return 'Tesla 授权失败，请重新开始'
    }
  }
  return 'Tesla 授权暂时不可用，请重新检查'
}

// WebView messages are untrusted wake-up hints. Only the server status for
// the locally owned transaction may authorize a claim.
function hasAuthorizationHint(value: unknown): boolean {
  if (typeof value === 'string') {
    try { return hasAuthorizationHint(JSON.parse(value)) } catch { return false }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const record = value as Record<string, unknown>
  return ['callback_ref', 'ticket', 'error'].some(key => typeof record[key] === 'string' && record[key] !== '')
}

export default function AuthPage() {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [exchanging, setExchanging] = useState(false)
  const [desktopSimulator] = useState(() => {
    try { return Taro.getSystemInfoSync?.().platform === 'devtools' } catch { return false }
  })
  const claimInFlight = useRef(false)
  const claimRun = useRef(0)
  const lifecycleEpoch = useRef(0)
  const pageAlive = useRef(true)
  const captureScope = () => ({ pending: readPendingTeslaAuthorization(Taro),
    session: readAppSession(Taro), generation: getApiSessionGeneration() })
  type AuthorizationScope = ReturnType<typeof captureScope>
  const pageScope = useRef<AuthorizationScope | null>(null)
  const sameScope = (scope: AuthorizationScope) => {
    const current = captureScope()
    return scope.generation === current.generation && scope.session?.userId === current.session?.userId &&
      scope.session?.accessToken === current.session?.accessToken && scope.session?.apiOrigin === current.session?.apiOrigin &&
      scope.pending?.transactionId === current.pending?.transactionId &&
      scope.pending?.clientProof === current.pending?.clientProof && scope.pending?.apiOrigin === current.pending?.apiOrigin
  }

  const claimAuthorization = () => {
    if (claimInFlight.current || !pageAlive.current || !pageScope.current || !sameScope(pageScope.current)) return
    const run = ++claimRun.current
    const scope = captureScope()
    claimInFlight.current = true
    setExchanging(true)
    void matelinkApi.claimTeslaAuthorization()
      .then(session => {
        if (!pageAlive.current || claimRun.current !== run) return
        const current = readAppSession(Taro)
        if (getApiSessionGeneration() !== scope.generation + 1 || current?.userId !== session.userId ||
            current?.accessToken !== session.accessToken || current?.apiOrigin !== session.apiOrigin || readPendingTeslaAuthorization(Taro)) return
        Taro.showToast({ title: '特斯拉账号登录成功', icon: 'success' })
        Taro.navigateBack()
      })
      .catch(reason => {
        if (!pageAlive.current || claimRun.current !== run || !sameScope(scope)) return
        setError(errorMessage(reason))
      })
      .finally(() => { if (pageAlive.current && claimRun.current === run) { claimInFlight.current = false; setExchanging(false) } })
  }

  const resumeAuthorization = async () => {
    if (!pageAlive.current) return
    const operation = ++lifecycleEpoch.current
    const sessionGeneration = getApiSessionGeneration()
    const scope = captureScope()
    if (!pageScope.current || !sameScope(pageScope.current)) {
      setError('授权事务已变化，请返回“更多”重新开始')
      return
    }
    setError(null)
    try {
      const status = await matelinkApi.getTeslaAuthorizationStatus()
      if (!pageAlive.current || operation !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || !sameScope(scope)) return
      if (status.status === 'ready') claimAuthorization()
      else if (status.status === 'pending') setError('授权尚未回流，请完成 Tesla 官方页面后再检查')
      else if (status.status === 'expired' || status.status === 'failed' || status.status === 'cancelled') {
        const messages = { expired: '授权事务已过期，请重新开始', failed: 'Tesla 授权失败，请重新开始', cancelled: '授权已取消，请重新开始' }
        setError(messages[status.status])
      } else if (status.status === 'consumed') setError('本次授权已使用，请返回重新登录特斯拉账号')
      else if (status.status !== 'none') setError('授权状态暂时不可用，请稍后检查')
      else setError('没有待恢复的授权事务')
    } catch (reason) {
      if (!pageAlive.current || operation !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || !sameScope(scope)) return
      setError(errorMessage(reason))
    }
  }

  useLoad(options => {
    pageScope.current = captureScope()
    if (options?.callback_ref || options?.ticket) {
      void resumeAuthorization()
      return
    }
    try {
      const candidate = typeof options?.url === 'string' ? decodeURIComponent(options.url) : ''
      if (isTrustedAuthorizationURL(candidate)) setUrl(candidate)
      else setError('授权入口无效，请从“更多”页面重新开始')
    } catch {
      setError('授权入口编码无效，请从“更多”页面重新开始')
    }
  })

  const invalidatePage = () => {
    pageAlive.current = false
    lifecycleEpoch.current += 1
    claimRun.current += 1
    claimInFlight.current = false
    setExchanging(false)
  }
  useDidHide(invalidatePage)
  useDidShow(() => { pageAlive.current = true })
  useUnload(invalidatePage)

  const copyOfficialEntry = async () => {
    if (!url || !isTrustedAuthorizationURL(url) || !pageAlive.current || !pageScope.current || !sameScope(pageScope.current)) return
    const scope = captureScope(), operation = lifecycleEpoch.current
    try {
      await Taro.setClipboardData({ data: url })
      if (pageAlive.current && operation === lifecycleEpoch.current && sameScope(scope))
        Taro.showToast({ title: '官方入口已复制，请在浏览器打开', icon: 'none' })
    } catch {
      if (pageAlive.current && operation === lifecycleEpoch.current && sameScope(scope)) setError('复制失败，请重试')
    }
  }

  if (exchanging) {
    return <View className="page subpage-page"><SubpageHeader title="Tesla 官方授权" subtitle="正在完成授权" /><View className="card"><Text className="section-title">正在完成授权</Text><LoadingIndicator label="正在完成当前特斯拉账号登录…" /></View></View>
  }

  if (error || !url || desktopSimulator) {
    return (
      <View className="page subpage-page">
        <SubpageHeader title="Tesla 官方授权" subtitle="官方账号登录与授权" />
        <View className="card">
          {error ? <Text className="error">{error}</Text> : !url ? <LoadingIndicator label="正在准备官方授权入口…" compact /> : null}
          {url ? <>
            <Text className="muted">{desktopSimulator
              ? '官方授权入口已就绪。电脑开发者工具请在系统浏览器完成 Tesla 官方登录，随后返回这里检查授权状态。'
              : '若微信无法打开官方页面，请复制入口到系统浏览器完成登录，再返回小程序检查授权状态。'}</Text>
            <Button className="button" onClick={() => { void copyOfficialEntry() }}>复制 Tesla 官方授权入口</Button>
          </> : null}
          <Button className="button" onClick={() => { void resumeAuthorization() }}>检查授权状态</Button>
          <Button className="button" onClick={() => Taro.navigateBack()}>返回</Button>
        </View>
      </View>
    )
  }

  return (
    <WebView
      src={url}
      onError={() => { if (pageAlive.current) setError('微信未能打开 Tesla 官方页面，可复制入口到浏览器继续') }}
      onMessage={event => {
        if (!pageAlive.current || !pageScope.current || !sameScope(pageScope.current)) return
        const value = event.detail?.data
        const messages = Array.isArray(value) ? value : [value]
        if (messages.some(hasAuthorizationHint)) void resumeAuthorization()
      }}
    />
  )
}
