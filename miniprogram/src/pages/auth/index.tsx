import { Button, Text, View, WebView } from '@tarojs/components'
import Taro, { useLoad, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, isTrustedAuthorizationURL, matelinkApi } from '../../services/api'
import { clearPendingTeslaAuthorization } from '../../services/session'

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return reason instanceof Error ? reason.message : 'Tesla 授权暂时不可用'
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

  const claimAuthorization = (callbackRef = '') => {
    const normalizedRef = callbackRef.trim()
    if (claimedRef.current === normalizedRef || !pageAlive.current) return
    const run = ++claimRun.current
    claimedRef.current = normalizedRef
    setExchanging(true)
    void matelinkApi.claimWechatAuthorization(normalizedRef)
      .then(() => {
        if (!pageAlive.current || claimRun.current !== run) return
        Taro.showToast({ title: 'Tesla 授权成功', icon: 'success' })
        Taro.navigateBack()
      })
      .catch(reason => {
        if (!pageAlive.current || claimRun.current !== run) return
        if (claimedRef.current === normalizedRef) claimedRef.current = null
        setError(errorMessage(reason))
      })
      .finally(() => { if (pageAlive.current && claimRun.current === run) setExchanging(false) })
  }

  const resumeAuthorization = async () => {
    const operation = ++lifecycleEpoch.current
    setError(null)
    try {
      const status = await matelinkApi.getWechatAuthorizationStatus()
      if (!pageAlive.current || operation !== lifecycleEpoch.current) return
      if (status.status === 'ready') claimAuthorization()
      else if (status.status === 'pending') setError('授权尚未回流，请完成 Tesla 官方页面后再检查')
      else if (status.status === 'expired' || status.status === 'failed' || status.status === 'cancelled') {
        clearPendingTeslaAuthorization(Taro)
        setError(`授权状态：${status.status}`)
      } else if (status.status !== 'none') setError(`授权状态：${status.status}`)
      else setError('没有待恢复的授权事务')
    } catch (reason) {
      if (!pageAlive.current || operation !== lifecycleEpoch.current) return
      setError(errorMessage(reason))
    }
  }

  useLoad(options => {
    const callbackRef = typeof options?.callback_ref === 'string' ? options.callback_ref : ''
    if (callbackRef) {
      claimAuthorization(callbackRef)
      return
    }
    try {
      const candidate = typeof options?.url === 'string' ? decodeURIComponent(options.url) : ''
      if (isTrustedAuthorizationURL(candidate)) setUrl(candidate)
      else setError('授权入口无效，请从“我的”页面重新开始')
    } catch {
      setError('授权入口编码无效，请从“我的”页面重新开始')
    }
  })

  useUnload(() => {
    pageAlive.current = false
    lifecycleEpoch.current += 1
    claimRun.current += 1
  })

  if (exchanging) {
    return <View className="page"><View className="card"><Text className="section-title">正在完成授权</Text><Text className="muted">正在用当前微信授权事务领取 MateLink 会话。</Text></View></View>
  }

  if (error || !url) {
    return (
      <View className="page">
        <View className="card">
          <Text className="section-title">Tesla 官方授权</Text>
          <Text className="error">{error || '正在准备官方授权入口…'}</Text>
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
        if (!pageAlive.current) return
        const value = event.detail?.data
        const messages = Array.isArray(value) ? value : [value]
        const payload = bridgePayload(messages[messages.length - 1])
        if (payload?.callbackRef) claimAuthorization(payload.callbackRef)
        else if (payload?.error) setError(`Tesla 授权失败：${payload.error}`)
      }}
    />
  )
}
