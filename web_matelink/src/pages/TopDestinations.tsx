import { useEffect, useState } from 'react';
import { api, getApiErrorMessage } from '../api/client';
import type { Drive } from '../api/types';

interface Destination {
  name: string;
  visits: number;
  km: number;
  lat: number;
  lng: number;
}

export default function TopDestinations({ carId }: { carId: number }) {
  const [destinations, setDestinations] = useState<Destination[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    setError('');
    api.getDrives(carId).then((drives: Drive[]) => {
      const destMap = new Map<string, Destination>();
      drives.forEach(d => {
        const key = d.end_address;
        if (destMap.has(key)) {
          const existing = destMap.get(key)!;
          existing.visits += 1;
          existing.km += Math.round(d.distance_km * 100);
        } else {
          destMap.set(key, {
            name: d.end_address,
            visits: 1,
            km: Math.round(d.distance_km * 100),
            lat: d.end_latitude,
            lng: d.end_longitude,
          });
        }
      });
      const sorted = [...destMap.values()].sort((a, b) => b.visits - a.visits);
      setDestinations(sorted);
    }).catch(err => setError(getApiErrorMessage(err)));
  }, [carId]);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate destination data unavailable: {error}</div>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Top Destinations</h1>

      {/* Map Placeholder */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <div className="bg-gray-100 dark:bg-gray-700 rounded-xl h-64 flex items-center justify-center">
          <div className="text-center text-gray-500">
            <div className="text-4xl mb-2">🗺️</div>
            <div>Leaflet Cluster Map</div>
            <div className="text-xs">Markers sized by visit count</div>
          </div>
        </div>
      </div>

      {/* Destination List */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-sm divide-y divide-gray-100 dark:divide-gray-700">
        {destinations.map((dest, i) => (
          <div key={dest.name} className="p-4 flex items-center gap-4">
            <div className="w-8 h-8 rounded-full bg-blue-100 text-blue-600 flex items-center justify-center font-bold text-sm">
              {i + 1}
            </div>
            <div className="flex-1">
              <div className="font-medium text-sm">{dest.name}</div>
              <div className="text-xs text-gray-500">{dest.visits} visits · {dest.km} km total</div>
            </div>
            <div className="w-12 h-12 rounded-full bg-blue-500 flex items-center justify-center text-white font-bold"
              style={{ width: `${Math.max(32, dest.visits / 10)}px`, height: `${Math.max(32, dest.visits / 10)}px` }}>
              {dest.visits}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
