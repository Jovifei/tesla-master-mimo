import { BrowserRouter, Routes, Route, NavLink, Navigate, useNavigate, useLocation } from 'react-router-dom'
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
  { path: '/controls', label: '远程控制', icon: 'sliders' },
  { path: '/analysis', label: '分析', icon: 'chart' },
  { path: '/me', label: '我的', icon: 'person' },
] as const

/**
 * Shell v3 (docs/WEB-DESIGN-V3.md §2.1): 56px glass top bar, no sidebar.
 * The dashboard renders its own premise strip and content frame; the
 * other pages keep the padded frame. Surfaces, radii and type follow
 * the v4 warm-baseline unification (see index.css). Shell-level car
 * switching and the live charging pill need page state lifted into
 * the shell — scheduled with the rollout.
 */
function Shell({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const signOut = async () => {
    await logout()
    navigate('/login', { replace: true })
  }
  const isDashboard = location.pathname === '/'
  return (
    <div style={{ minHeight: '100vh', background: '#ede9e3', display: 'flex', flexDirection: 'column' }}>
      <header
        style={{
          height: 56, flexShrink: 0, display: 'flex', alignItems: 'center', gap: 28,
          padding: '0 32px', position: 'sticky', top: 0, zIndex: 40,
          background: 'color-mix(in srgb, #fffdf8 72%, transparent)',
          backdropFilter: 'blur(14px) saturate(180%)',
          borderBottom: '0.5px solid rgba(60,50,35,0.10)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, fontSize: 16, fontWeight: 700, color: '#1c1c1e' }}>
          <span style={{ width: 26, height: 26, borderRadius: 10, background: '#157a86', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: '#fff' }}>
            <Icon name="bolt" size={15} />
          </span>
          MateLink
        </div>
        <nav style={{ display: 'flex', gap: 4 }}>
          {NAV.map(item => (
            <NavLink
              key={item.path}
              to={item.path}
              end={item.path === '/'}
              style={({ isActive }) => ({
                display: 'inline-flex', alignItems: 'center', gap: 7,
                padding: '7px 14px', borderRadius: 999, textDecoration: 'none',
                fontSize: 14, fontWeight: isActive ? 600 : 400,
                color: isActive ? '#0071e3' : '#1c1c1e',
                background: isActive ? 'rgba(0,113,227,0.09)' : 'transparent',
                transition: 'background 160ms ease',
              })}
            >
              <Icon name={item.icon as IconName} size={18} />
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6 }}>
          <button
            onClick={() => void signOut()}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', cursor: 'pointer', color: '#3a3a3c', fontSize: 13.5, fontWeight: 500, padding: '7px 12px', borderRadius: 999 }}
          >
            <Icon name="logout" size={17} />
            退出登录
          </button>
        </div>
      </header>
      {isDashboard ? (
        <main style={{ flex: 1, width: '100%' }}>{children}</main>
      ) : (
        <main style={{ flex: 1, padding: '20px 32px 44px', maxWidth: 1400, margin: '0 auto', width: '100%' }}>{children}</main>
      )}
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
