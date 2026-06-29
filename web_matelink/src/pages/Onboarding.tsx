import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

export default function Onboarding() {
  const navigate = useNavigate();
  const [step, setStep] = useState<'welcome' | 'config' | 'success'>('welcome');
  const [serverUrl, setServerUrl] = useState('');
  const [token, setToken] = useState('');
  const [testing, setTesting] = useState(false);
  const [error, setError] = useState('');

  const testConnection = () => {
    if (!serverUrl) {
      setError('Please enter a server URL');
      return;
    }
    setTesting(true);
    setError('');
    setTimeout(() => {
      setTesting(false);
      setStep('success');
    }, 2000);
  };

  if (step === 'welcome') {
    return (
      <div className="min-h-[80vh] flex items-center justify-center">
        <div className="text-center max-w-md">
          <div className="text-6xl mb-6">🚗</div>
          <h1 className="text-3xl font-bold mb-2">MateLink</h1>
          <p className="text-gray-500 mb-8">Your TeslaMate data companion</p>
          <button onClick={() => setStep('config')}
            className="w-full px-6 py-3 bg-blue-600 text-white rounded-xl text-lg font-medium hover:bg-blue-700">
            Connect to TeslaMate
          </button>
          <button onClick={() => navigate('/dashboard')}
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
          <h1 className="text-2xl font-bold mb-6 text-center">Connect to TeslaMate</h1>
          <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm space-y-4">
            <div>
              <label className="block text-sm text-gray-500 mb-1">Server URL</label>
              <input type="text" value={serverUrl} onChange={e => setServerUrl(e.target.value)}
                placeholder="https://teslamate.example.com/api/v1"
                className="w-full p-3 rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-700" />
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
              {testing ? (
                <span className="flex items-center justify-center gap-2">
                  <span className="animate-spin">⏳</span> Testing Connection...
                </span>
              ) : 'Test Connection'}
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-[80vh] flex items-center justify-center">
      <div className="text-center max-w-md">
        <div className="text-6xl mb-4">✅</div>
        <h1 className="text-2xl font-bold mb-2">Connected!</h1>
        <p className="text-gray-500 mb-6">Found 2 vehicles</p>
        <button onClick={() => navigate('/dashboard')}
          className="px-8 py-3 bg-blue-600 text-white rounded-xl text-lg font-medium hover:bg-blue-700">
          Enter Dashboard
        </button>
      </div>
    </div>
  );
}
