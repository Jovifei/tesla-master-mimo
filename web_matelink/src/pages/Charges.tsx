import { Link } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { api, getApiErrorMessage } from '../api/client';
import type { Charge } from '../api/types';

export default function Charges({ carId }: { carId: number }) {
  const [charges, setCharges] = useState<Charge[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [typeFilter, setTypeFilter] = useState<'All' | 'AC' | 'DC'>('All');

  useEffect(() => {
    setLoading(true);
    setError('');
    api.getCharges(carId)
      .then(data => { setCharges(data); setLoading(false); })
      .catch(err => { setError(getApiErrorMessage(err)); setLoading(false); });
  }, [carId]);

  const filteredCharges = charges.filter(c =>
    typeFilter === 'All' || c.charging_type === typeFilter
  );

  // Monthly chart data
  const monthlyData = [
    { month: 'Jan', charges: 18, energy: 520 },
    { month: 'Feb', charges: 15, energy: 430 },
    { month: 'Mar', charges: 20, energy: 580 },
    { month: 'Apr', charges: 17, energy: 490 },
    { month: 'May', charges: 19, energy: 550 },
    { month: 'Jun', charges: 12, energy: 340 },
  ];

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate charges unavailable: {error}</div>;
  if (loading) return <div className="animate-pulse text-gray-400">Loading charges...</div>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Charges</h1>
        <div className="flex gap-2">
          {(['All', 'AC', 'DC'] as const).map(type => (
            <button
              key={type}
              onClick={() => setTypeFilter(type)}
              className={`px-3 py-1 rounded-lg text-sm ${
                typeFilter === type ? 'bg-blue-100 text-blue-700' : 'bg-gray-100 text-gray-600'
              }`}
            >
              {type}
            </button>
          ))}
        </div>
      </div>

      {/* Monthly Chart */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Monthly Charges</h2>
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={monthlyData}>
            <XAxis dataKey="month" />
            <YAxis />
            <Tooltip />
            <Bar dataKey="energy" fill="#1E88E5" />
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* Charge List */}
      {filteredCharges.map(charge => (
        <Link key={charge.id} to={`/charges/${charge.id}`} className="block">
          <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm hover:shadow-md transition-shadow">
            <div className="flex items-center justify-between mb-2">
              <div className="text-sm text-gray-500">
                {new Date(charge.start_date).toLocaleString('zh-CN')}
              </div>
              <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                charge.charging_type === 'DC' ? 'bg-orange-100 text-orange-700' : 'bg-green-100 text-green-700'
              }`}>
                {charge.charging_type}
              </span>
            </div>
            <div className="flex items-center justify-between">
              <div>
                <div className="text-sm font-medium">{charge.address}</div>
                <div className="text-xs text-gray-400">{charge.duration_min} min</div>
              </div>
              <div className="text-right">
                <div className="text-lg font-bold">{charge.charge_energy_added} kWh</div>
                <div className="text-xs text-gray-500">
                  {charge.start_battery_level}% → {charge.end_battery_level}%
                </div>
                {charge.cost && <div className="text-sm font-medium text-green-600">¥{charge.cost}</div>}
              </div>
            </div>
          </div>
        </Link>
      ))}
    </div>
  );
}
