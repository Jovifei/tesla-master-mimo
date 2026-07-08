import { useState } from 'react';
import { useStore } from '../store';

export default function Settings() {
  const [serverUrl, setServerUrl] = useState('https://teslamate.example.com/api/v1');
  const [token, setToken] = useState('');
  const [testResult, setTestResult] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [units, setUnits] = useState('km');
  const [tempUnit, setTempUnit] = useState('C');
  const [tariffEnabled, setTariffEnabled] = useState(false);

  const theme = useStore(s => s.theme);
  const setTheme = useStore(s => s.setTheme);
  const mockMode = useStore(s => s.mockMode);
  const setMockMode = useStore(s => s.setMockMode);

  const testConnection = () => {
    setTestResult('testing');
    setTimeout(() => {
      setTestResult(mockMode ? 'success' : 'error');
    }, 1500);
  };

  return (
    <div className="space-y-6 max-w-2xl">
      <h1 className="text-2xl font-bold">Settings</h1>

      {/* Connection */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Connection</h2>
        <div className="space-y-3">
          <div>
            <label className="block text-sm text-gray-500 mb-1">Server URL</label>
            <input type="text" value={serverUrl} onChange={e => setServerUrl(e.target.value)}
              className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm" />
          </div>
          <div>
            <label className="block text-sm text-gray-500 mb-1">API Token</label>
            <input type="password" value={token} onChange={e => setToken(e.target.value)}
              placeholder="Enter token..."
              className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm" />
          </div>
          <button onClick={testConnection}
            className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700">
            {testResult === 'testing' ? 'Testing...' : 'Test Connection'}
          </button>
          {testResult === 'success' && <div className="text-green-600 text-sm">✓ Connected! Found 2 vehicles.</div>}
          {testResult === 'error' && <div className="text-red-600 text-sm">✗ Cannot reach server.</div>}
        </div>
      </div>

      {/* Advanced */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Advanced</h2>
        <div className="space-y-3">
          <div>
            <label className="block text-sm text-gray-500 mb-1">Secondary Server URL (optional)</label>
            <input type="text" placeholder="https://local-lan:8080"
              className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm" />
          </div>
        </div>
      </div>

      {/* Preferences */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Preferences</h2>
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <span className="text-sm">Units</span>
            <select value={units} onChange={e => setUnits(e.target.value)}
              className="p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm">
              <option value="km">km</option>
              <option value="mi">miles</option>
            </select>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm">Temperature</span>
            <select value={tempUnit} onChange={e => setTempUnit(e.target.value)}
              className="p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm">
              <option value="C">°C</option>
              <option value="F">°F</option>
            </select>
          </div>
          <div className="flex items-center justify-between">
            <span className="text-sm">Theme</span>
            <select value={theme} onChange={e => setTheme(e.target.value as 'light' | 'dark' | 'system')}
              className="p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm">
              <option value="system">System</option>
              <option value="light">Light</option>
              <option value="dark">Dark</option>
            </select>
          </div>
        </div>
      </div>

      {/* China */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">China Localization</h2>
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <span className="text-sm">Time-of-Use Tariff</span>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={tariffEnabled} onChange={e => setTariffEnabled(e.target.checked)} className="rounded" />
              <span className="text-sm">{tariffEnabled ? 'On' : 'Off'}</span>
            </label>
          </div>
          {tariffEnabled && (
            <div className="grid grid-cols-3 gap-3 text-sm">
              <div>
                <label className="text-gray-500">Peak ¥/kWh</label>
                <input type="number" defaultValue="1.0" className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
              </div>
              <div>
                <label className="text-gray-500">Flat ¥/kWh</label>
                <input type="number" defaultValue="0.7" className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
              </div>
              <div>
                <label className="text-gray-500">Valley ¥/kWh</label>
                <input type="number" defaultValue="0.3" className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Development */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Development</h2>
        <div className="flex items-center justify-between">
          <span className="text-sm">Mock Mode</span>
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={mockMode} onChange={e => setMockMode(e.target.checked)} className="rounded" />
            <span className="text-sm">{mockMode ? 'On' : 'Off'}</span>
          </label>
        </div>
      </div>

      {/* About */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">About</h2>
        <div className="space-y-2 text-sm text-gray-500">
          <div>Version: 1.0.0</div>
          <div>Not affiliated with Tesla, Inc.</div>
          <div>Requires self-hosted TeslaMate instance.</div>
        </div>
      </div>
    </div>
  );
}
