import { useParams, Link } from 'react-router-dom';
import { useEffect, useState } from 'react';
import { api, getApiErrorMessage } from '../api/client';
import { useStore } from '../store';
import type { Drive } from '../api/types';

export default function DriveDetail() {
  const { id } = useParams();
  const carId = useStore(s => s.currentCarId);
  const [drive, setDrive] = useState<Drive | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    api.getDrive(carId, Number(id))
      .then(value => { if (active) { setDrive(value ?? null); setLoading(false); } })
      .catch(err => { if (active) { setError(getApiErrorMessage(err)); setLoading(false); } });
    return () => { active = false; };
  }, [carId, id]);

  if (loading) return <div className="animate-pulse text-gray-400">Loading drive detail...</div>;
  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate drive detail unavailable: {error}</div>;
  if (!drive) return <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-6 text-center text-sm text-gray-500">Drive not found for this vehicle.</div>;

  return (
    <div className="space-y-6">
      <Link to="/drives" className="text-blue-600 text-sm">← Back to Drives</Link>

      {/* Header */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h1 className="text-2xl font-bold mb-4">Drive Detail</h1>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div className="text-sm text-gray-500">Date</div>
            <div className="font-medium">{new Date(drive.start_date).toLocaleDateString('zh-CN')}</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Duration</div>
            <div className="font-medium">{drive.duration_min} min</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Distance</div>
            <div className="font-medium">{drive.distance_km} km</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Efficiency</div>
            <div className="font-medium">{drive.efficiency} Wh/km</div>
          </div>
        </div>
      </div>

      {/* Route Map Placeholder */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Route Map</h2>
        <div className="bg-gray-100 dark:bg-gray-700 rounded-xl h-64 flex items-center justify-center">
          <div className="text-center text-gray-500">
            <div className="text-4xl mb-2">🗺️</div>
            <div>Leaflet Map</div>
            <div className="text-xs">{drive.start_address} → {drive.end_address}</div>
          </div>
        </div>
      </div>

      {/* Statistics Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold text-blue-600">{drive.speed_max}</div>
          <div className="text-xs text-gray-500">Max Speed (km/h)</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold text-green-600">{drive.power_max}</div>
          <div className="text-xs text-gray-500">Max Power (kW)</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{drive.outside_temp_avg}°C</div>
          <div className="text-xs text-gray-500">Avg Temp</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{drive.elevation_gain}m</div>
          <div className="text-xs text-gray-500">Elevation Gain</div>
        </div>
      </div>

      {/* Chart Tabs */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <div className="flex gap-2 mb-4">
          {['Speed', 'Power', 'Elevation'].map(tab => (
            <button key={tab} className="px-3 py-1 rounded-lg text-sm bg-blue-100 text-blue-700 dark:bg-blue-900/30">
              {tab}
            </button>
          ))}
        </div>
        <div className="h-48 flex items-center justify-center text-sm text-gray-400">Drive telemetry samples are not available for this trip.</div>
      </div>

      {/* Weather */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-4 shadow-sm">
        <div className="text-sm text-gray-500">Weather Along The Way</div>
        <div className="font-medium">🌡️ {drive.outside_temp_avg}°C</div>
      </div>
    </div>
  );
}
