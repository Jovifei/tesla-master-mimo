import { useEffect, useState } from 'react';
import { api, getApiErrorMessage } from '../api/client';
import type { SoftwareUpdate } from '../api/types';

export default function SoftwareVersions({ carId }: { carId: number }) {
  const [updates, setUpdates] = useState<SoftwareUpdate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true);
    setError('');
    api.getUpdates(carId)
      .then(data => { setUpdates(data); setLoading(false); })
      .catch(err => { setError(getApiErrorMessage(err)); setLoading(false); });
  }, [carId]);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate software updates unavailable: {error}</div>;
  if (loading) return <div className="animate-pulse text-gray-400">Loading software updates...</div>;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Software Updates</h1>

      {updates.map((update, i) => {
        const installedAt = update.install_date || update.date;
        return (
          <div key={update.id ?? `${update.version}-${i}`} className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm">
            <div className="flex items-center justify-between">
              <div>
                <div className="font-medium">{update.version}</div>
                <div className="text-sm text-gray-500">
                  Installed: {new Date(installedAt).toLocaleDateString('zh-CN')}
                </div>
              </div>
              <div className="text-right">
                {i === 0 && (
                  <span className="px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-700">Latest</span>
                )}
                <div className="text-sm text-gray-400 mt-1">
                  Running for {Math.floor((Date.now() - new Date(installedAt).getTime()) / 86400000)} days
                </div>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
