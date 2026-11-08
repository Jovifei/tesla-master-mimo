import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, claimInvite, getCars, loadSession, logout } from '../api/matelink'
import type { WebCar } from '../api/matelink-types'
import { Icon, TONES, type IconName } from '../components/viz'
import { Panel, V3 } from '../components/v3'

export const CONSENT_VERSION = '2026-08-21'

function IconChip({ name, tone = TONES.teal, size = 32 }: { name: IconName; tone?: string; size?: number }) {
  return (
    <span
      style={{
        width: size,
        height: size,
        borderRadius: size / 3,
        background: 'var(--apple-surface-secondary)',
        color: tone,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        flexShrink: 0,
      }}
    >
      <Icon name={name} size={Math.round(size * 0.52)} />
    </span>
  )
}

function CardTitle({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 10, display: 'flex', alignItems: 'center', gap: 8 }}>
      <span style={{ color: TONES.teal, display: 'inline-flex' }}><Icon name={icon} size={18} /></span>
      {children}
    </h2>
  )
}

function InfoRow({ icon, label, value, first }: { icon: IconName; label: string; value: string; first?: boolean }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '10px 0',
        borderTop: first ? 'none' : '0.5px solid var(--apple-separator)',
      }}
    >
      <IconChip name={icon} size={30} />
      <span style={{ fontSize: 14, fontWeight: 500, flexShrink: 0 }}>{label}</span>
      <span className="text-secondary tabular" style={{ marginLeft: 'auto', fontSize: 13, textAlign: 'right', wordBreak: 'break-all' }}>
        {value}
      </span>
    </div>
  )
}

export default function MePage() {
  const navigate = useNavigate()
  const session = loadSession()
  const [cars, setCars] = useState<WebCar[]>([])
  const [inviteCode, setInviteCode] = useState('')
  const [inviteState, setInviteState] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null)
  const [claiming, setClaiming] = useState(false)

  useEffect(() => {
    getCars().then(setCars).catch(() => setCars([]))
  }, [])

  const claim = async () => {
    const code = inviteCode.trim()
    if (!code) return
    setClaiming(true)
    setInviteState(null)
    try {
      await claimInvite(code)
      setInviteState({ kind: 'ok', text: '邀请码已领取。' })
      setInviteCode('')
    } catch (reason) {
      setInviteState({
        kind: 'error',
        text: reason instanceof ApiError ? `领取失败：${reason.message}` : '领取失败，请稍后重试。',
      })
    } finally {
      setClaiming(false)
    }
  }

  const handleLogout = async () => {
    await logout()
    navigate('/login', { replace: true })
  }

  const avatarChar = session?.userId ? session.userId.charAt(0).toUpperCase() : null

  return (
    <div className="page-in" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <h1 className="hero-title">我的</h1>

      <Panel>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 6 }}>
          <span
            style={{
              width: 60,
              height: 60,
              borderRadius: 30,
              background: V3.teal,
              color: '#fff',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 24,
              fontWeight: 700,
              flexShrink: 0,
            }}
          >
            {avatarChar ?? <Icon name="person" size={28} />}
          </span>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 17, fontWeight: 700 }}>MateLink 账号</div>
            <div className="text-secondary tabular" style={{ fontSize: 12.5, marginTop: 3, wordBreak: 'break-all' }}>
              {session?.userId ?? '--'}
            </div>
          </div>
        </div>
        <InfoRow icon="person" label="用户 ID" value={session?.userId ?? '--'} first />
        <InfoRow icon="key" label="登录方式" value="Tesla 官方授权（OAuth）" />
        <InfoRow icon="shield" label="隐私" value="不会收集或保存你的 Tesla 密码" />
        <InfoRow icon="calendar" label="已同意条款版本" value={`${CONSENT_VERSION}（用户协议与隐私政策）`} />
      </Panel>

      <Panel>
        <CardTitle icon="car">我的车辆</CardTitle>
        {cars.length ? (
          cars.map((car, index) => (
            <div
              key={car.id}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '10px 0',
                borderTop: index === 0 ? 'none' : '0.5px solid var(--apple-separator)',
              }}
            >
              <IconChip name="car" size={36} />
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 14.5, fontWeight: 600 }}>{car.name}</div>
                {car.model ? <div className="text-secondary" style={{ fontSize: 12.5, marginTop: 1 }}>{car.model}</div> : null}
              </div>
              <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center', gap: 6, flexShrink: 0 }}>
                <span style={{ width: 8, height: 8, borderRadius: 4, background: TONES.green, boxShadow: `0 0 0 3px ${TONES.green}22` }} />
                <span className="text-tertiary" style={{ fontSize: 12 }}>已绑定</span>
              </span>
            </div>
          ))
        ) : (
          <p className="text-secondary" style={{ fontSize: 14 }}>暂无车辆。</p>
        )}
      </Panel>

      <Panel>
        <CardTitle icon="key">邀请码</CardTitle>
        <p className="text-secondary" style={{ fontSize: 13, lineHeight: 1.7, marginBottom: 12 }}>
          如果你有邀请码，可以在这里领取绑定到当前账号。
        </p>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <input
            className="apple-input"
            style={{ flex: '1 1 200px' }}
            placeholder="输入邀请码"
            value={inviteCode}
            onChange={event => setInviteCode(event.target.value)}
          />
          <button className="apple-btn-primary" disabled={claiming || !inviteCode.trim()} onClick={() => void claim()}>
            {claiming ? '领取中…' : '领取'}
          </button>
        </div>
        {inviteState ? (
          <p className={inviteState.kind === 'error' ? 'text-error' : 'text-teal'} style={{ fontSize: 13, marginTop: 10, display: 'flex', alignItems: 'center', gap: 7 }}>
            <span style={{ width: 7, height: 7, borderRadius: 4, background: inviteState.kind === 'error' ? 'var(--apple-error)' : TONES.green, flexShrink: 0 }} />
            {inviteState.text}
          </p>
        ) : null}
      </Panel>

      <Panel style={{ padding: '10px 17px' }}>
        <button
          onClick={() => void handleLogout()}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            width: '100%',
            padding: '8px 2px',
            background: 'none',
            border: 'none',
            cursor: 'pointer',
            color: 'var(--apple-error)',
            fontSize: 15,
            fontWeight: 600,
            textAlign: 'left',
          }}
        >
          <Icon name="logout" size={19} />
          退出登录
        </button>
      </Panel>
    </div>
  )
}
