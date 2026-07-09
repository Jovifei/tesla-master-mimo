import { useState } from 'react';
import { getApiErrorMessage, testTeslaMateConnection } from '../api/client';
import { useStore } from '../store';

export default function Settings() {
  const savedServerUrl = useStore(s => s.serverUrl);
  const savedToken = useStore(s => s.apiToken);
  const setServer = useStore(s => s.setServer);
  const theme = useStore(s => s.theme);
  const setTheme = useStore(s => s.setTheme);
  const mockMode = useStore(s => s.mockMode);
  const setMockMode = useStore(s => s.setMockMode);

  const [serverUrl, setServerUrl] = useState(savedServerUrl);
  const [token, setToken] = useState(savedToken);
  const [testResult, setTestResult] = useState<'idle' | 'testing' | 'success' | 'error'>('idle');
  const [message, setMessage] = useState('');
  const [units, setUnits] = useState('km');
  const [tempUnit, setTempUnit] = useState('C');
  const [tariffEnabled, setTariffEnabled] = useState(false);

  const testConnection = async () => {
    setTestResult('testing');
    setMessage('');
    try {
      const result = await testTeslaMateConnection(serverUrl, token);
      setServer(serverUrl, token);
      setMockMode(false);
      setTestResult('success');
      setMessage(`Connected. Found ${result.carCount} vehicle${result.carCount === 1 ? '' : 's'}${result.firstCarName ? `: ${result.firstCarName}` : ''}${result.warning ? ` (${result.warning})` : ''}`);
    } catch (err) {
      setTestResult('error');
      setMessage(getApiErrorMessage(err));
    }
  };

  return (
    <div className="space-y-6 max-w-2xl">
      <h1 className="text-2xl font-bold">Settings</h1>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Connection</h2>
        <div className="space-y-3">
          <p className="text-sm text-gray-500">Requires self-hosted TeslaMate + TeslaMateApi-compatible API. Do I need a server? Real data yes; Mock mode no.</p>
          <div>
            <label className="block text-sm text-gray-500 mb-1">API Root URL</label>
            <input type="text" value={serverUrl} onChange={e => { setServerUrl(e.target.value); setTestResult('idle'); }}
              placeholder="https://teslamate-api.example.com"
              className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm" />
            <div className="text-xs text-gray-500 mt-1">Enter the API root URL for your TeslaMateApi-compatible API, not Grafana or TeslaMate Web UI. Do not add /api/v1.</div>
          </div>
          <div>
            <label className="block text-sm text-gray-500 mb-1">API Token (optional)</label>
            <input type="password" value={token} onChange={e => { setToken(e.target.value); setTestResult('idle'); }}
              placeholder="Enter token..."
              className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700 text-sm" />
          </div>
          <div className="flex gap-3">
            <button onClick={testConnection} disabled={testResult === 'testing' || mockMode}
              className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50">
              {testResult === 'testing' ? 'Testing...' : 'Test and Save'}
            </button>
            <button onClick={() => setServer(serverUrl, token)} disabled={testResult !== 'success'}
              className="px-4 py-2 bg-gray-100 dark:bg-gray-700 rounded-lg text-sm disabled:opacity-50">
              Save Verified Config
            </button>
          </div>
          {mockMode && <div className="text-purple-600 text-sm">Mock Mode is on. Turn it off to use real TeslaMate data.</div>}
          {testResult === 'success' && <div className="text-green-600 text-sm">{message}</div>}
          {testResult === 'error' && <div className="text-red-600 text-sm">{message}</div>}
        </div>
      </div>

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
              <option value="C">C</option>
              <option value="F">F</option>
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

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">China Localization</h2>
        <div className="space-y-4">
          <div className="text-sm text-gray-500">
            Map guidance: AMap/Gaode Web Service Key is user-owned and must be applied for separately. Leave it blank to keep the current fallback map and geocoding behavior.
          </div>
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
                <label className="text-gray-500">Peak CNY/kWh</label>
                <input type="number" defaultValue="1.0" className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
              </div>
              <div>
                <label className="text-gray-500">Flat CNY/kWh</label>
                <input type="number" defaultValue="0.7" className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
              </div>
              <div>
                <label className="text-gray-500">Valley CNY/kWh</label>
                <input type="number" defaultValue="0.3" className="w-full p-2 rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
              </div>
            </div>
          )}
        </div>
      </div>

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

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">About</h2>
        <div className="space-y-2 text-sm text-gray-500">
          <div>Version: 1.0.0</div>
          <div>Not affiliated with Tesla, Inc.</div>
          <div>Requires self-hosted TeslaMate + TeslaMateApi-compatible API.</div>
          <div>Do I need a server? Real data yes; Mock mode no.</div>
        </div>
      </div>
    </div>
  );
}
