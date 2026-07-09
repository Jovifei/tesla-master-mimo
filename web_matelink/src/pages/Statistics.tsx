import { useEffect, useState } from 'react';
import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';
import { api, getApiErrorMessage } from '../api/client';

interface Record {
  label: string;
  value: string;
  detail: string;
}

interface AcDcEntry {
  name: string;
  value: number;
  color: string;
}

export default function Statistics({ carId }: { carId: number }) {
  const [records, setRecords] = useState<Record[]>([]);
  const [acDcData, setAcDcData] = useState<AcDcEntry[]>([]);
  const [tempStats, setTempStats] = useState({ avg: 0, max: 0, min: 0 });
  const [error, setError] = useState('');

  useEffect(() => {
    setError('');
    Promise.all([api.getDrives(carId), api.getCharges(carId)]).then(([drives, charges]) => {
      // Records from drives
      if (drives.length > 0) {
        const longestDrive = drives.reduce((a, b) => a.distance_km > b.distance_km ? a : b);
        const topSpeed = drives.reduce((a, b) => a.speed_max > b.speed_max ? a : b);
        const mostEfficient = drives.reduce((a, b) => a.efficiency < b.efficiency ? a : b);
        const longestTrip = drives.reduce((a, b) => a.duration_min > b.duration_min ? a : b);

        const fmtDate = (d: string) => new Date(d).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });

        setRecords([
          { label: 'Longest Drive', value: `${longestDrive.distance_km} km`, detail: fmtDate(longestDrive.start_date) },
          { label: 'Top Speed', value: `${topSpeed.speed_max} km/h`, detail: fmtDate(topSpeed.start_date) },
          { label: 'Most Efficient', value: `${mostEfficient.efficiency} Wh/km`, detail: fmtDate(mostEfficient.start_date) },
          { label: 'Longest Trip', value: `${Math.floor(longestTrip.duration_min / 60)}h ${longestTrip.duration_min % 60}min`, detail: fmtDate(longestTrip.start_date) },
        ]);
      }

      // AC/DC from charges
      const acCount = charges.filter(c => c.charging_type === 'AC').length;
      const dcCount = charges.filter(c => c.charging_type === 'DC').length;
      setAcDcData([
        { name: 'AC', value: acCount, color: '#43A047' },
        { name: 'DC', value: dcCount, color: '#FB8C00' },
      ]);

      // Temperature stats from drives
      const temps = drives.filter(d => d.outside_temp_avg != null).map(d => d.outside_temp_avg);
      if (temps.length > 0) {
        setTempStats({
          avg: Math.round(temps.reduce((a, b) => a + b, 0) / temps.length * 10) / 10,
          max: Math.round(Math.max(...temps) * 10) / 10,
          min: Math.round(Math.min(...temps) * 10) / 10,
        });
      }
    }).catch(err => setError(getApiErrorMessage(err)));
  }, [carId]);

  const totalCharges = acDcData.reduce((s, d) => s + d.value, 0);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate statistics unavailable: {error}</div>;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Statistics for Nerds</h1>

      {/* Records */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Personal Records</h2>
        <div className="grid grid-cols-2 gap-4">
          {records.map(record => (
            <div key={record.label} className="p-3 bg-gray-50 dark:bg-gray-700 rounded-lg">
              <div className="text-sm text-gray-500">{record.label}</div>
              <div className="text-lg font-bold">{record.value}</div>
              <div className="text-xs text-gray-400">{record.detail}</div>
            </div>
          ))}
        </div>
      </div>

      {/* AC/DC Pie */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">AC/DC Ratio</h2>
        <div className="flex items-center gap-8">
          <ResponsiveContainer width={200} height={200}>
            <PieChart>
              <Pie data={acDcData} dataKey="value" cx="50%" cy="50%" outerRadius={80}>
                {acDcData.map(entry => <Cell key={entry.name} fill={entry.color} />)}
              </Pie>
              <Tooltip />
            </PieChart>
          </ResponsiveContainer>
          <div className="space-y-2">
            {acDcData.map(entry => (
              <div key={entry.name} className="flex items-center gap-2">
                <div className="w-3 h-3 rounded-full" style={{ backgroundColor: entry.color }} />
                <span className="text-sm">{entry.name}: {entry.value} sessions ({totalCharges > 0 ? Math.round(entry.value / totalCharges * 100) : 0}%)</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Temperature Stats */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Temperature Stats</h2>
        <div className="grid grid-cols-3 gap-4 text-center">
          <div>
            <div className="text-2xl font-bold">{tempStats.avg}°C</div>
            <div className="text-xs text-gray-500">Average</div>
          </div>
          <div>
            <div className="text-2xl font-bold text-red-500">{tempStats.max}°C</div>
            <div className="text-xs text-gray-500">Highest</div>
          </div>
          <div>
            <div className="text-2xl font-bold text-blue-500">{tempStats.min}°C</div>
            <div className="text-xs text-gray-500">Lowest</div>
          </div>
        </div>
      </div>
    </div>
  );
}
