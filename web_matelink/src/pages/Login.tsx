import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError, extractTicket, exchangeTicket, startTeslaAuthorization } from '../api/matelink'

/**
 * Web login: Tesla OAuth is the only credential path — the web app
 * never sees a Tesla password. The client asks the backend to
 * return the one-shot login ticket to this origin's /auth/complete
 * (honored when the origin is on the backend allowlist, which the
 * production web origin and the localhost dev origin are). The
 * paste box below remains as the fallback for any other origin.
 */
export default function Login() {
  const navigate = useNavigate()
  const [starting, setStarting] = useState(false)
  const [paste, setPaste] = useState('')
  const [exchanging, setExchanging] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const begin = async () => {
    setError(null)
    setStarting(true)
    try {
      const { authorizationUrl } = await startTeslaAuthorization()
      window.location.assign(authorizationUrl)
    } catch (reason) {
      setError(reason instanceof ApiError ? reason.message : '无法发起 Tesla 授权，请稍后重试。')
      setStarting(false)
    }
  }

  const completeWithPaste = async () => {
    setError(null)
    const ticket = extractTicket(paste)
    if (!ticket) {
      setError('没有在粘贴内容里找到有效的登录票据，请粘贴授权完成后地址栏里的完整链接。')
      return
    }
    setExchanging(true)
    try {
      await exchangeTicket(ticket)
      setPaste('')
      navigate('/', { replace: true })
    } catch (reason) {
      setError(reason instanceof ApiError ? `完成登录失败：${reason.message}（票据 2 分钟内有效，过期请重新授权）` : '完成登录失败，请重新授权。')
      setExchanging(false)
    }
  }

  return (
    <div className="ml-login">
      <div className="apple-card ml-login-card">
        <div className="ml-login-mark" aria-hidden>M</div>
        <h1 style={{ fontSize: 28, fontWeight: 700, marginBottom: 8 }}>MateLink</h1>
        <p className="text-secondary" style={{ fontSize: 14, lineHeight: 1.6, marginBottom: 20 }}>
          使用 Tesla 官方授权登录。MateLink 不会接触你的 Tesla 账号密码；授权经 Tesla 官网完成，可随时在 Tesla 账号里撤销。
        </p>
        <button className="apple-btn-primary" style={{ width: '100%' }} disabled={starting} onClick={() => void begin()}>
          {starting ? '正在打开 Tesla 授权…' : '使用 Tesla 账号登录'}
        </button>
        <p className="text-tertiary" style={{ fontSize: 12, lineHeight: 1.6, marginTop: 12 }}>
          点击即表示同意《服务条款》与《隐私政策》（版本 2026-08-21，与 App、小程序相同）。
        </p>

        <details>
          <summary>授权后没有自动跳回？</summary>
          <p className="text-secondary" style={{ fontSize: 13, lineHeight: 1.6, marginBottom: 12 }}>
            在已登记的网页地址上，授权完成后会自动跳回并完成登录。若浏览器停在了“授权成功，正在返回 MateLink…”页面，把那个页面地址栏里的完整链接粘贴到这里即可。票据 2 分钟内有效且只能用一次。
          </p>
          <textarea
            className="apple-input"
            rows={3}
            value={paste}
            onChange={event => setPaste(event.target.value)}
            aria-label="授权返回链接" autoComplete="off" spellCheck={false} placeholder="粘贴 https://auth.teslalink.joviluma.com/oauth/callback?ticket=… 链接"
          />
          <button className="apple-btn-secondary" style={{ marginTop: 12 }} disabled={exchanging || !paste.trim()} onClick={() => void completeWithPaste()}>
            {exchanging ? '正在完成登录…' : '完成登录'}
          </button>
        </details>

        {error ? <p className="text-error" style={{ fontSize: 13, marginTop: 16, lineHeight: 1.6 }}>{error}</p> : null}
      </div>
    </div>
  )
}
