import VisualIcon from '../../components/VisualIcon'
import { Button, Checkbox, CheckboxGroup, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, ensureWechatSession, getApiOrigin, getApiSessionGeneration, getWechatRecoveryFailure, isWechatRecoveryEnabledForAccount, JOURVOLT_PRIVACY_VERSION, JOURVOLT_TERMS_VERSION, matelinkApi } from '../../services/api'
import { readAppSession, readPendingTeslaAuthorization, type AppSession } from '../../services/session'
import { requestDataStatus } from '../../services/data_status_intent'
import { MOCK_FIXTURE_ENABLED } from '../../services/mock_fixture'
import { getAppNavGeometry } from '../../services/nav_geometry'
import { CONSENT_STORAGE_KEY, readStoredConsent, writeStoredConsent } from '../../domain/legal'
import LoadingIndicator from '../../components/LoadingIndicator'
import { useSessionRecoveryReload } from '../../services/use_session_recovery'

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

export default function MePage() {
  const initialSession = readAppSession(Taro)
  const initialConsent = readStoredConsent(Taro)
  const hasConsent = initialConsent !== null
  const [session, setSession] = useState<AppSession | null>(initialSession)
  const [termsAccepted, setTermsAccepted] = useState(hasConsent)
  const [privacyAccepted, setPrivacyAccepted] = useState(hasConsent)
  const [authorizationPending, setAuthorizationPending] = useState(false)
  const [loading, setLoading] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [wechatBound, setWechatBound] = useState<boolean | null>(null)
  const [error, setError] = useState<string | null>(null)
  const operationEpoch = useRef(0)
  const bindingEpoch = useRef(0)
  const pageVisible = useRef(false)
  const consentBlocked = useRef(false)

  const invalidatePage = () => {
    operationEpoch.current += 1
    bindingEpoch.current += 1
    pageVisible.current = false
    setRestoring(false)
    setLoading(false)
  }

  const captureScope = () => ({ pending: readPendingTeslaAuthorization(Taro),
    session: readAppSession(Taro), generation: getApiSessionGeneration() })
  type AuthorizationScope = ReturnType<typeof captureScope>
  const sameScope = (scope: AuthorizationScope) => {
    const current = captureScope()
    return scope.generation === current.generation && scope.session?.userId === current.session?.userId &&
      scope.session?.accessToken === current.session?.accessToken && scope.session?.apiOrigin === current.session?.apiOrigin &&
      scope.pending?.transactionId === current.pending?.transactionId && scope.pending?.clientProof === current.pending?.clientProof &&
      scope.pending?.apiOrigin === current.pending?.apiOrigin
  }

  const loadWechatBinding = async () => {
    const current = readAppSession(Taro), generation = getApiSessionGeneration(), operation = ++bindingEpoch.current, pageOperation = operationEpoch.current
    if (!current?.userId || MOCK_FIXTURE_ENABLED) { setWechatBound(null); return }
    const active = () => {
      const latest = readAppSession(Taro)
      return pageVisible.current && operation === bindingEpoch.current && pageOperation === operationEpoch.current && generation === getApiSessionGeneration() &&
        latest?.userId === current.userId && latest?.apiOrigin === current.apiOrigin && !readPendingTeslaAuthorization(Taro)
    }
    try {
      const binding = await matelinkApi.getWechatBinding()
      if (active()) setWechatBound(binding.bound)
    } catch {
      if (active()) setWechatBound(null)
    }
  }

  const resumePendingAuthorization = async () => {
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

  const showPage = () => {
    pageVisible.current = true
    const operation = ++operationEpoch.current
    setSession(null)
    setWechatBound(null)
    setRestoring(true)
    void ensureWechatSession().then(() => {
      if (!pageVisible.current || operation !== operationEpoch.current) return
      const current = readAppSession(Taro)
      setSession(current)
      setRestoring(false)
      if (!current && getWechatRecoveryFailure()) setError(errorMessage(getWechatRecoveryFailure()))
      const consent = !consentBlocked.current && readStoredConsent(Taro) !== null
      setTermsAccepted(consent); setPrivacyAccepted(consent)
      if (readPendingTeslaAuthorization(Taro)) void resumePendingAuthorization()
      else { setAuthorizationPending(false); void loadWechatBinding() }
    })
  }
  useSessionRecoveryReload(() => { if (pageVisible.current) showPage() })
  useDidShow(showPage)

  const bindWechat = async () => {
    if (!pageVisible.current || loading || restoring || authorizationPending || MOCK_FIXTURE_ENABLED) return
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
          await loadWechatBinding()
        }
      }
    }
    finally { if (operation === operationEpoch.current) setLoading(false) }
  }

  const unbindWechat = async () => {
    if (!pageVisible.current || loading || restoring || authorizationPending || MOCK_FIXTURE_ENABLED) return
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
      setSession(current); setWechatBound(current ? false : null)
      if (!current) { setTermsAccepted(false); setPrivacyAccepted(false) }
      Taro.showToast({ title: current ? '微信已解绑' : '微信已解绑，请重新登录', icon: 'none' })
    } catch (reason) {
      if (operation === operationEpoch.current && pageVisible.current) {
        const current = readAppSession(Taro)
        setSession(current); setError(errorMessage(reason))
        if (reason instanceof ApiError && reason.code === 'wechat_recovery_storage_failed' && getApiSessionGeneration() === scope.generation &&
            current?.userId === scope.session?.userId && current?.apiOrigin === scope.session?.apiOrigin && !readPendingTeslaAuthorization(Taro)) {
          setWechatBound(null)
          await loadWechatBinding()
        } else if (!current) setWechatBound(null)
      }
    } finally { if (operation === operationEpoch.current) setLoading(false) }
  }

  const enterWithWechat = async () => {
    if (!termsAccepted || !privacyAccepted || loading || restoring || authorizationPending || MOCK_FIXTURE_ENABLED) return
    const operation = ++operationEpoch.current
    setLoading(true); setError(null)
    try {
      const next = await matelinkApi.restoreWechatSession()
      if (operation !== operationEpoch.current || !pageVisible.current) return
      const current = readAppSession(Taro)
      if (current?.userId !== next.userId || current?.accessToken !== next.accessToken) return
      setSession(current); void loadWechatBinding()
      Taro.showToast({ title: '已进入账号', icon: 'success' })
    } catch (reason) { if (operation === operationEpoch.current && pageVisible.current) setError(errorMessage(reason)) }
    finally { if (operation === operationEpoch.current) setLoading(false) }
  }

  const onConsentChange = (event: { detail: { value: string[] } }) => {
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
      }
    }
  }

  const deleteAccount = async () => {
    if (!pageVisible.current || loading || restoring) return
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

  useDidHide(invalidatePage)
  useUnload(invalidatePage)

  const navGeo = getAppNavGeometry()
  let recoveryEnabled = false
  try { recoveryEnabled = Boolean(session?.userId && isWechatRecoveryEnabledForAccount(getApiOrigin(), session.userId)) } catch { /* no preference */ }
  return (
    <View className="page more-page">
      <View className="app-toolbar" style={{ paddingTop: `${navGeo.navBarTop}px`, paddingRight: `${navGeo.capsuleRightPadding}px` }}><Text className="title">更多</Text></View>
      {MOCK_FIXTURE_ENABLED ? <Text className="muted">合成数据模式——账号状态为模拟，授权流程不可用；全部记录为 source=mock_fixture 的测试数据。</Text> : null}
      <View className="more-status">
        <VisualIcon name="shield" />
        <View className="more-status-copy">
          <Text className="more-status-title">数据状态</Text>
          <Text className="more-status-description">连接成功后可查看实时车辆数据；统计分析页面需要积累足够的历史记录后才会完整。</Text>
        </View>
      </View>
      <Text className="more-section-heading">数据分析</Text>
      <View className="more-grid">{[
        { label: '统计概览', icon: 'speed' as const, route: '/pages/analysis/index?kind=stats' },
        { label: '电池健康', icon: 'battery' as const, route: '/pages/insights/index?kind=battery' },
        { label: '里程统计', icon: 'distance' as const, route: '/pages/analysis/index?kind=mileage' },
        { label: '行程历史', icon: 'clock' as const, route: '/pages/drives/index' },
        { label: '长途旅程', icon: 'route' as const, route: '/pages/insights/index?kind=longTrips' },
        { label: '能耗', icon: 'chart' as const, route: '/pages/analysis/index?kind=efficiency' },
        { label: '费用分析', icon: 'money' as const, route: '/pages/insights/index?kind=cost' },
        { label: '续航分析', icon: 'route' as const, route: '/pages/insights/index?kind=range' },
        { label: '待机能耗', icon: 'energy' as const, route: '/pages/analysis/index?kind=vampire' },
        { label: '时间线', icon: 'timeline' as const, route: '/pages/analysis/index?kind=timeline' },
      ].map(item => <Button className="more-action more-tile" key={item.label} onClick={() => item.route === '/pages/drives/index' ? Taro.switchTab({ url: item.route }) : Taro.navigateTo({ url: item.route })}>
        <VisualIcon name={item.icon} /><Text className="more-tile-label">{item.label}</Text>
      </Button>)}</View>
      <Text className="more-section-heading">报告与导出</Text>
      <View className="more-grid">{[{ label: '年度报告', kind: 'report', icon: 'chart' as const }, { label: '导出数据', kind: 'export', icon: 'update' as const }].map(item => <Button className="more-action more-tile" key={item.kind} onClick={() => Taro.navigateTo({ url: `/pages/tools/index?kind=${item.kind}` })}><VisualIcon name={item.icon} /><Text className="more-tile-label">{item.label}</Text></Button>)}</View>
      <Text className="more-section-heading">系统</Text>
      <View className="more-system">{[
        { label: '数据状态', kind: 'dataStatus', icon: 'shield' as const },
        { label: '软件更新', kind: 'updates', icon: 'update' as const },
        { label: '哨兵历史', kind: 'sentry', icon: 'shield' as const },
        { label: '设置', kind: 'settings', icon: 'settings' as const },
        { label: '关于', kind: 'about', icon: 'info' as const },
      ].map(item => <Button className="more-action more-system-action" key={item.kind} onClick={() => {
        if (item.kind === 'dataStatus') {
          const current = readAppSession(Taro)
          if (current?.userId && current.apiOrigin) {
            try { requestDataStatus(Taro, current.userId, current.apiOrigin) } catch { /* navigation remains available */ }
          }
          void Taro.switchTab({ url: '/pages/vehicle/index' })
        } else void Taro.navigateTo({ url: `/pages/tools/index?kind=${item.kind}` })
      }}><VisualIcon name={item.icon} /><Text className="more-system-label">{item.label}</Text><Text className="more-system-chevron">›</Text></Button>)}</View>
      <View className="card account-hero">
        <View className="account-heading">
          <View>
            <View className="visual-heading"><VisualIcon name="account" /><Text className="section-title">账号与授权</Text></View>
          </View>
          <Text className={`state-pill ${session ? 'state-pill-ready' : ''}`}>{session ? '会话已建立' : '尚未登录'}</Text>
        </View>
        <Text className="muted">使用特斯拉账号登录，授权在 Tesla 官方页面完成。与 App 使用同一特斯拉账号，可查看该账号已有的车辆和历史数据。</Text>
      </View>

      <View className="card consent-card">
        <View className="visual-heading"><VisualIcon name="shield" /><Text className="section-title">授权前确认</Text></View>
        <Text className="muted">请先阅读并确认以下文件。点击下方链接可查看完整内容。</Text>
        <View className="legal-links">
          <Text className="legal-link" onClick={() => openLegal('terms')}>查看服务条款</Text>
          <Text className="legal-link" onClick={() => openLegal('privacy')}>查看隐私指引</Text>
        </View>
        <CheckboxGroup onChange={onConsentChange}>
          <View className="consent-row"><Checkbox value="terms" checked={termsAccepted}>同意服务条款（{TERMS_VERSION}）</Checkbox></View>
          <View className="consent-row"><Checkbox value="privacy" checked={privacyAccepted}>同意隐私指引（{PRIVACY_VERSION}）</Checkbox></View>
        </CheckboxGroup>
        {restoring ? <LoadingIndicator label="正在恢复会话…" compact /> : null}
        {!session ? <Button className="button" loading={loading} disabled={loading || restoring || !termsAccepted || !privacyAccepted} onClick={startTeslaAuthorization}>特斯拉账号登录</Button> : null}
        {!session && !MOCK_FIXTURE_ENABLED ? <Button className="button button-secondary" disabled={loading || restoring || authorizationPending || !termsAccepted || !privacyAccepted} onClick={() => { void enterWithWechat() }}>使用已绑定微信进入</Button> : null}
        {session ? <Text className="muted">MateLink 会话已建立{session.expiresAt ? `，有效期至 ${session.expiresAt}` : ''}。</Text> : null}
      </View>

      {authorizationPending ? (
        <View className="card">
          <View className="visual-heading"><VisualIcon name="shield" /><Text className="section-title">等待特斯拉账号授权</Text></View>
          <Text className="muted">请在 Tesla 官方页面完成登录，然后返回本小程序检查授权状态。</Text>
          <Button className="button" loading={loading} onClick={() => { void resumePendingAuthorization() }}>检查授权状态</Button>
          <Button className="button button-secondary" disabled={loading} onClick={() => { void cancelTeslaAuthorization() }}>取消本次授权</Button>
        </View>
      ) : null}

      {session ? (
        <View className="card">
          <View className="visual-heading"><VisualIcon name="account" /><Text className="section-title">账号管理</Text></View>
          <Text className="muted">退出只结束当前会话；注销会请求服务端删除账号关联数据。</Text>
          <Button className="button" loading={loading} disabled={loading || !termsAccepted || !privacyAccepted} onClick={startTeslaAuthorization}>重新授权 Tesla</Button>
          <Button className="button button-secondary" disabled={loading || authorizationPending || !termsAccepted || !privacyAccepted} onClick={startTeslaAuthorization}>切换 Tesla 账号</Button>
          {!MOCK_FIXTURE_ENABLED ? <>
            <Text className="muted">{wechatBound === null ? '微信绑定状态暂未确认' : wechatBound ? '此账号已绑定微信' : '此账号尚未绑定微信'}{recoveryEnabled ? '，本机已启用快捷进入。' : '。'}</Text>
            <Button className="button button-secondary" disabled={loading || restoring || authorizationPending} onClick={() => { void bindWechat() }}>{wechatBound ? '启用当前微信快捷进入' : '绑定当前微信'}</Button>
            {wechatBound ? <Button className="button button-secondary" disabled={loading || restoring || authorizationPending} onClick={() => { void unbindWechat() }}>解绑微信</Button> : null}
          </> : null}
          <Button className="button button-secondary" disabled={loading} onClick={logout}>退出登录</Button>
          <Button className="button button-danger" disabled={loading} onClick={() => { void deleteAccount() }}>注销账号</Button>
        </View>
      ) : null}

      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
