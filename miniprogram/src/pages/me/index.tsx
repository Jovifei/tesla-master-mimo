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
  switch (status) {
    case 'expired': return 'Tesla 授权事务已过期，请重新开始授权'
    case 'cancelled': return 'Tesla 授权已取消，请重新开始授权'
    case 'failed': return 'Tesla 授权未完成，请重新开始授权'
    case 'claimed': return '授权已领取，请点击微信登录恢复会话'
    default: return `Tesla 授权状态：${status}`
  }
}

export default function MePage() {
  const initialSession = readAppSession(Taro)
  const [session, setSession] = useState<AppSession | null>(initialSession)
  const [linkRequired, setLinkRequired] = useState(() => Boolean(readPendingWechatLink(Taro)))
  const [termsAccepted, setTermsAccepted] = useState(Boolean(initialSession))
  const [privacyAccepted, setPrivacyAccepted] = useState(Boolean(initialSession))
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
        // claim intentionally advances the API session generation after the
        // server has committed; the claim result itself owns the new session.
        if (operation !== operationEpoch.current) return
        setSession(nextSession)
        setLinkRequired(Boolean(nextSession.linkRequired))
        setAuthorizationPending(false)
        Taro.showToast({ title: 'Tesla 授权成功', icon: 'success' })
      } else if (status.status === 'expired' || status.status === 'failed' || status.status === 'cancelled') {
        clearPendingTeslaAuthorization(Taro)
        setAuthorizationPending(false)
        setError(errorMessagesForStatus(status.status))
      } else if (status.status === 'claimed') {
        setAuthorizationPending(false)
        setError('授权已领取，请点击微信登录恢复会话')
      } else if (status.status === 'pending') {
        setAuthorizationPending(true)
        setError(null)
      } else {
        setAuthorizationPending(false)
        setError(errorMessagesForStatus(status.status))
      }
    } catch (reason) {
      if (operation !== operationEpoch.current || sessionGeneration !== getApiSessionGeneration()) return
      if (reason instanceof ApiError && (reason.code === 'wechat_authorization_expired' || reason.code === 'wechat_authorization_cancelled' || reason.code === 'wechat_authorization_failed')) {
        clearPendingTeslaAuthorization(Taro)
        setAuthorizationPending(false)
      }
      setError(errorMessage(reason))
    }
  }

  useDidShow(() => {
    const current = readAppSession(Taro)
    setSession(current)
    if (current) {
      setTermsAccepted(true)
      setPrivacyAccepted(true)
    }
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
      if (operation === operationEpoch.current) setError(errorMessage(reason))
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
        if (operation !== operationEpoch.current) return
        Taro.setClipboardData({ data: authorizationUrl })
        Taro.showToast({ title: '官方入口已复制，请在浏览器打开', icon: 'none' })
      })
    } catch (reason) {
      if (operation === operationEpoch.current) setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const cancelTeslaAuthorization = async () => {
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    try {
      await matelinkApi.cancelWechatAuthorization()
      if (operation !== operationEpoch.current) return
      setAuthorizationPending(false)
      Taro.showToast({ title: '本次授权已取消', icon: 'success' })
    } catch (reason) {
      if (operation === operationEpoch.current) setError(errorMessage(reason))
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
      if (operation === operationEpoch.current) setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const deleteAccount = async () => {
    const confirmation = await Taro.showModal({
      title: '注销 MateLink 账号？',
      content: '服务端账号、会话和关联车辆数据将被删除。此操作不可撤销；Tesla 官方授权需要随后单独撤销。',
      confirmText: '确认注销',
      confirmColor: '#b42318',
    })
    if (!confirmation.confirm) return
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    try {
      const result = await matelinkApi.deleteAccount()
      if (operation !== operationEpoch.current) return
      setSession(null)
      setLinkRequired(false)
      setAuthorizationPending(false)
      setTermsAccepted(false)
      setPrivacyAccepted(false)
      Taro.showToast({ title: '账号已注销', icon: 'success' })
      if (result.teslaConsentRevokeUrl) {
        const revoke = await Taro.showModal({
          title: '撤销 Tesla 授权',
          content: 'MateLink 账号已删除。建议继续前往 Tesla 官方页面撤销第三方授权。',
          confirmText: '前往 Tesla',
        })
        if (revoke.confirm && operation === operationEpoch.current) {
          const url = `/pages/auth/index?url=${encodeURIComponent(result.teslaConsentRevokeUrl)}`
          await Taro.navigateTo({ url })
        }
      }
    } catch (reason) {
      if (operation === operationEpoch.current) setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const openLegal = (kind: 'terms' | 'privacy') => {
    void Taro.navigateTo({ url: `/pages/legal/index?kind=${kind}` })
  }

  useDidHide(invalidatePage)
  useUnload(invalidatePage)

  return (
    <View className="page">
      <View className="card account-hero">
        <View className="account-heading">
          <View>
            <Text className="eyebrow">ACCOUNT & PRIVACY</Text>
            <Text className="title">我的</Text>
          </View>
          <Text className={`state-pill ${session ? 'state-pill-ready' : ''}`}>{session ? '会话已建立' : '尚未登录'}</Text>
        </View>
        <Text className="muted">微信身份只用于建立小程序会话。Tesla 授权仍在官方页面完成，令牌只留在服务端。</Text>
      </View>

      <View className="card consent-card">
        <Text className="section-title">授权前确认</Text>
        <Text className="muted">请先阅读并确认以下文件。点击蓝色文字可查看完整内容。</Text>
        <View className="legal-links">
          <Text className="legal-link" onClick={() => openLegal('terms')}>查看服务条款</Text>
          <Text className="legal-link" onClick={() => openLegal('privacy')}>查看隐私指引</Text>
        </View>
        <CheckboxGroup onChange={onConsentChange}>
          <View className="consent-row"><Checkbox value="terms" checked={termsAccepted}>同意服务条款（{TERMS_VERSION}）</Checkbox></View>
          <View className="consent-row"><Checkbox value="privacy" checked={privacyAccepted}>同意隐私指引（{PRIVACY_VERSION}）</Checkbox></View>
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
          <Button className="button button-secondary" disabled={loading} onClick={() => { void cancelTeslaAuthorization() }}>取消本次授权</Button>
        </View>
      ) : null}

      {session ? (
        <View className="card">
          <Text className="section-title">账号管理</Text>
          <Text className="muted">退出只结束当前会话；注销会请求服务端删除账号关联数据。</Text>
          {!linkRequired ? <Button className="button" loading={loading} onClick={startTeslaAuthorization}>重新授权 Tesla</Button> : null}
          <Button className="button button-secondary" disabled={loading} onClick={logout}>退出登录</Button>
          <Button className="button button-danger" disabled={loading} onClick={() => { void deleteAccount() }}>注销账号</Button>
        </View>
      ) : null}
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
