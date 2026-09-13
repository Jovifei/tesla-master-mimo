import { Button, Checkbox, CheckboxGroup, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, getApiSessionGeneration, JOURVOLT_PRIVACY_VERSION, JOURVOLT_TERMS_VERSION, matelinkApi, type WechatLoginResult } from '../../services/api'
import { clearPendingTeslaAuthorization, readAppSession, readPendingTeslaAuthorization, readPendingWechatLink, type AppSession } from '../../services/session'

const TERMS_VERSION = JOURVOLT_TERMS_VERSION
const PRIVACY_VERSION = JOURVOLT_PRIVACY_VERSION

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return reason instanceof Error ? reason.message : '操作暂时不可用'
}

function errorMessagesForStatus(status: string): string {
  return status === 'expired' ? 'Tesla 授权事务已过期，请重新开始授权' : 'Tesla 授权未完成，请重新开始授权'
}

export default function MePage() {
  const [session, setSession] = useState<AppSession | null>(() => readAppSession(Taro))
  const [linkRequired, setLinkRequired] = useState(() => Boolean(readPendingWechatLink(Taro)))
  const [termsAccepted, setTermsAccepted] = useState(false)
  const [privacyAccepted, setPrivacyAccepted] = useState(false)
  const [authorizationPending, setAuthorizationPending] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const operationEpoch = useRef(0)

  const invalidatePage = () => {
    operationEpoch.current += 1
    setLoading(false)
  }

  const resumePendingAuthorization = async () => {
    const operation = ++operationEpoch.current
    const sessionGeneration = getApiSessionGeneration()
    if (!readPendingTeslaAuthorization(Taro)) {
      setAuthorizationPending(false)
      return
    }
    setAuthorizationPending(true)
    try {
      const status = await matelinkApi.getWechatAuthorizationStatus()
      if (operation !== operationEpoch.current || sessionGeneration !== getApiSessionGeneration()) return
      if (status.status === 'ready') {
        const nextSession = await matelinkApi.claimWechatAuthorization()
        if (operation !== operationEpoch.current || sessionGeneration !== getApiSessionGeneration()) return
        setSession(nextSession)
        setLinkRequired(Boolean(nextSession.linkRequired))
        setAuthorizationPending(false)
        Taro.showToast({ title: 'Tesla 授权成功', icon: 'success' })
      } else if (status.status === 'expired' || status.status === 'failed') {
        clearPendingTeslaAuthorization(Taro)
        setAuthorizationPending(false)
        setError(errorMessagesForStatus(status.status))
      }
    } catch (reason) {
      setError(errorMessage(reason))
    }
  }

  useDidShow(() => {
    const current = readAppSession(Taro)
    setSession(current)
    setLinkRequired(Boolean(current?.linkRequired) || Boolean(readPendingWechatLink(Taro)))
    void resumePendingAuthorization()
  })

  const onConsentChange = (event: { detail: { value: string[] } }) => {
    setTermsAccepted(event.detail.value.includes('terms'))
    setPrivacyAccepted(event.detail.value.includes('privacy'))
  }

  const startWechatSession = async () => {
    if (!termsAccepted || !privacyAccepted) {
      setError('请先勾选服务条款和隐私指引')
      return
    }
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    try {
      const result: WechatLoginResult = await matelinkApi.loginWithWechat({ termsVersion: TERMS_VERSION, privacyVersion: PRIVACY_VERSION })
      if (operation !== operationEpoch.current) return
      if (result.status === 'authenticated') {
        setSession(result.session)
        setLinkRequired(Boolean(result.session.linkRequired))
        Taro.showToast({ title: '微信登录成功', icon: 'success' })
      } else {
        setSession(null)
        setLinkRequired(true)
        Taro.showToast({ title: '请继续完成 Tesla 授权', icon: 'none' })
      }
    } catch (reason) {
      setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const startTeslaAuthorization = async () => {
    if (!termsAccepted || !privacyAccepted) {
      setError('请先勾选服务条款和隐私指引')
      return
    }
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    try {
      const result = await matelinkApi.startTeslaAuthorization({ termsVersion: TERMS_VERSION, privacyVersion: PRIVACY_VERSION })
      if (operation !== operationEpoch.current) return
      const authorizationUrl = result.web_authorization_url || result.authorization_url
      setAuthorizationPending(true)
      const url = `/pages/auth/index?url=${encodeURIComponent(authorizationUrl)}`
      Taro.navigateTo({ url }).catch(() => {
        Taro.setClipboardData({ data: authorizationUrl })
        Taro.showToast({ title: '官方入口已复制，请在浏览器打开', icon: 'none' })
      })
    } catch (reason) {
      setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const logout = async () => {
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    try {
      await matelinkApi.logout()
      if (operation !== operationEpoch.current) return
      setSession(null)
      setLinkRequired(false)
      setTermsAccepted(false)
      setPrivacyAccepted(false)
      setAuthorizationPending(false)
      Taro.showToast({ title: '已退出登录', icon: 'success' })
    } catch (reason) {
      setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  useDidHide(invalidatePage)
  useUnload(invalidatePage)

  return (
    <View className="page">
      <View className="card">
        <Text className="title">我的</Text>
        <Text className="muted">微信身份只用于建立小程序会话。Tesla 授权仍在官方页面完成，令牌只留在服务端。</Text>
        <CheckboxGroup onChange={onConsentChange}>
          <View className="status"><Checkbox value="terms" checked={termsAccepted}>我已阅读并同意服务条款（{TERMS_VERSION}）</Checkbox></View>
          <View className="status"><Checkbox value="privacy" checked={privacyAccepted}>我已阅读并同意隐私指引（{PRIVACY_VERSION}）</Checkbox></View>
        </CheckboxGroup>
        {!session ? <Button className="button" loading={loading} onClick={startWechatSession}>微信登录</Button> : null}
        {session ? <Text className="muted">MateLink 会话已建立{session.expiresAt ? `，有效期至 ${session.expiresAt}` : ''}。</Text> : null}
      </View>

      {linkRequired ? (
        <View className="card">
          <Text className="section-title">需要关联 Tesla 账号</Text>
          <Text className="muted">微信账号尚未绑定 Tesla。点击下面入口后，在 Tesla 官方页面登录并同意只读权限；回流后再完成车辆钥匙确认。</Text>
          <Button className="button" loading={loading} onClick={startTeslaAuthorization}>进入 Tesla 官方授权</Button>
        </View>
      ) : null}

      {authorizationPending ? (
        <View className="card">
          <Text className="section-title">等待 Tesla 授权回流</Text>
          <Text className="muted">完成 Tesla 官方页面后返回本小程序；这里会用原授权事务安全检查并领取会话。</Text>
          <Button className="button" loading={loading} onClick={() => { void resumePendingAuthorization() }}>检查授权状态</Button>
        </View>
      ) : null}

      {session && !linkRequired ? <Button className="button" loading={loading} onClick={startTeslaAuthorization}>重新授权 Tesla</Button> : null}
      {session ? <Button className="button" loading={loading} onClick={logout}>退出登录</Button> : null}
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
