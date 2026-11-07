import { useParams, Link } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { api, getApiErrorMessage } from '../api/client';
import { useStore } from '../store';
import type { Charge } from '../api/types';

export default function ChargeDetail() {
  const { id } = useParams();
  const carId = useStore(s => s.currentCarId);
  const [charge, setCharge] = useState<Charge | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    api.getCharge(carId, Number(id))
      .then(value => { if (active) { setCharge(value ?? null); setLoading(false); } })
      .catch(err => { if (active) { setError(getApiErrorMessage(err)); setLoading(false); } });
    return () => { active = false; };
  }, [carId, id]);

  if (loading) return <div className="animate-pulse text-gray-400">Loading charge detail...</div>;
  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate charge detail unavailable: {error}</div>;
  if (!charge) return <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-6 text-center text-sm text-gray-500">Charge not found for this vehicle.</div>;

  return (
    <div className="space-y-6">
      <Link to="/charges" className="text-blue-600 text-sm">← Back to Charges</Link>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h1 className="text-2xl font-bold mb-4">Charge Detail</h1>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div className="text-sm text-gray-500">Date</div>
            <div className="font-medium">{new Date(charge.start_date).toLocaleDateString('zh-CN')}</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Duration</div>
            <div className="font-medium">{charge.duration_min} min</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Energy Added</div>
            <div className="font-medium">{charge.charge_energy_added} kWh</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Cost</div>
            <div className="font-medium text-green-600">¥{charge.cost || 'N/A'}</div>
          </div>
        </div>
      </div>

      {/* Map */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Location</h2>
        <div className="bg-gray-100 dark:bg-gray-700 rounded-xl h-48 flex items-center justify-center">
          <div className="text-center text-gray-500">
            <div className="text-4xl mb-2">📍</div>
            <div>{charge.address}</div>
          </div>
        </div>
      </div>

      {/* Power Chart */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Power Curve</h2>
        <div className="h-48 flex items-center justify-center text-sm text-gray-400">Power telemetry is not available for this charge.</div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold text-orange-600">{charge.power_max} kW</div>
          <div className="text-xs text-gray-500">Max Power</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{charge.start_battery_level}% → {charge.end_battery_level}%</div>
          <div className="text-xs text-gray-500">Battery Level</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{charge.outside_temp_avg}°C</div>
          <div className="text-xs text-gray-500">Outside Temp</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{charge.charging_type}</div>
          <div className="text-xs text-gray-500">Type</div>
        </div>
      </div>
    </div>
  );
}
