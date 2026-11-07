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
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    setError('');
    api.getDrives(carId).then((drives: Drive[]) => {
      const destMap = new Map<string, Destination>();
      drives.forEach(d => {
        const key = d.end_address;
        if (destMap.has(key)) {
          const existing = destMap.get(key)!;
          existing.visits += 1;
          existing.km += d.distance_km;
        } else {
          destMap.set(key, {
            name: d.end_address,
            visits: 1,
            km: d.distance_km,
            lat: d.end_latitude,
            lng: d.end_longitude,
          });
        }
      });
      const sorted = [...destMap.values()].sort((a, b) => b.visits - a.visits);
      setDestinations(sorted.map(destination => ({ ...destination, km: Number(destination.km.toFixed(1)) })));
      setLoading(false);
    }).catch(err => { setError(getApiErrorMessage(err)); setLoading(false); });
  }, [carId]);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate destination data unavailable: {error}</div>;
  if (loading) return <div className="animate-pulse text-gray-400">Loading top destinations...</div>;

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
      {destinations.length === 0 ? (
        <div className="rounded-xl border border-gray-200 dark:border-gray-700 p-6 text-center text-sm text-gray-500">No destination data for this vehicle.</div>
      ) : (
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
      )}
    </div>
  );
}
