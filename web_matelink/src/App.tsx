import { BrowserRouter, Routes, Route, NavLink, Navigate, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { loadSession, logout } from './api/matelink'
import Login from './pages/Login'
import AuthComplete from './pages/AuthComplete'
import WebDashboard from './pages/WebDashboard'
import ChargesPage from './pages/ChargesPage'
import DrivesPage from './pages/DrivesPage'

/**
 * MateLink Web shell (W1). The previous TeslaMate-style shell and its
 * pages remain in the tree as legacy code (unrouted) until their
 * sections are migrated onto the production API in W2-W4.
 */

const NAV = [
  { path: '/', label: '仪表盘', stage: 'W1' },
  { path: '/charges', label: '充电', stage: 'W2' },
  { path: '/drives', label: '行程', stage: 'W2' },
  { path: '/controls', label: '远程控制', stage: 'W3' },
  { path: '/analysis', label: '分析', stage: 'W4' },
  { path: '/me', label: '我的', stage: 'W4' },
] as const

function Placeholder({ title, stage }: { title: string; stage: string }) {
  return (
    <div className="apple-card">
      <h1 style={{ fontSize: 22, fontWeight: 700, marginBottom: 8 }}>{title}</h1>
      <p className="text-secondary" style={{ fontSize: 14, lineHeight: 1.7 }}>
        这一部分在网页版 {stage} 阶段交付（见 docs/WEB-PLAN-20261108.md）。当前 W1 已完成骨架、登录与只读仪表盘。
      </p>
    </div>
  )
}

function Shell({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const signOut = async () => {
    await logout()
    navigate('/login', { replace: true })
  }
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <aside
        style={{
          width: 216,
          flexShrink: 0,
          borderRight: '0.5px solid var(--apple-separator)',
          background: 'var(--apple-surface-secondary)',
          padding: '20px 12px',
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
          position: 'sticky',
          top: 0,
          height: '100vh',
        }}
      >
        <div style={{ fontSize: 17, fontWeight: 700, padding: '4px 10px 16px' }}>MateLink</div>
        {NAV.map(item => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            style={({ isActive }) => ({
              display: 'block',
              padding: '8px 10px',
              borderRadius: 'var(--apple-radius-sm)',
              color: isActive ? 'var(--apple-teal)' : 'var(--apple-label-primary)',
              background: isActive ? 'var(--apple-teal-soft)' : 'transparent',
              fontSize: 14,
              fontWeight: isActive ? 600 : 400,
              textDecoration: 'none',
            })}
          >
            {item.label}
          </NavLink>
        ))}
        <div style={{ marginTop: 'auto', padding: '0 10px' }}>
          <button className="apple-btn-secondary" onClick={() => void signOut()}>退出登录</button>
        </div>
      </aside>
      <main style={{ flex: 1, padding: '24px 28px', maxWidth: 1080, margin: '0 auto', width: '100%' }}>{children}</main>
    </div>
  )
}

function RequireSession({ children }: { children: ReactNode }) {
  if (!loadSession()) return <Navigate to="/login" replace />
  return <Shell>{children}</Shell>
}

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/auth/complete" element={<AuthComplete />} />
        <Route
          path="/"
          element={
            <RequireSession>
              <WebDashboard />
            </RequireSession>
          }
        />
        <Route
          path="/charges"
          element={
            <RequireSession>
              <ChargesPage />
            </RequireSession>
          }
        />
        <Route
          path="/drives"
          element={
            <RequireSession>
              <DrivesPage />
            </RequireSession>
          }
        />
        <Route
          path="/controls"
          element={
            <RequireSession>
              <Placeholder title="远程控制" stage="W3" />
            </RequireSession>
          }
        />
        <Route
          path="/analysis"
          element={
            <RequireSession>
              <Placeholder title="分析" stage="W4" />
            </RequireSession>
          }
        />
        <Route
          path="/me"
          element={
            <RequireSession>
              <Placeholder title="我的" stage="W4" />
            </RequireSession>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
