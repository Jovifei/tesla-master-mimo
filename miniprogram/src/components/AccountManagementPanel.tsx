import { Button, Checkbox, CheckboxGroup, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useEffect, useRef, useState } from 'react'
import VisualIcon from './VisualIcon'
import {
  ApiError,
  getApiOrigin,
  getApiSessionGeneration,
  isWechatRecoveryEnabledForAccount,
  JOURVOLT_PRIVACY_VERSION,
  JOURVOLT_TERMS_VERSION,
  matelinkApi,
} from '../services/api'
import { readAppSession, readPendingTeslaAuthorization, type AppSession } from '../services/session'
import { CONSENT_STORAGE_KEY, readStoredConsent, writeStoredConsent } from '../domain/legal'
import { MOCK_FIXTURE_ENABLED } from '../services/mock_fixture'

const TERMS_VERSION = JOURVOLT_TERMS_VERSION
const PRIVACY_VERSION = JOURVOLT_PRIVACY_VERSION

function errorMessage(reason: unknown): string {
  if (reason instanceof ApiError) return reason.message
  return '操作暂时不可用，请稍后重试'
}

function errorMessagesForStatus(status: string): string {
  switch (status) {
    case 'expired': return 'Tesla 授权事务已过期，请重新开始授权'
    case 'cancelled': return 'Tesla 授权已取消，请重新开始授权'
    case 'failed': return 'Tesla 授权未完成，请重新开始授权'
    case 'consumed': return '本次授权已使用，请重新登录特斯拉账号'
    default: return 'Tesla 授权状态尚未确认，请稍后重新检查'
  }
}

/**
 * Full account management, used by the account subpage. The More hub keeps
 * only a compact status row and login entry; reauthorization, WeChat binding,
 * consent and destructive actions live here so the hub stays a navigation hub.
 */
