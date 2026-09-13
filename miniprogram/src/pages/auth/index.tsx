import { Button, Text, View, WebView } from '@tarojs/components'
import Taro, { useLoad } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, matelinkApi } from '../../services/api'

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return reason instanceof Error ? reason.message : 'Tesla 授权暂时不可用'
}

function bridgePayload(value: unknown): { ticket: string; error: string } | null {
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
    ticket: typeof record.ticket === 'string' ? record.ticket : '',
    error: typeof record.error === 'string' ? record.error : '',
  }
}

export default function AuthPage() {
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [exchanging, setExchanging] = useState(false)
  const exchangedTicket = useRef<string | null>(null)

  const exchangeTicket = (ticket: string) => {
    if (!ticket || exchangedTicket.current === ticket) return
    exchangedTicket.current = ticket
    setExchanging(true)
    void matelinkApi.exchangeTeslaTicket(ticket)
      .then(() => {
        Taro.showToast({ title: 'Tesla 授权成功', icon: 'success' })
        Taro.navigateBack()
      })
      .catch(reason => {
        exchangedTicket.current = null
        setError(errorMessage(reason))
      })
      .finally(() => setExchanging(false))
  }

  useLoad(options => {
    const ticket = typeof options?.ticket === 'string' ? options.ticket : ''
    if (ticket) {
      exchangeTicket(ticket)
      return
    }
    try {
      const candidate = typeof options?.url === 'string' ? decodeURIComponent(options.url) : ''
      if (candidate.startsWith('https://')) setUrl(candidate)
      else setError('授权入口无效，请从“我的”页面重新开始')
    } catch {
      setError('授权入口编码无效，请从“我的”页面重新开始')
    }
  })

  if (exchanging) {
    return <View className="page"><View className="card"><Text className="section-title">正在完成授权</Text><Text className="muted">正在把一次性授权结果安全交换为 MateLink 会话。</Text></View></View>
  }

  if (error || !url) {
    return (
      <View className="page">
        <View className="card">
          <Text className="section-title">Tesla 官方授权</Text>
          <Text className="error">{error || '正在准备官方授权入口…'}</Text>
          <Button className="button" onClick={() => Taro.navigateBack()}>返回</Button>
        </View>
      </View>
    )
  }

  return (
    <WebView
      src={url}
      onMessage={event => {
        const value = event.detail?.data
        const messages = Array.isArray(value) ? value : [value]
        const payload = bridgePayload(messages[messages.length - 1])
        if (payload?.ticket) exchangeTicket(payload.ticket)
        else if (payload?.error) setError(`Tesla 授权失败：${payload.error}`)
      }}
    />
  )
}
