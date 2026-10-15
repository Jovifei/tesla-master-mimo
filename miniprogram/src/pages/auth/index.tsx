import { Button, Text, View, WebView } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useLoad, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, getApiSessionGeneration, isTrustedAuthorizationURL, matelinkApi } from '../../services/api'
import { clearPendingTeslaAuthorization, readAppSession, readPendingTeslaAuthorization } from '../../services/session'
import LoadingIndicator from '../../components/LoadingIndicator'

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) {
    switch (reason.code) {
      case 'api_not_configured': return '服务地址尚未配置，请返回后重新开始'
      case 'session_changed': return '会话已变化，请返回“更多”重新开始'
      case 'network_error': return '网络暂时不可用，请稍后检查'
      case 'wechat_authorization_expired': return '授权事务已过期，请重新开始'
      case 'wechat_authorization_cancelled': return '授权已取消，请重新开始'
      case 'wechat_authorization_failed': return 'Tesla 授权失败，请重新开始'
    }
  }
  return 'Tesla 授权暂时不可用，请重新检查'
}

function bridgePayload(value: unknown): { callbackRef: string; error: string } | null {
  if (typeof value === 'string') {
    try {
      return bridgePayload(JSON.parse(value))
    } catch {
      return null
    }
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const record = value as Record<string, unknown>
  return {
    callbackRef: typeof record.callback_ref === 'string' ? record.callback_ref : '',
    error: typeof record.error === 'string' ? record.error : '',
  }
}

export default function AuthPage() {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [exchanging, setExchanging] = useState(false)
  const claimedRef = useRef<string | null>(null)
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
      scope.session?.accessToken === current.session?.accessToken &&
      scope.pending?.transactionId === current.pending?.transactionId &&
      scope.pending?.clientProof === current.pending?.clientProof && scope.pending?.apiOrigin === current.pending?.apiOrigin
  }

  const claimAuthorization = (callbackRef = '') => {
    const normalizedRef = callbackRef.trim()
    if (claimedRef.current === normalizedRef || !pageAlive.current || !pageScope.current || !sameScope(pageScope.current)) return
    const run = ++claimRun.current
    const scope = captureScope()
    claimedRef.current = normalizedRef
    setExchanging(true)
    void matelinkApi.claimWechatAuthorization(normalizedRef)
      .then(session => {
        if (!pageAlive.current || claimRun.current !== run) return
        const current = readAppSession(Taro)
        if (getApiSessionGeneration() !== scope.generation + 1 || current?.userId !== session.userId ||
            current?.accessToken !== session.accessToken || readPendingTeslaAuthorization(Taro)) return
        Taro.showToast({ title: 'Tesla 授权成功', icon: 'success' })
        Taro.navigateBack()
      })
      .catch(reason => {
        if (!pageAlive.current || claimRun.current !== run || !sameScope(scope)) return
        if (claimedRef.current === normalizedRef) claimedRef.current = null
        setError(errorMessage(reason))
      })
      .finally(() => { if (pageAlive.current && claimRun.current === run) setExchanging(false) })
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
      const status = await matelinkApi.getWechatAuthorizationStatus()
      if (!pageAlive.current || operation !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || !sameScope(scope)) return
      if (status.status === 'ready') claimAuthorization()
      else if (status.status === 'pending') setError('授权尚未回流，请完成 Tesla 官方页面后再检查')
      else if (status.status === 'expired' || status.status === 'failed' || status.status === 'cancelled') {
        clearPendingTeslaAuthorization(Taro)
        const messages = { expired: '授权事务已过期，请重新开始', failed: 'Tesla 授权失败，请重新开始', cancelled: '授权已取消，请重新开始' }
        setError(messages[status.status])
      } else if (status.status === 'claimed') setError('授权已领取，请返回“更多”恢复会话')
      else if (status.status !== 'none') setError('授权状态暂时不可用，请稍后检查')
      else setError('没有待恢复的授权事务')
    } catch (reason) {
      if (!pageAlive.current || operation !== lifecycleEpoch.current || sessionGeneration !== getApiSessionGeneration() || !sameScope(scope)) return
      if (reason instanceof ApiError && (reason.code === 'wechat_authorization_expired' || reason.code === 'wechat_authorization_cancelled' || reason.code === 'wechat_authorization_failed')) {
        clearPendingTeslaAuthorization(Taro)
      }
      setError(errorMessage(reason))
    }
  }

  useLoad(options => {
    pageScope.current = captureScope()
    const callbackRef = typeof options?.callback_ref === 'string' ? options.callback_ref : ''
    if (callbackRef) {
      claimAuthorization(callbackRef)
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
    claimedRef.current = null
    setExchanging(false)
  }
  useDidHide(invalidatePage)
  useDidShow(() => { pageAlive.current = true })
  useUnload(invalidatePage)

  if (exchanging) {
    return <View className="page"><View className="card"><Text className="section-title">正在完成授权</Text><LoadingIndicator label="正在用当前微信授权事务领取 MateLink 会话…" /></View></View>
  }

  if (error || !url) {
    return (
      <View className="page">
        <View className="card">
          <Text className="section-title">Tesla 官方授权</Text>
          {error ? <Text className="error">{error}</Text> : <LoadingIndicator label="正在准备官方授权入口…" compact />}
          <Button className="button" onClick={() => { void resumeAuthorization() }}>检查授权状态</Button>
          <Button className="button" onClick={() => Taro.navigateBack()}>返回</Button>
        </View>
      </View>
    )
  }

  return (
    <WebView
      src={url}
      onError={() => { if (pageAlive.current) setError('授权页面加载失败，请返回后检查授权状态') }}
      onMessage={event => {
        if (!pageAlive.current || !pageScope.current || !sameScope(pageScope.current)) return
        const value = event.detail?.data
        const messages = Array.isArray(value) ? value : [value]
        const payload = bridgePayload(messages[messages.length - 1])
        if (payload?.callbackRef) claimAuthorization(payload.callbackRef)
        else if (payload?.error) setError('Tesla 授权失败，请返回后重新检查')
      }}
    />
  )
}
