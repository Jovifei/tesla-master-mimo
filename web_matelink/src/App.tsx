import { BrowserRouter, Routes, Route, NavLink, Navigate, useNavigate, useLocation } from 'react-router-dom'
import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { loadSession, logout, sessionScope, subscribeSession } from './api/matelink'
import Login from './pages/Login'
import AuthComplete from './pages/AuthComplete'
import WebDashboard from './pages/WebDashboard'
import ChargeLevelPage from './pages/ChargeLevelPage'
import ChargesPage from './pages/ChargesPage'
import ChargingStatsPage from './pages/ChargingStatsPage'
import DrivesPage from './pages/DrivesPage'
import DriveStatsPage from './pages/DriveStatsPage'
import EfficiencyPage from './pages/EfficiencyPage'
import BatteryPage from './pages/BatteryPage'
import ProjectedRangePage from './pages/ProjectedRangePage'
import VampirePage from './pages/VampirePage'
import StatisticsPage from './pages/StatisticsPage'
import MileagePage from './pages/MileagePage'
import TemperaturesPage from './pages/TemperaturesPage'
import LocationsPage from './pages/LocationsPage'
import VisitedPage from './pages/VisitedPage'
import UpdatesPage from './pages/UpdatesPage'
import DataInfoPage from './pages/DataInfoPage'
import ControlsPage from './pages/ControlsPage'
import { Icon, type IconName } from './components/viz'
import MePage from './pages/MePage'

/**
 * MateLink Web shell. Navigation groups mirror the TeslaMate
 * reproduction mapping (docs/TESLAMATE-MAPPING.md): 总览 / 充电 /
 * 行程 / 电池 / 数据 groups plus our own 远程控制 and 我的.
 */

type NavItem = { path: string; label: string }
type NavGroup = { label: string; icon: IconName; items: NavItem[] }

const NAV_GROUPS: NavGroup[] = [
  {
    label: '总览',
    icon: 'gauge',
    items: [
      { path: '/', label: '总览' },
      { path: '/charge-level', label: '电量历史' },
    ],
  },
  {
    label: '充电',
    icon: 'bolt',
    items: [
      { path: '/charges', label: '充电记录' },
      { path: '/charging-stats', label: '充电统计' },
    ],
  },
  {
    label: '行程',
    icon: 'route',
    items: [
      { path: '/drives', label: '行程记录' },
      { path: '/drive-stats', label: '行程统计' },
      { path: '/efficiency', label: '效率' },
    ],
  },
  {
    label: '电池',
    icon: 'battery',
    items: [
      { path: '/battery', label: '电池健康' },
      { path: '/projected-range', label: '预计续航' },
      { path: '/vampire', label: '驻车掉电' },
    ],
  },
  {
    label: '数据',
    icon: 'chart',
    items: [
      { path: '/statistics', label: '统计' },
      { path: '/mileage', label: '里程' },
      { path: '/temperatures', label: '温度' },
      { path: '/locations', label: '地点' },
      { path: '/visited', label: '到访地图' },
      { path: '/updates', label: '软件更新' },
      { path: '/data-info', label: '数据概况' },
    ],
  },
]

