import { BrowserRouter, Routes, Route, NavLink, Navigate, useNavigate } from 'react-router-dom'
import type { ReactNode } from 'react'
import { loadSession, logout } from './api/matelink'
import Login from './pages/Login'
import AuthComplete from './pages/AuthComplete'
import WebDashboard from './pages/WebDashboard'
import ChargesPage from './pages/ChargesPage'
import DrivesPage from './pages/DrivesPage'
import ControlsPage from './pages/ControlsPage'
import { Icon, type IconName } from './components/viz'
import AnalysisPage from './pages/AnalysisPage'
import MePage from './pages/MePage'

/**
 * MateLink Web shell (W1). The previous TeslaMate-style shell and its
 * pages remain in the tree as legacy code (unrouted) until their
 * sections are migrated onto the production API in W2-W4.
 */

const NAV = [
  { path: '/', label: '仪表盘', icon: 'gauge' },
  { path: '/charges', label: '充电', icon: 'bolt' },
  { path: '/drives', label: '行程', icon: 'route' },
  { path: '/controls', label: '远程控制', icon: 'remote' },
  { path: '/analysis', label: '分析', icon: 'chart' },
  { path: '/me', label: '我的', icon: 'person' },
] as const

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
          background: 'color-mix(in srgb, var(--apple-surface) 72%, transparent)',
          backdropFilter: 'blur(14px)',
          padding: '20px 12px',
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
          position: 'sticky',
          top: 0,
          height: '100vh',
        }}
      >
        <div style={{ fontSize: 17, fontWeight: 700, padding: '4px 10px 16px', display: 'flex', alignItems: 'center', gap: 8 }}>
          <span style={{ width: 26, height: 26, borderRadius: 8, background: 'linear-gradient(135deg, #157a86, #34c759)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#fff' }}>
            <Icon name="bolt" size={15} />
          </span>
          MateLink
        </div>
        {NAV.map(item => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            style={({ isActive }) => ({
              display: 'flex',
              alignItems: 'center',
              gap: 9,
              padding: '8px 10px',
              borderRadius: 10,
              color: isActive ? 'var(--apple-teal)' : 'var(--apple-label-primary)',
              background: isActive ? 'var(--apple-teal-soft)' : 'transparent',
              fontSize: 14,
              fontWeight: isActive ? 600 : 400,
              textDecoration: 'none',
              transition: 'background 160ms ease',
            })}
          >
            <Icon name={item.icon as IconName} size={17} />
            {item.label}
          </NavLink>
        ))}
        <div style={{ marginTop: 'auto', padding: '0 10px' }}>
          <button className="apple-btn-secondary" onClick={() => void signOut()}>退出登录</button>
        </div>
      </aside>
      <main className="page-in" style={{ flex: 1, padding: '26px 30px 40px', maxWidth: 1160, margin: '0 auto', width: '100%' }}>{children}</main>
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
              <ControlsPage />
            </RequireSession>
          }
        />
        <Route
          path="/analysis"
          element={
            <RequireSession>
              <AnalysisPage />
            </RequireSession>
          }
        />
        <Route
          path="/me"
          element={
            <RequireSession>
              <MePage />
            </RequireSession>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
