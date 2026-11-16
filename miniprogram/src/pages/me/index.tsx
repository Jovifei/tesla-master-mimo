import { useAppearance } from '../../services/appearance'
import VisualIcon from '../../components/VisualIcon'
import { Button, Checkbox, CheckboxGroup, Text, View } from '@tarojs/components'
import Taro, { useDidHide, useDidShow, useUnload } from '@tarojs/taro'
import { useRef, useState } from 'react'
import { ApiError, ensureWechatSession, getApiOrigin, getApiSessionGeneration, getWechatRecoveryFailure, JOURVOLT_PRIVACY_VERSION, JOURVOLT_TERMS_VERSION, matelinkApi } from '../../services/api'
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
  const appearance = useAppearance()
  const initialSession = readAppSession(Taro)
  const initialConsent = MOCK_FIXTURE_ENABLED ? null : readStoredConsent(Taro)
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
    // Keep the current account display while the session is refreshed, so the
    // hub does not flash the login state on every show.
    setSession(current => current ?? readAppSession(Taro))
    setRestoring(true)
    void ensureWechatSession().then(() => {
      if (!pageVisible.current || operation !== operationEpoch.current) return
      const current = readAppSession(Taro)
      setSession(current)
      setRestoring(false)
      if (!current && getWechatRecoveryFailure()) setError(errorMessage(getWechatRecoveryFailure()))
      const consent = !MOCK_FIXTURE_ENABLED && !consentBlocked.current && readStoredConsent(Taro) !== null
      setTermsAccepted(consent); setPrivacyAccepted(consent)
      if (!MOCK_FIXTURE_ENABLED && readPendingTeslaAuthorization(Taro)) void resumePendingAuthorization()
      else { setAuthorizationPending(false); void loadWechatBinding() }
    })
  }
  useSessionRecoveryReload(() => { if (pageVisible.current) showPage() })
  useDidShow(showPage)

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

  const openLegal = (kind: 'terms' | 'privacy') => {
    void Taro.navigateTo({ url: `/pages/legal/index?kind=${kind}` })
  }

  useDidHide(invalidatePage)
  useUnload(invalidatePage)

  const navGeo = getAppNavGeometry()
  return (
    <View className={`page more-page ${appearance.className}${session ? '' : ' minimal-login'}`}>
      <View className="app-toolbar" style={{ paddingTop: `${navGeo.navBarTop}px`, paddingRight: `${navGeo.capsuleRightPadding}px` }}><Text className="title">更多</Text></View>
      {MOCK_FIXTURE_ENABLED ? <Text className="muted">合成数据模式——账号状态为模拟，授权流程不可用；全部记录为 source=mock_fixture 的测试数据。</Text> : null}
      {!session ? <View className="login-welcome"><Text className="title">每一程，都有记录。</Text><Text className="muted">连接你的 Tesla，查看车辆状态、行程与充电。账号登录、官方授权和车辆确认各自独立。</Text></View> : null}
      {session ? <View className="card account-hero compact-account-hero" onClick={() => {
        if (!MOCK_FIXTURE_ENABLED) {
          void Taro.navigateTo({ url: '/pages/tools/index?kind=account' })
        }
      }}>
        <View className="account-heading">
          <View className="visual-heading"><VisualIcon name="account" /><Text className="section-title">账号与授权</Text></View>
          <View className="account-heading-right">
            <Text className={`state-pill ${MOCK_FIXTURE_ENABLED ? '' : 'state-pill-ready'}`}>{MOCK_FIXTURE_ENABLED ? '合成会话' : '会话已建立'}</Text>
            {!MOCK_FIXTURE_ENABLED ? <Text className="account-hero-chevron">›</Text> : null}
          </View>
        </View>
        <Text className="muted compact-account-hint">{MOCK_FIXTURE_ENABLED
          ? '合成会话，未连接真实账号或车辆。'
          : `${wechatBound === true ? '微信快捷进入已绑定' : wechatBound === false ? '微信快捷进入未绑定' : '微信绑定状态未确认'} · 点击管理账号、微信与退出`}</Text>
      </View> : null}

      {!session && !MOCK_FIXTURE_ENABLED ? <View className="card consent-card">
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
        <Button className="button" loading={loading} disabled={loading || restoring || !termsAccepted || !privacyAccepted} onClick={startTeslaAuthorization}>特斯拉账号登录</Button>
        {!MOCK_FIXTURE_ENABLED ? <Button className="button button-secondary" disabled={loading || restoring || authorizationPending || !termsAccepted || !privacyAccepted} onClick={() => { void enterWithWechat() }}>使用已绑定微信进入</Button> : null}
      </View> : null}

      {!MOCK_FIXTURE_ENABLED && authorizationPending ? (
        <View className="card account-pending-card">
          <View className="visual-heading"><VisualIcon name="shield" /><Text className="section-title">等待特斯拉账号授权</Text></View>
          <Text className="muted">请在 Tesla 官方页面完成登录，然后返回本小程序检查授权状态。</Text>
          <View className="account-btn-row">
            <Button className="button" loading={loading} onClick={() => { void resumePendingAuthorization() }}>检查授权状态</Button>
            <Button className="button button-secondary" disabled={loading} onClick={() => { void cancelTeslaAuthorization() }}>取消本次授权</Button>
          </View>
        </View>
      ) : null}

      {session ? <>
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
        <VisualIcon name={item.icon} /><Text className="more-tile-label">{item.label}</Text><Text className="more-tile-chevron">›</Text>
      </Button>)}</View>
      <Text className="more-section-heading">报告与导出</Text>
      <View className="more-grid">{[{ label: '年度报告', kind: 'report', icon: 'chart' as const }, { label: '导出数据', kind: 'export', icon: 'update' as const }].map(item => <Button className="more-action more-tile" key={item.kind} onClick={() => Taro.navigateTo({ url: `/pages/tools/index?kind=${item.kind}` })}><VisualIcon name={item.icon} /><Text className="more-tile-label">{item.label}</Text><Text className="more-tile-chevron">›</Text></Button>)}</View>
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
          if (!MOCK_FIXTURE_ENABLED && current?.userId && current.apiOrigin) {
            try { requestDataStatus(Taro, current.userId, current.apiOrigin) } catch { /* navigation remains available */ }
          }
          void Taro.switchTab({ url: '/pages/vehicle/index' })
        } else void Taro.navigateTo({ url: `/pages/tools/index?kind=${item.kind}` })
      }}><VisualIcon name={item.icon} /><Text className="more-system-label">{item.label}</Text><Text className="more-system-chevron">›</Text></Button>)}</View>
</> : null}
      {error ? <Text className="error">{error}</Text> : null}
    </View>
  )
}
