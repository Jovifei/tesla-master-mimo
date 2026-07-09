import { useEffect, useState } from 'react';
import { api, getApiErrorMessage } from '../api/client';
import type { SentryEvent } from '../api/types';

export default function SentryEvents({ carId }: { carId: number }) {
  const [events, setEvents] = useState<SentryEvent[]>([]);
  const [error, setError] = useState('');

  useEffect(() => {
    setError('');
    api.getSentryEvents(carId)
      .then((data: SentryEvent[]) => setEvents(data))
      .catch(err => setError(getApiErrorMessage(err)));
  }, [carId]);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate sentry events unavailable: {error}</div>;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Sentry Events</h1>

      {events.map(event => (
        <div key={event.id} className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <div className="text-sm text-gray-500">
              {new Date(event.start_date).toLocaleString('zh-CN')}
            </div>
            <span className="px-2 py-0.5 rounded-full text-xs bg-red-100 text-red-700">Event</span>
          </div>
          <div className="font-medium text-sm">{event.address}</div>
          <div className="text-xs text-gray-400 mt-1">
            Duration: {Math.round((new Date(event.end_date).getTime() - new Date(event.start_date).getTime()) / 60000)} min
          </div>
          <div className="mt-2 bg-gray-100 dark:bg-gray-700 rounded-lg h-32 flex items-center justify-center">
            <span className="text-gray-500 text-sm">📍 Map Location</span>
          </div>
        </div>
      ))}
    </div>
  );
}
