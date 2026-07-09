import { useEffect, useState } from 'react';
import { api, getApiErrorMessage } from '../api/client';
import type { Drive } from '../api/types';

interface Trip {
  date: string;
  drives: Drive[];
  totalDistance: number;
  totalDuration: number;
  startAddress: string;
  endAddress: string;
}

function groupDrivesIntoTrips(drives: Drive[]): Trip[] {
  // Sort drives by start_date ascending
  const sorted = [...drives].sort((a, b) => new Date(a.start_date).getTime() - new Date(b.start_date).getTime());

  const groups = new Map<string, Drive[]>();
  for (const d of sorted) {
    const dateKey = d.start_date.slice(0, 10); // YYYY-MM-DD
    const g = groups.get(dateKey) || [];
    g.push(d);
    groups.set(dateKey, g);
  }

  return [...groups.entries()].map(([date, items]) => ({
    date,
    drives: items,
    totalDistance: items.reduce((sum, d) => sum + d.distance_km, 0),
    totalDuration: items.reduce((sum, d) => sum + d.duration_min, 0),
    startAddress: items[0].start_address,
    endAddress: items[items.length - 1].end_address,
  }));
}

export default function Trips({ carId }: { carId: number }) {
  const [drives, setDrives] = useState<Drive[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    setError('');
    api.getDrives(carId)
      .then(d => { setDrives(d); setLoading(false); })
      .catch(err => { setError(getApiErrorMessage(err)); setLoading(false); });
  }, [carId]);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate trips unavailable: {error}</div>;
  if (loading) return <div className="animate-pulse text-gray-400">Loading...</div>;

  const trips = groupDrivesIntoTrips(drives);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Trips</h1>
        <button
          onClick={() => alert('Trip creation coming soon! Trips are currently auto-detected from your drive history.')}
          className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700"
        >
          + Create Trip
        </button>
      </div>

      {trips.length === 0 ? (
        <div className="bg-white dark:bg-gray-800 rounded-2xl p-8 shadow-sm text-center">
          <div className="text-6xl mb-4">🗺️</div>
          <div className="text-xl font-semibold mb-2">No Trips Yet</div>
          <div className="text-gray-500 text-sm max-w-md mx-auto">
            <p>Drive more to see your trips appear here.</p>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {trips.map(trip => (
            <div key={trip.date} className="bg-white dark:bg-gray-800 rounded-xl p-4 border border-gray-100 dark:border-gray-700 hover:shadow-md transition-shadow">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span className="text-2xl">🗺️</span>
                  <div>
                    <div className="font-medium text-gray-900 dark:text-white">
                      {trip.startAddress} → {trip.endAddress}
                    </div>
                    <div className="text-xs text-gray-400">
                      {new Date(trip.date + 'T00:00:00').toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' })}
                    </div>
                  </div>
                </div>
                <div className="text-right">
                  <div className="font-bold text-gray-900 dark:text-white">{trip.totalDistance.toFixed(1)} km</div>
                  <div className="text-xs text-gray-400">
                    {trip.drives.length} drive{trip.drives.length !== 1 ? 's' : ''} · {trip.totalDuration} min
                  </div>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
