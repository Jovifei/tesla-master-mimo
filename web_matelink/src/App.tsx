import { BrowserRouter, Routes, Route, NavLink, Navigate } from 'react-router-dom';
import { useState, useEffect } from 'react';
import { useStore } from './store';
import { api, getApiErrorMessage } from './api/client';
import { flattenCar } from './api/types';
import mockData from './mock_data.json';

// Pages
import Dashboard from './pages/Dashboard';
import Drives from './pages/Drives';
import DriveDetail from './pages/DriveDetail';
import Charges from './pages/Charges';
import ChargeDetail from './pages/ChargeDetail';
import CurrentCharge from './pages/CurrentCharge';
import BatteryHealth from './pages/BatteryHealth';
import Mileage from './pages/Mileage';
import Statistics from './pages/Statistics';
import Heatmap from './pages/Heatmap';
import TopDestinations from './pages/TopDestinations';
import EfficiencyCurve from './pages/EfficiencyCurve';
import CountriesVisited from './pages/CountriesVisited';
import SentryEvents from './pages/SentryEvents';
import Trips from './pages/Trips';
import SoftwareVersions from './pages/SoftwareVersions';
import Settings from './pages/Settings';
import Onboarding from './pages/Onboarding';

const navItems = [
  { path: '/dashboard', label: 'Dashboard', icon: '📊' },
  { path: '/drives', label: 'Drives', icon: '🚗' },
  { path: '/charges', label: 'Charges', icon: '⚡' },
  { path: '/battery', label: 'Battery', icon: '🔋' },
  { path: '/mileage', label: 'Mileage', icon: '📏' },
  { path: '/statistics', label: 'Statistics', icon: '📈' },
  { path: '/heatmap', label: 'Heatmap', icon: '🗓️' },
  { path: '/top-destinations', label: 'Top Destinations', icon: '📍' },
  { path: '/efficiency', label: 'Efficiency', icon: '📉' },
  { path: '/countries', label: 'Countries', icon: '🌍' },
  { path: '/sentry', label: 'Sentry', icon: '📷' },
  { path: '/trips', label: 'Trips', icon: '🗺️' },
  { path: '/updates', label: 'Updates', icon: '📱' },
  { path: '/settings', label: 'Settings', icon: '⚙️' },
];

