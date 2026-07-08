import { useEffect, useState } from 'react';
import { api } from '../api/client';
import { useStore } from '../store';
import type { Drive } from '../api/types';

interface HeatCell {
  day: number;
  hour: number;
  value: number;
}

const DAYS = 15;
const HOURS = 24;

export default function Heatmap({ carId }: { carId: number }) {
  const { currentCarId } = useStore();
  const [data, setData] = useState<HeatCell[]>([]);

  useEffect(() => {
    api.getDrives(currentCarId).then((drives: Drive[]) => {
      const now = new Date();
      const cellMap = new Map<string, number>();

      // Initialize empty cells
      for (let day = 0; day < DAYS; day++) {
        for (let hour = 0; hour < HOURS; hour++) {
          cellMap.set(`${day}-${hour}`, 0);
        }
      }

      // Fill from drive data: accumulate distance by hour of day for each recent day
      drives.forEach(d => {
        const start = new Date(d.start_date);
        const end = new Date(d.end_date);
        const daysAgo = Math.floor((now.getTime() - start.getTime()) / 86400000);
        if (daysAgo < 0 || daysAgo >= DAYS) return;

        const startHour = start.getHours();
        const endHour = end.getHours();
        const distPerHour = d.duration_min > 0 ? d.distance_km / (d.duration_min / 60) : 0;

        for (let h = startHour; h <= Math.min(endHour, HOURS - 1); h++) {
          const key = `${daysAgo}-${h}`;
          cellMap.set(key, (cellMap.get(key) || 0) + distPerHour);
        }
      });

      const cells: HeatCell[] = [];
      cellMap.forEach((value, key) => {
        const [day, hour] = key.split('-').map(Number);
        cells.push({ day, hour, value: Math.round(value * 10) / 10 });
      });
      setData(cells);
    });
  }, [currentCarId]);

  const getColor = (value: number) => {
    if (value < 1) return 'bg-green-100';
    if (value < 5) return 'bg-green-200';
    if (value < 15) return 'bg-green-300';
    if (value < 30) return 'bg-green-400';
    return 'bg-green-500';
  };

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Activity Heatmap</h1>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm overflow-x-auto">
        <div className="min-w-[800px]">
          {/* Hour labels */}
          <div className="flex gap-1 mb-1 ml-10">
            {Array.from({ length: HOURS }, (_, i) => (
              <div key={i} className="w-6 text-xs text-gray-400 text-center">{i}</div>
            ))}
          </div>

          {/* Heatmap rows */}
          {Array.from({ length: DAYS }, (_, day) => (
            <div key={day} className="flex items-center gap-1 mb-1">
              <div className="w-8 text-xs text-gray-500 text-right pr-2">
                {new Date(Date.now() - (DAYS - day) * 86400000).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })}
              </div>
              {Array.from({ length: HOURS }, (_, hour) => {
                const value = data.find(d => d.day === day && d.hour === hour)?.value || 0;
                return (
                  <div
                    key={hour}
                    className={`w-6 h-6 rounded-sm ${getColor(value)} cursor-pointer hover:ring-2 hover:ring-blue-400 transition-all`}
                    title={`${new Date(Date.now() - (DAYS - day) * 86400000).toLocaleDateString()} ${hour}:00 - ${value.toFixed(1)} km/h`}
                  />
                );
              })}
            </div>
          ))}

          {/* Legend */}
          <div className="flex items-center gap-2 mt-4 justify-end">
            <span className="text-xs text-gray-400">Less</span>
            {['bg-green-100', 'bg-green-200', 'bg-green-300', 'bg-green-400', 'bg-green-500'].map(c => (
              <div key={c} className={`w-4 h-4 rounded-sm ${c}`} />
            ))}
            <span className="text-xs text-gray-400">More</span>
          </div>
        </div>
      </div>
    </div>
  );
}
