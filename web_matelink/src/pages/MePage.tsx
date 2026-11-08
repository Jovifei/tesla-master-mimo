import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, claimInvite, getCars, loadSession, logout } from '../api/matelink'
import type { WebCar } from '../api/matelink-types'

export const CONSENT_VERSION = '2026-08-21'

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

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <h1 style={{ fontSize: 28, fontWeight: 700 }}>我的</h1>

      <div className="apple-card">
        <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>账号</h2>
        <p className="text-secondary tabular" style={{ fontSize: 14, lineHeight: 1.8 }}>
          用户 ID：{session?.userId ?? '--'}
          <br />
          登录方式：Tesla 官方授权（OAuth）。MateLink 不会收集或保存你的 Tesla 密码。
          <br />
          已同意条款版本：{CONSENT_VERSION}（用户协议与隐私政策）
        </p>
      </div>

      <div className="apple-card">
        <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 12 }}>我的车辆</h2>
        {cars.length ? (
          cars.map(car => (
            <div key={car.id} style={{ display: 'flex', justifyContent: 'space-between', padding: '8px 0', borderTop: '0.5px solid var(--apple-separator)', fontSize: 14 }}>
              <span style={{ fontWeight: 600 }}>{car.name}</span>
              <span className="text-secondary">{car.model ?? ''}</span>
            </div>
          ))
        ) : (
          <p className="text-secondary" style={{ fontSize: 14 }}>暂无车辆。</p>
        )}
      </div>

      <div className="apple-card">
        <h2 style={{ fontSize: 16, fontWeight: 600, marginBottom: 8 }}>邀请码</h2>
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
          <p className={inviteState.kind === 'error' ? 'text-error' : 'text-teal'} style={{ fontSize: 13, marginTop: 10 }}>
            {inviteState.text}
          </p>
        ) : null}
      </div>

      <div className="apple-card">
        <button className="apple-btn-secondary" style={{ color: 'var(--apple-error)' }} onClick={() => void handleLogout()}>
          退出登录
        </button>
      </div>
    </div>
  )
}
