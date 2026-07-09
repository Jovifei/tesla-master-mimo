import { useEffect, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { api, getApiErrorMessage } from '../api/client';
import type { BatteryHealth as BatteryHealthPoint } from '../api/types';

export default function BatteryHealth({ carId }: { carId: number }) {
  const [healthData, setHealthData] = useState<BatteryHealthPoint[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    setError('');
    api.getBatteryHealth(carId)
      .then(data => { setHealthData(data); setLoading(false); })
      .catch(err => { setError(getApiErrorMessage(err)); setLoading(false); });
  }, [carId]);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate battery health unavailable: {error}</div>;
  if (loading) return <div className="animate-pulse text-gray-400">Loading battery health...</div>;

  const latest = healthData[healthData.length - 1];
  const original = healthData[0];

  if (!latest || !original) return <div>No battery health data</div>;

  const healthPercent = ((latest.rated_range_km / original.rated_range_km) * 100).toFixed(1);
  const degradation = (100 - Number(healthPercent)).toFixed(1);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Battery Health</h1>

      {/* Health Ring */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-8 shadow-sm text-center">
        <div className="relative inline-block">
          <svg className="w-48 h-48 transform -rotate-90">
            <circle cx="96" cy="96" r="88" stroke="#E0E0E0" strokeWidth="12" fill="none" />
            <circle cx="96" cy="96" r="88" stroke="#43A047" strokeWidth="12" fill="none"
              strokeDasharray={`${Number(healthPercent) * 5.53} 553`} strokeLinecap="round" />
          </svg>
          <div className="absolute inset-0 flex items-center justify-center">
            <div>
              <div className="text-4xl font-bold text-green-600">{healthPercent}%</div>
              <div className="text-sm text-gray-500">Health</div>
            </div>
          </div>
        </div>
      </div>

      {/* Capacity Comparison */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Capacity Comparison</h2>
        <div className="space-y-3">
          <div>
            <div className="flex justify-between text-sm mb-1">
              <span>Original</span>
              <span>{original.rated_range_km} km</span>
            </div>
            <div className="bg-gray-200 rounded-full h-4">
              <div className="bg-gray-400 h-4 rounded-full" style={{ width: '100%' }} />
            </div>
          </div>
          <div>
            <div className="flex justify-between text-sm mb-1">
              <span>Current</span>
              <span>{latest.rated_range_km} km</span>
            </div>
            <div className="bg-gray-200 rounded-full h-4">
              <div className="bg-green-500 h-4 rounded-full" style={{ width: `${healthPercent}%` }} />
            </div>
          </div>
        </div>
        <div className="mt-4 text-center">
          <span className="text-sm text-gray-500">Degradation: {degradation}%</span>
        </div>
      </div>

      {/* Trend Chart */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Degradation Trend</h2>
        <ResponsiveContainer width="100%" height={250}>
          <LineChart data={healthData}>
            <XAxis dataKey="date" />
            <YAxis domain={[400, 550]} />
            <Tooltip />
            <Line type="monotone" dataKey="rated_range_km" stroke="#43A047" strokeWidth={2} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
