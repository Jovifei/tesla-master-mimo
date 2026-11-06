import { useEffect, useState } from 'react';
import { api, getApiErrorMessage } from '../api/client';
import { useStore } from '../store';
import type { VisitedRegion } from '../api/types';

export default function CountriesVisited() {
  const currentCarId = useStore(s => s.currentCarId);
  const [regions, setRegions] = useState<VisitedRegion[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');
    api.getVisitedRegions(currentCarId)
      .then(data => { if (active) { setRegions(data); setLoading(false); } })
      .catch(err => { if (active) { setError(getApiErrorMessage(err)); setLoading(false); } });
    return () => { active = false; };
  }, [currentCarId]);

  if (loading) return <div className="animate-pulse text-gray-400">Loading visited regions...</div>;
  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate region data unavailable: {error}</div>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Countries & Regions Visited</h1>

      {regions.length === 0 ? (
        <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-6 text-center text-sm text-gray-500">No visited region data for this vehicle.</div>
      ) : (
        <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-sm divide-y divide-gray-100 dark:divide-gray-700">
          {regions.map(region => (
            <div key={`${region.country_code}-${region.region}`} className="p-4">
              <div className="flex items-center gap-3">
                <span className="text-2xl">{region.country_code === 'CN' ? '🇨🇳' : '🌍'}</span>
                <div className="flex-1">
                  <div className="font-medium">{region.region}</div>
                  <div className="text-sm text-gray-500">
                    {region.drive_count} drives · {region.charge_count} charges
                  </div>
                </div>
                <div className="text-right">
                  <div className="font-bold">{region.distance_km.toLocaleString()} km</div>
                  <div className="text-sm text-gray-500">{region.energy_used.toLocaleString()} kWh</div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
