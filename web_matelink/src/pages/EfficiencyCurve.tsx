import { useEffect, useState } from 'react';
import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { api } from '../api/client';
import { useStore } from '../store';
import type { Drive } from '../api/types';

interface ScatterPoint {
  temp: number;
  efficiency: number;
}

export default function EfficiencyCurve({ carId }: { carId: number }) {
  const { currentCarId } = useStore();
  const [data, setData] = useState<ScatterPoint[]>([]);

  useEffect(() => {
    api.getDrives(currentCarId).then((drives: Drive[]) => {
      const points: ScatterPoint[] = drives
        .filter(d => d.outside_temp_avg != null && d.efficiency != null)
        .map(d => ({
          temp: Math.round(d.outside_temp_avg * 10) / 10,
          efficiency: d.efficiency,
        }));
      setData(points);
    });
  }, [currentCarId]);

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Efficiency Curve</h1>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Efficiency vs Temperature</h2>
        <ResponsiveContainer width="100%" height={350}>
          <ScatterChart>
            <XAxis dataKey="temp" name="Temperature" unit="°C" domain={[-10, 40]} />
            <YAxis dataKey="efficiency" name="Efficiency" unit=" Wh/km" />
            <Tooltip cursor={{ strokeDasharray: '3 3' }} />
            <Scatter data={data} fill="#1E88E5" />
          </ScatterChart>
        </ResponsiveContainer>
        <div className="mt-4 text-sm text-gray-500 text-center">
          Optimal efficiency: 18-25°C · Each point = one drive
        </div>
      </div>
    </div>
  );
}
