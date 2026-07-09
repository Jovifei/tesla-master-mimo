import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getApiErrorMessage, testTeslaMateConnection } from '../api/client';
import { useStore } from '../store';

export default function Onboarding() {
  const navigate = useNavigate();
  const setServer = useStore(s => s.setServer);
  const setMockMode = useStore(s => s.setMockMode);
  const [step, setStep] = useState<'welcome' | 'config' | 'success'>('welcome');
  const [serverUrl, setServerUrl] = useState('');
  const [token, setToken] = useState('');
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [warning, setWarning] = useState('');

  const testConnection = async () => {
    if (!serverUrl.trim()) {
      setError('Please enter a TeslaMate root URL.');
      return;
    }

    setTesting(true);
    setError('');
    setSuccess('');
    setWarning('');

    try {
      const result = await testTeslaMateConnection(serverUrl, token);
      setServer(serverUrl, token);
      setSuccess(`Found ${result.carCount} vehicle${result.carCount === 1 ? '' : 's'}${result.firstCarName ? `: ${result.firstCarName}` : ''}`);
      setWarning(result.warning ?? '');
      setStep('success');
    } catch (err) {
      setError(getApiErrorMessage(err));
    } finally {
      setTesting(false);
    }
  };

  const useMockData = () => {
    setMockMode(true);
    navigate('/dashboard');
  };

  if (step === 'welcome') {
    return (
      <div className="min-h-[80vh] flex items-center justify-center">
        <div className="text-center max-w-md">
          <div className="text-6xl mb-6">ML</div>
          <h1 className="text-3xl font-bold mb-2">MateLink</h1>
          <p className="text-gray-500 mb-8">Connect your self-hosted TeslaMate data.</p>
          <button onClick={() => setStep('config')}
            className="w-full px-6 py-3 bg-blue-600 text-white rounded-xl text-lg font-medium hover:bg-blue-700">
            Connect TeslaMate
          </button>
          <button onClick={useMockData}
            className="w-full mt-3 px-6 py-3 bg-gray-100 dark:bg-gray-700 rounded-xl text-sm">
            Use Mock Data
          </button>
        </div>
      </div>
    );
  }

  if (step === 'config') {
    return (
      <div className="min-h-[80vh] flex items-center justify-center">
        <div className="max-w-md w-full">
          <h1 className="text-2xl font-bold mb-6 text-center">Connect TeslaMate</h1>
          <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm space-y-4">
            <div>
              <label className="block text-sm text-gray-500 mb-1">Server URL</label>
              <input type="text" value={serverUrl} onChange={e => setServerUrl(e.target.value)}
                placeholder="https://teslamate.example.com"
                className="w-full p-3 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
              <div className="text-xs text-gray-500 mt-1">Enter the TeslaMate root address. Do not add /api or /api/v1.</div>
            </div>
            <div>
              <label className="block text-sm text-gray-500 mb-1">API Token (optional)</label>
              <input type="password" value={token} onChange={e => setToken(e.target.value)}
                placeholder="Enter token..."
                className="w-full p-3 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
            </div>
            {error && <div className="text-red-600 text-sm">{error}</div>}
            <button onClick={testConnection} disabled={testing}
              className="w-full py-3 bg-blue-600 text-white rounded-lg font-medium hover:bg-blue-700 disabled:opacity-50">
              {testing ? 'Testing Connection...' : 'Test Connection'}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-[80vh] flex items-center justify-center">
      <div className="text-center max-w-md">
        <div className="text-5xl mb-4 text-green-600">OK</div>
        <h1 className="text-2xl font-bold mb-2">Connected</h1>
        <p className="text-gray-500 mb-2">{success}</p>
        {warning && <p className="text-yellow-600 text-sm mb-4">{warning}</p>}
        <button onClick={() => navigate('/dashboard')}
          className="px-8 py-3 bg-blue-600 text-white rounded-xl text-lg font-medium hover:bg-blue-700">
          Enter Dashboard
        </button>
      </div>
    </div>
  );
}
