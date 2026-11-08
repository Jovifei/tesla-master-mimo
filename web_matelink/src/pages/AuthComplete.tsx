import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ApiError, exchangeTicket } from '../api/matelink'

/**
 * Ticket intake on the web origin. If an authorization flow ever
 * lands here directly with ?ticket= (or ?error=), the exchange runs
 * automatically; otherwise it points back to the login page, whose
 * paste box accepts the callback link from the app-link host.
 */
export default function AuthComplete() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const ran = useRef(false)
  const [message, setMessage] = useState('正在完成登录…')

  useEffect(() => {
    if (ran.current) return
    ran.current = true
    const error = params.get('error')
    const ticket = params.get('ticket')
    if (error) {
      setMessage(`Tesla 授权未完成：${error}。请返回登录页重试。`)
      return
    }
    if (!ticket) {
      setMessage('这个页面没有收到登录票据。请回到登录页，粘贴授权完成页面的完整链接来完成登录。')
      return
    }
    exchangeTicket(ticket)
      .then(() => navigate('/', { replace: true }))
      .catch((reason: unknown) => {
        setMessage(reason instanceof ApiError ? `完成登录失败：${reason.message}（票据 2 分钟内有效，过期请重新授权）` : '完成登录失败，请重新授权。')
      })
  }, [navigate, params])

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24 }}>
      <div className="apple-card" style={{ maxWidth: 420, width: '100%', textAlign: 'center' }}>
        <p style={{ fontSize: 15, lineHeight: 1.7 }}>{message}</p>
        <p style={{ marginTop: 16 }}>
          <Link className="text-teal" to="/login">返回登录页</Link>
        </p>
      </div>
    </div>
  )
}