export default function AccountManagementPanel({
  onSessionChanged,
  sessionUserId,
}: {
  onSessionChanged?: () => void
  /** Lets the host page re-check binding after a cold-start session restore. */
  sessionUserId?: string | null
}) {
  const [session, setSession] = useState<AppSession | null>(() => readAppSession(Taro))
  const [termsAccepted, setTermsAccepted] = useState(() => !MOCK_FIXTURE_ENABLED && readStoredConsent(Taro) !== null)
  const [privacyAccepted, setPrivacyAccepted] = useState(() => !MOCK_FIXTURE_ENABLED && readStoredConsent(Taro) !== null)
  const [authorizationPending, setAuthorizationPending] = useState(false)
  const [loading, setLoading] = useState(false)
  const [wechatBound, setWechatBound] = useState<boolean | null>(null)
  const [error, setError] = useState<string | null>(null)
  const operationEpoch = useRef(0)
  const bindingEpoch = useRef(0)
  const pageVisible = useRef(false)
  const consentBlocked = useRef(false)
  const bindingLoadedFor = useRef<string | null>(null)

  const invalidatePage = () => {
    operationEpoch.current += 1
    bindingEpoch.current += 1
    pageVisible.current = false
    bindingLoadedFor.current = null
    setLoading(false)
  }

  const captureScope = () => ({
    pending: readPendingTeslaAuthorization(Taro),
    session: readAppSession(Taro),
    generation: getApiSessionGeneration(),
  })
  type AuthorizationScope = ReturnType<typeof captureScope>
  const sameScope = (scope: AuthorizationScope) => {
    const current = captureScope()
    return scope.generation === current.generation && scope.session?.userId === current.session?.userId &&
      scope.session?.accessToken === current.session?.accessToken && scope.session?.apiOrigin === current.session?.apiOrigin &&
      scope.pending?.transactionId === current.pending?.transactionId && scope.pending?.clientProof === current.pending?.clientProof &&
      scope.pending?.apiOrigin === current.pending?.apiOrigin
  }

  const loadWechatBinding = async (force = false) => {
    const current = readAppSession(Taro), generation = getApiSessionGeneration(), operation = ++bindingEpoch.current, pageOperation = operationEpoch.current
    if (!current?.userId || MOCK_FIXTURE_ENABLED) { setWechatBound(null); return }
    const key = `${current.userId}:${generation}`
    if (!force && bindingLoadedFor.current === key) return
    bindingLoadedFor.current = key
    const active = () => {
      const latest = readAppSession(Taro)
      return pageVisible.current && operation === bindingEpoch.current && pageOperation === operationEpoch.current && generation === getApiSessionGeneration() &&
        latest?.userId === current.userId && latest?.apiOrigin === current.apiOrigin && !readPendingTeslaAuthorization(Taro)
    }
    try {
      const binding = await matelinkApi.getWechatBinding()
      if (active()) setWechatBound(binding.bound)
    } catch {
      if (active()) {
        bindingLoadedFor.current = null
        setWechatBound(null)
      }
    }
  }

  const resumePendingAuthorization = async () => {
    if (MOCK_FIXTURE_ENABLED) return
    const operation = ++operationEpoch.current
    const scope = captureScope()
    if (!scope.pending) { setAuthorizationPending(false); return }
    setAuthorizationPending(true)
    setLoading(true)
    setError(null)
    try {
      const status = await matelinkApi.getTeslaAuthorizationStatus()
      if (operation !== operationEpoch.current || !sameScope(scope)) return
      if (status.status === 'ready') {
        const nextSession = await matelinkApi.claimTeslaAuthorization()
        const current = readAppSession(Taro)
        if (operation !== operationEpoch.current || getApiSessionGeneration() !== scope.generation + 1 ||
            current?.userId !== nextSession.userId || current?.accessToken !== nextSession.accessToken ||
            current?.apiOrigin !== nextSession.apiOrigin || readPendingTeslaAuthorization(Taro)) return
        setSession(current)
        setAuthorizationPending(false)
        onSessionChanged?.()
        void loadWechatBinding()
        Taro.showToast({ title: '特斯拉账号登录成功', icon: 'success' })
      } else if (status.status !== 'pending') {
        setError(errorMessagesForStatus(status.status))
      }
    } catch (reason) {
      if (operation !== operationEpoch.current || !sameScope(scope)) return
      setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const refresh = () => {
    pageVisible.current = true
    const current = readAppSession(Taro)
    setSession(current)
    const consent = !MOCK_FIXTURE_ENABLED && !consentBlocked.current && readStoredConsent(Taro) !== null
    setTermsAccepted(consent)
    setPrivacyAccepted(consent)
    if (!MOCK_FIXTURE_ENABLED && readPendingTeslaAuthorization(Taro)) void resumePendingAuthorization()
    else {
      setAuthorizationPending(false)
      void loadWechatBinding()
    }
  }

  useEffect(() => { refresh() }, [])
  useEffect(() => { if (sessionUserId) refresh() }, [sessionUserId])
  useDidShow(refresh)
  useDidHide(invalidatePage)
  useUnload(invalidatePage)

  const bindWechat = async () => {
    if (!pageVisible.current || loading || authorizationPending || MOCK_FIXTURE_ENABLED) return
    const scope = captureScope()
    if (!scope.session?.userId || scope.pending) return
    const operation = ++operationEpoch.current
    bindingEpoch.current += 1
    setLoading(true); setError(null)
    try {
      const prepared = await matelinkApi.prepareWechatBinding()
      const confirmationScope = captureScope()
      if (!pageVisible.current || operation !== operationEpoch.current || confirmationScope.generation !== scope.generation ||
          confirmationScope.session?.userId !== scope.session?.userId || confirmationScope.session?.apiOrigin !== scope.session?.apiOrigin || confirmationScope.pending) return
      const confirmed = await Taro.showModal({ title: '绑定当前微信？', content: '绑定后可用当前微信快捷进入此账号，随时可在这里解绑。', confirmText: '确认绑定' })
      if (!confirmed.confirm || !pageVisible.current || operation !== operationEpoch.current || !sameScope(confirmationScope)) return
      await matelinkApi.bindWechat(prepared.bindingVersion)
      const current = readAppSession(Taro)
      if (operation !== operationEpoch.current || !pageVisible.current || getApiSessionGeneration() !== scope.generation ||
          current?.userId !== scope.session?.userId || current?.apiOrigin !== scope.session?.apiOrigin || readPendingTeslaAuthorization(Taro)) return
      setWechatBound(true)
      Taro.showToast({ title: '微信已绑定', icon: 'success' })
    } catch (reason) {
      if (operation === operationEpoch.current && pageVisible.current) {
        setError(errorMessage(reason))
        const current = readAppSession(Taro)
        if (reason instanceof ApiError && reason.code === 'wechat_recovery_storage_failed' && getApiSessionGeneration() === scope.generation &&
            current?.userId === scope.session?.userId && current?.apiOrigin === scope.session?.apiOrigin && !readPendingTeslaAuthorization(Taro)) {
          setWechatBound(null)
          await loadWechatBinding(true)
        }
      }
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const unbindWechat = async () => {
    if (!pageVisible.current || loading || authorizationPending || MOCK_FIXTURE_ENABLED) return
    const scope = captureScope()
    if (!scope.session?.userId || scope.pending) return
    const beforeConfirmation = operationEpoch.current
    const confirmed = await Taro.showModal({ title: '解绑微信？', content: '解绑后不能再用微信快捷进入；微信进入的会话会退出。', confirmText: '确认解绑' })
    if (!confirmed.confirm || !pageVisible.current || operationEpoch.current !== beforeConfirmation || !sameScope(scope)) return
    const operation = ++operationEpoch.current
    bindingEpoch.current += 1
    setLoading(true); setError(null)
    try {
      await matelinkApi.unbindWechat()
      if (operation !== operationEpoch.current || !pageVisible.current) return
      const current = readAppSession(Taro)
      bindingLoadedFor.current = null
      setSession(current)
      setWechatBound(current ? false : null)
      if (!current) { setTermsAccepted(false); setPrivacyAccepted(false) }
      onSessionChanged?.()
      Taro.showToast({ title: current ? '微信已解绑' : '微信已解绑，请重新登录', icon: 'none' })
    } catch (reason) {
      if (operation === operationEpoch.current && pageVisible.current) {
        const current = readAppSession(Taro)
        setSession(current)
        setError(errorMessage(reason))
        if (reason instanceof ApiError && reason.code === 'wechat_recovery_storage_failed' && getApiSessionGeneration() === scope.generation &&
            current?.userId === scope.session?.userId && current?.apiOrigin === scope.session?.apiOrigin && !readPendingTeslaAuthorization(Taro)) {
          setWechatBound(null)
          await loadWechatBinding(true)
        } else if (!current) setWechatBound(null)
      }
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const onConsentChange = (event: { detail: { value: string[] } }) => {
    if (MOCK_FIXTURE_ENABLED) return
    const terms = event.detail.value.includes('terms')
    const privacy = event.detail.value.includes('privacy')
    setTermsAccepted(terms)
    setPrivacyAccepted(privacy)
    if (terms && privacy) {
      writeStoredConsent(Taro)
      consentBlocked.current = readStoredConsent(Taro) === null
    } else {
      consentBlocked.current = true
      try { Taro.removeStorageSync(CONSENT_STORAGE_KEY) }
      catch {
        try { Taro.setStorageSync(CONSENT_STORAGE_KEY, null) }
        catch { setError('本机同意状态未能更新，请检查微信存储。') }
      }
    }
  }

  const startTeslaAuthorization = async () => {
    if (MOCK_FIXTURE_ENABLED) return
    if (!termsAccepted || !privacyAccepted) {
      setError('请先勾选服务条款和隐私指引')
      return
    }
    writeStoredConsent(Taro)
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    const scope = captureScope()
    try {
      const result = await matelinkApi.startTeslaAuthorization({ termsVersion: TERMS_VERSION, privacyVersion: PRIVACY_VERSION })
      const current = readAppSession(Taro)
      if (operation !== operationEpoch.current || scope.generation !== getApiSessionGeneration() ||
          scope.session?.userId !== current?.userId || scope.session?.apiOrigin !== current?.apiOrigin) return
      const authorizationUrl = result.authorization_url
      setAuthorizationPending(true)
      const url = `/pages/auth/index?url=${encodeURIComponent(authorizationUrl)}`
      const startedScope = captureScope()
      void Taro.navigateTo({ url }).catch(async () => {
        if (operation !== operationEpoch.current || !sameScope(startedScope)) return
        try {
          await Taro.setClipboardData({ data: authorizationUrl })
          if (operation === operationEpoch.current && sameScope(startedScope))
            Taro.showToast({ title: '官方入口已复制，请在浏览器打开', icon: 'none' })
        } catch {
          if (operation === operationEpoch.current && sameScope(startedScope)) setError('官方入口复制失败，请重试')
        }
      })
    } catch (reason) {
      if (operation === operationEpoch.current) setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const cancelTeslaAuthorization = async () => {
    if (MOCK_FIXTURE_ENABLED) return
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    const scope = captureScope()
    try {
      await matelinkApi.cancelTeslaAuthorization()
      const current = readAppSession(Taro)
      if (operation !== operationEpoch.current || scope.generation !== getApiSessionGeneration() ||
          current?.userId !== scope.session?.userId || current?.accessToken !== scope.session?.accessToken ||
          current?.apiOrigin !== scope.session?.apiOrigin || readPendingTeslaAuthorization(Taro)) return
      setAuthorizationPending(false)
      Taro.showToast({ title: '本次授权已取消', icon: 'success' })
    } catch (reason) {
      if (operation === operationEpoch.current) setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const logout = async () => {
    if (MOCK_FIXTURE_ENABLED) return
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    const generation = getApiSessionGeneration()
    try {
      await matelinkApi.logout()
      if (operation !== operationEpoch.current) return
      if (!readAppSession(Taro) && getApiSessionGeneration() === generation + 1)
        Taro.showToast({ title: '已退出登录', icon: 'success' })
    } catch (reason) {
      if (operation === operationEpoch.current && getApiSessionGeneration() === generation + 1)
        setError(errorMessage(reason))
    } finally {
      if (operation === operationEpoch.current) {
        const current = readAppSession(Taro)
        const consent = !consentBlocked.current && readStoredConsent(Taro) !== null
        setSession(current)
        setWechatBound(null)
        setTermsAccepted(consent)
        setPrivacyAccepted(consent)
        setAuthorizationPending(Boolean(readPendingTeslaAuthorization(Taro)))
        setLoading(false)
        onSessionChanged?.()
      }
    }
  }

  const deleteAccount = async () => {
    if (MOCK_FIXTURE_ENABLED) return
    if (!pageVisible.current || loading) return
    const scope = captureScope(), beforeConfirmation = operationEpoch.current
    if (!scope.session?.userId) return
    const confirmation = await Taro.showModal({
      title: '注销 MateLink 账号？',
      content: '服务端账号、会话和关联车辆数据将被删除。此操作不可撤销；Tesla 官方授权需要随后单独撤销。',
      confirmText: '确认注销',
      confirmColor: '#b42318',
    })
    if (!confirmation.confirm || !pageVisible.current || operationEpoch.current !== beforeConfirmation || !sameScope(scope)) return
    setLoading(true)
    setError(null)
    const operation = ++operationEpoch.current
    try {
      const result = await matelinkApi.deleteAccount()
      if (operation !== operationEpoch.current) return
      try { Taro.removeStorageSync(CONSENT_STORAGE_KEY) } catch { /* best effort */ }
      setSession(null)
      setAuthorizationPending(false)
      setTermsAccepted(false)
      setPrivacyAccepted(false)
      onSessionChanged?.()
      if (result.recoveryStorageWarning) setError('账号已注销，但本机快捷进入设置无法保存，请检查微信存储。')
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
      if (operation === operationEpoch.current) { setSession(readAppSession(Taro)); setError(errorMessage(reason)) }
    } finally {
      if (operation === operationEpoch.current) setLoading(false)
    }
  }

  const openLegal = (kind: 'terms' | 'privacy') => {
    void Taro.navigateTo({ url: `/pages/legal/index?kind=${kind}` })
  }

  let recoveryEnabled = false
  try {
    recoveryEnabled = Boolean(!MOCK_FIXTURE_ENABLED && session?.userId && isWechatRecoveryEnabledForAccount(getApiOrigin(), session.userId))
  } catch { /* no preference */ }

  if (!session) {
    return <View className="card account-card account-logged-out">
      <View className="visual-heading"><VisualIcon name="account" /><Text className="section-title">账号与授权</Text></View>
      <Text className="muted">尚未登录。返回「更多」完成特斯拉账号登录或使用已绑定微信进入。</Text>
      <Button className="button" onClick={() => { void Taro.switchTab({ url: '/pages/me/index' }) }}>特斯拉账号登录</Button>
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  }

  return <View className="account-card">
    <View className="account-heading">
      <View className="visual-heading"><VisualIcon name="account" /><Text className="section-title">账号管理</Text></View>
      <Text className={`state-pill ${MOCK_FIXTURE_ENABLED ? '' : 'state-pill-ready'}`}>{MOCK_FIXTURE_ENABLED ? '合成会话' : '会话已建立'}</Text>
    </View>
    <Text className="muted">已通过 Tesla 官方授权连接。重新授权会打开 Tesla 官方页面；退出只结束当前会话，注销会删除服务端账号数据。</Text>

    {!MOCK_FIXTURE_ENABLED && authorizationPending ? (
      <View className="account-pending-card">
        <View className="visual-heading"><VisualIcon name="shield" /><Text className="section-title">等待特斯拉账号授权</Text></View>
        <Text className="muted">请在 Tesla 官方页面完成登录，然后返回检查授权状态。</Text>
        <View className="account-btn-row">
          <Button className="button" loading={loading} onClick={() => { void resumePendingAuthorization() }}>检查授权状态</Button>
          <Button className="button button-secondary" disabled={loading} onClick={() => { void cancelTeslaAuthorization() }}>取消本次授权</Button>
        </View>
      </View>
    ) : null}

    <View className="account-reauth-consent">
      <View className="legal-links">
        <Text className="legal-link" onClick={() => openLegal('terms')}>查看服务条款</Text>
        <Text className="legal-link" onClick={() => openLegal('privacy')}>查看隐私指引</Text>
      </View>
      <CheckboxGroup onChange={onConsentChange}>
        <View className="consent-row"><Checkbox value="terms" checked={termsAccepted}>同意服务条款（{TERMS_VERSION}）</Checkbox></View>
        <View className="consent-row"><Checkbox value="privacy" checked={privacyAccepted}>同意隐私指引（{PRIVACY_VERSION}）</Checkbox></View>
      </CheckboxGroup>
    </View>

    <View className="account-btn-row">
      <Button className="button" loading={loading} disabled={loading || !termsAccepted || !privacyAccepted} onClick={startTeslaAuthorization}>重新授权 Tesla</Button>
    </View>

    <View className="account-wechat-box">
      <View className="wechat-box-info">
        <Text className="wechat-box-title">微信快捷进入</Text>
        <Text className={`wechat-box-badge ${wechatBound ? 'bound' : ''}`}>{wechatBound === null ? '未确认' : wechatBound ? '已绑定' : '未绑定'}</Text>
      </View>
      <Text className="wechat-box-muted">{wechatBound === null ? '微信绑定状态暂未确认' : wechatBound ? '此账号已绑定微信' : '此账号尚未绑定微信'}{recoveryEnabled ? '，本机已启用快捷进入。' : '。'}</Text>
      <View className="wechat-box-actions">
        <Button className="button button-secondary" disabled={loading || authorizationPending} onClick={() => { void bindWechat() }}>{wechatBound ? '启用当前微信快捷进入' : '绑定当前微信'}</Button>
        {wechatBound ? <Button className="button button-secondary" disabled={loading || authorizationPending} onClick={() => { void unbindWechat() }}>解绑微信</Button> : null}
      </View>
    </View>

    <View className="account-btn-row account-danger-row">
      <Button className="button button-secondary" disabled={loading} onClick={logout}>退出登录</Button>
      <Button className="button button-danger" disabled={loading} onClick={() => { void deleteAccount() }}>注销账号</Button>
    </View>
    {error ? <Text className="error">{error}</Text> : null}
  </View>
}