function GroupMenu({ group }: { group: NavGroup }) {
  const [open, setOpen] = useState(false)
  const location = useLocation()
  const ref = useRef<HTMLDivElement | null>(null)
  const active = group.items.some(item => (item.path === '/' ? location.pathname === '/' : location.pathname.startsWith(item.path)))
  useEffect(() => setOpen(false), [location.pathname])
  useEffect(() => {
    if (!open) return
    const onClick = (event: MouseEvent) => {
      if (ref.current && !ref.current.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [open ])
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button
        onClick={() => setOpen(value => !value)}
        style={{
          display: 'inline-flex', alignItems: 'center', gap: 7,
          padding: '7px 14px', borderRadius: 999, border: 'none', cursor: 'pointer',
          fontSize: 14, fontWeight: active ? 600 : 400, fontFamily: 'inherit',
          color: active ? 'var(--apple-tint)' : 'var(--apple-label-primary)',
          background: active ? 'var(--apple-surface-tertiary)' : 'transparent',
        }}
        aria-haspopup="menu"
        aria-expanded={open}
        onKeyDown={e => { if(e.key === 'Escape') setOpen(false) }}
      >
        <Icon name={group.icon} size={18} />
        {group.label}
        <span style={{ fontSize: 10, color: active ? 'var(--apple-tint)' : '#6e6e73' }}>▾</span>
      </button>
      {open ? (
        <div
          role="menu"
          style={{
            position: 'absolute', top: 'calc(100% + 8px)', left: 0, minWidth: 168, zIndex: 60,
            background: 'var(--apple-surface)', borderRadius: 16, padding: 6,
            boxShadow: '0 12px 34px rgba(43,36,28,0.16)', border: '0.5px solid rgba(60,50,35,0.10)',
          }}
        >
          {group.items.map(item => (
            <NavLink
              key={item.path}
              to={item.path}
              end={item.path === '/'}
              style={({ isActive }) => ({
                display: 'block', padding: '8px 12px', borderRadius: 10, textDecoration: 'none',
                fontSize: 14, fontWeight: isActive ? 600 : 400,
                color: isActive ? 'var(--apple-tint)' : 'var(--apple-label-primary)',
                background: isActive ? 'var(--apple-surface-tertiary)' : 'transparent',
              })}
            >
              {item.label}
            </NavLink>
          ))}
        </div>
      ) : null}
    </div>
  )
}

function Shell({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [navigationOpen,setNavigationOpen]=useState(false)
  const location=useLocation()
  useEffect(()=>setNavigationOpen(false),[location.pathname])
  const signOut = async () => {
    if (!window.confirm('退出当前账号？保留外观设置；车辆数据将停止显示。')) return
    await logout()
    if (loadSession()) return
    navigate('/login', { replace: true })
  }
  return (
    <div className="ml-shell">
      <header className={`ml-header${navigationOpen ? ' is-open' : ''}`} onKeyDown={e=>{if(e.key==='Escape')setNavigationOpen(false)}}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 9, fontSize: 16, fontWeight: 700, color: 'var(--apple-label-primary)' }}>
          <span style={{ width: 26, height: 26, borderRadius: 10, background: 'var(--apple-action)', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--apple-on-action)' }}>
            <Icon name="bolt" size={15} />
          </span>
          MateLink
        </div>
        <button className="ml-mobile-menu" aria-expanded={navigationOpen} aria-controls="site-navigation" onClick={()=>setNavigationOpen(v=>!v)}>{navigationOpen?'收起导航':'导航'}</button>
        <nav id="site-navigation" aria-label="主导航" style={{ display: 'flex', gap: 4, flexWrap: 'wrap', alignItems: 'center' }}>
          {NAV_GROUPS.map(group => (
            <GroupMenu key={group.label} group={group} />
          ))}
          <NavLink
            to="/controls"
            style={({ isActive }) => ({
              display: 'inline-flex', alignItems: 'center', gap: 7,
              padding: '7px 14px', borderRadius: 999, textDecoration: 'none',
              fontSize: 14, fontWeight: isActive ? 600 : 400,
              color: isActive ? 'var(--apple-tint)' : 'var(--apple-label-primary)',
              background: isActive ? 'var(--apple-surface-tertiary)' : 'transparent',
            })}
          >
            <Icon name="sliders" size={18} />
            远程控制
          </NavLink>
          <NavLink
            to="/me"
            style={({ isActive }) => ({
              display: 'inline-flex', alignItems: 'center', gap: 7,
              padding: '7px 14px', borderRadius: 999, textDecoration: 'none',
              fontSize: 14, fontWeight: isActive ? 600 : 400,
              color: isActive ? 'var(--apple-tint)' : 'var(--apple-label-primary)',
              background: isActive ? 'var(--apple-surface-tertiary)' : 'transparent',
            })}
          >
            <Icon name="person" size={18} />
            设置
          </NavLink>
        </nav>
        <div className="ml-header-session" style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 6 }}>
          <button
            onClick={() => void signOut()}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', cursor: 'pointer', color: 'var(--apple-label-secondary)', fontSize: 13.5, fontWeight: 500, padding: '7px 12px', borderRadius: 999, fontFamily: 'inherit' }}
          >
            <Icon name="logout" size={17} />
            退出登录
          </button>
        </div>
      </header>
      <main className="ml-main">{children}</main>
    </div>
  )
}

function RequireSession({ children }: { children: ReactNode }) {
  const scope = useSyncExternalStore(subscribeSession, sessionScope, () => '')
  if (!loadSession()) return <Navigate to="/login" replace />
  return <Shell key={scope}>{children}</Shell>
}

const ROUTES: { path: string; element: ReactNode }[] = [
  { path: '/', element: <WebDashboard /> },
  { path: '/charge-level', element: <ChargeLevelPage /> },
  // 状态不再单独成页（所有者 2026-11-08 定版）：旧路由落到总览的状态条锚点。
  { path: '/states', element: <Navigate to="/#states" replace /> },
  { path: '/timeline', element: <Navigate to="/#states" replace /> },
  { path: '/charges', element: <ChargesPage /> },
  { path: '/charging-stats', element: <ChargingStatsPage /> },
  { path: '/drives', element: <DrivesPage /> },
  { path: '/drive-stats', element: <DriveStatsPage /> },
  { path: '/efficiency', element: <EfficiencyPage /> },
  { path: '/battery', element: <BatteryPage /> },
  { path: '/projected-range', element: <ProjectedRangePage /> },
  { path: '/vampire', element: <VampirePage /> },
  { path: '/statistics', element: <StatisticsPage /> },
  { path: '/mileage', element: <MileagePage /> },
  { path: '/temperatures', element: <TemperaturesPage /> },
  { path: '/locations', element: <LocationsPage /> },
  { path: '/visited', element: <VisitedPage /> },
  { path: '/updates', element: <UpdatesPage /> },
  { path: '/data-info', element: <DataInfoPage /> },
  { path: '/controls', element: <ControlsPage /> },
  { path: '/me', element: <MePage /> },
]

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/auth/complete" element={<AuthComplete />} />
        {ROUTES.map(route => (
          <Route
            key={route.path}
            path={route.path}
            element={<RequireSession>{route.element}</RequireSession>}
          />
        ))}
        <Route path="/analysis" element={<Navigate to="/statistics" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