function App() {
  const [sidebarOpen, setSidebarOpen] = useState(true);
  const [isOnline, setIsOnline] = useState(navigator.onLine);
  const [apiError, setApiError] = useState('');

  const currentCarId = useStore(s => s.currentCarId);
  const setCarId = useStore(s => s.setCarId);
  const setCars = useStore(s => s.setCars);
  const mockMode = useStore(s => s.mockMode);
  const setMockMode = useStore(s => s.setMockMode);
  const serverUrl = useStore(s => s.serverUrl);
  const onboardingDone = useStore(s => s.onboardingDone);
  const theme = useStore(s => s.theme);
  const setTheme = useStore(s => s.setTheme);
  const cars = useStore(s => s.cars);

  // Online/offline detection
  useEffect(() => {
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  // Theme management handled by store's setTheme
  useEffect(() => {
    if (mockMode || !serverUrl) return;
    let active = true;
    api.getCars()
      .then(items => {
        if (!active) return;
        setCars(items.map(flattenCar));
        setApiError('');
      })
      .catch(err => {
        if (active) setApiError(getApiErrorMessage(err));
      });
    return () => { active = false; };
  }, [mockMode, serverUrl, setCars]);

  const currentCar = mockData.cars.find(c => c.car_id === currentCarId);
  const currentStatus = mockData.statuses[String(currentCarId) as keyof typeof mockData.statuses];

  return (
    <BrowserRouter>
      <div className="flex h-screen bg-gray-50 dark:bg-gray-900">
        {/* Sidebar */}
        <aside className={`${sidebarOpen ? 'w-64' : 'w-16'} bg-white dark:bg-gray-800 border-r border-gray-200 dark:border-gray-700 transition-all duration-300 flex flex-col`}>
          {/* Logo */}
          <div className="p-4 border-b border-gray-200 dark:border-gray-700 flex items-center justify-between">
            {sidebarOpen && <h1 className="text-xl font-bold text-blue-600">MateLink</h1>}
            <button onClick={() => setSidebarOpen(!sidebarOpen)} className="p-1 rounded hover:bg-gray-100 dark:hover:bg-gray-700">
              {sidebarOpen ? '◀' : '▶'}
            </button>
          </div>

          {/* Car Selector */}
          {sidebarOpen && (
            <div className="p-4 border-b border-gray-200 dark:border-gray-700">
              <select
                value={currentCarId}
                onChange={e => setCarId(Number(e.target.value))}
                className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700"
              >
                {cars.map(car => (
                  <option key={car.id} value={car.id}>
                    {car.name} ({car.model})
                  </option>
                ))}
              </select>
            </div>
          )}

          {/* Navigation */}
          <nav className="flex-1 overflow-y-auto p-2">
            {navItems.map(item => (
              <NavLink
                key={item.path}
                to={item.path}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-3 py-2 rounded-lg mb-1 transition-colors ${
                    isActive
                      ? 'bg-blue-50 dark:bg-blue-900/30 text-blue-600'
                      : 'hover:bg-gray-100 dark:hover:bg-gray-700'
                  }`
                }
              >
                <span className="text-lg">{item.icon}</span>
                {sidebarOpen && <span className="text-sm">{item.label}</span>}
              </NavLink>
            ))}
          </nav>

          {/* Mock Mode + Theme Toggle */}
          {sidebarOpen && (
            <div className="p-4 border-t border-gray-200 dark:border-gray-700 space-y-3">
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={mockMode}
                  onChange={e => setMockMode(e.target.checked)}
                  className="rounded"
                />
                Mock Mode
              </label>
              <div className="flex gap-1">
                {(['light', 'dark', 'system'] as const).map(t => (
                  <button
                    key={t}
                    onClick={() => setTheme(t)}
                    className={`flex-1 py-1 text-xs rounded ${
                      theme === t
                        ? 'bg-blue-600 text-white'
                        : 'bg-gray-100 dark:bg-gray-700 text-gray-600 dark:text-gray-300'
                    }`}
                  >
                    {t === 'light' ? '☀️' : t === 'dark' ? '🌙' : '💻'}
                  </button>
                ))}
              </div>
            </div>
          )}
        </aside>

        {/* Main Content */}
        <main className="flex-1 overflow-y-auto">
          {/* Offline Banner */}
          {!isOnline && (
            <div className="bg-red-100 dark:bg-red-900/30 text-red-800 dark:text-red-200 text-center py-1 text-sm">
              ⚠️ Offline — Data may be outdated
            </div>
          )}

          {/* Mock Mode Banner */}
          {mockMode && (
            <div className="bg-purple-100 dark:bg-purple-900/30 text-purple-800 dark:text-purple-200 text-center py-1 text-sm">
              Mock Mode - All data is fake
            </div>
          )}

          {apiError && !mockMode && (
            <div className="bg-red-100 dark:bg-red-900/30 text-red-800 dark:text-red-200 text-center py-1 text-sm">
              TeslaMate unavailable - {apiError}
            </div>
          )}

          {/* Car Status Bar */}
          {currentStatus && (
            <div className="bg-white dark:bg-gray-800 border-b border-gray-200 dark:border-gray-700 px-6 py-3 flex items-center gap-6 text-sm">
              <span className="font-medium">{currentCar?.name}</span>
              <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                currentStatus.state === 'online' ? 'bg-green-100 text-green-700' :
                currentStatus.state === 'charging' ? 'bg-orange-100 text-orange-700' :
                currentStatus.state === 'driving' ? 'bg-blue-100 text-blue-700' :
                'bg-gray-100 text-gray-700'
              }`}>
                {currentStatus.state}
              </span>
              <span>🔋 {currentStatus.battery_level}%</span>
              <span>📏 {currentStatus.usable_battery_range_km} km</span>
              <span>🌡️ {currentStatus.inside_temp}°C</span>
              <span>{currentStatus.locked ? '🔒' : '🔓'}</span>
              <span>{currentStatus.plugged_in ? '⚡' : ''}</span>
            </div>
          )}

          {/* Page Content */}
          <div className="p-6">
            <Routes>
              <Route path="/" element={<Navigate to={onboardingDone ? '/dashboard' : '/onboarding'} />} />
              {!onboardingDone && <Route path="*" element={<Navigate to="/onboarding" replace />} />}
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/drives" element={<Drives />} />
              <Route path="/drives/:id" element={<DriveDetail />} />
              <Route path="/charges" element={<Charges carId={currentCarId} />} />
              <Route path="/charges/:id" element={<ChargeDetail />} />
              <Route path="/current-charge" element={<CurrentCharge carId={currentCarId} />} />
              <Route path="/battery" element={<BatteryHealth carId={currentCarId} />} />
              <Route path="/mileage" element={<Mileage carId={currentCarId} />} />
              <Route path="/statistics" element={<Statistics carId={currentCarId} />} />
              <Route path="/heatmap" element={<Heatmap carId={currentCarId} />} />
              <Route path="/top-destinations" element={<TopDestinations carId={currentCarId} />} />
              <Route path="/efficiency" element={<EfficiencyCurve carId={currentCarId} />} />
              <Route path="/countries" element={<CountriesVisited />} />
              <Route path="/sentry" element={<SentryEvents carId={currentCarId} />} />
              <Route path="/trips" element={<Trips carId={currentCarId} />} />
              <Route path="/updates" element={<SoftwareVersions carId={currentCarId} />} />
              <Route path="/settings" element={<Settings />} />
              <Route path="/onboarding" element={<Onboarding />} />
            </Routes>
          </div>
        </main>
      </div>
    </BrowserRouter>
  );
}

export default App;
