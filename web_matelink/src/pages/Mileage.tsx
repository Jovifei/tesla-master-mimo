import { useEffect, useState } from 'react';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import { api, getApiErrorMessage } from '../api/client';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

interface MonthData {
  month: string;
  km: number;
}

export default function Mileage({ carId }: { carId: number }) {
  const [yearData, setYearData] = useState<MonthData[]>([]);
  const [totalKm, setTotalKm] = useState(0);
  const [totalEnergy, setTotalEnergy] = useState(0);
  const [avgEfficiency, setAvgEfficiency] = useState(0);
  const [error, setError] = useState('');

  useEffect(() => {
    setError('');
    Promise.all([api.getDrives(carId), api.getCharges(carId)]).then(([drives, charges]) => {
      const now = new Date();
      const thisYear = now.getFullYear();

      // Monthly km from drives
      const monthlyKm = new Array(12).fill(0);
      drives.forEach(d => {
        const date = new Date(d.start_date);
        if (date.getFullYear() === thisYear) {
          monthlyKm[date.getMonth()] += d.distance_km;
        }
      });

      const yearData: MonthData[] = MONTHS.map((month, i) => ({
        month,
        km: Math.round(monthlyKm[i]),
      }));

      // Total km and energy for this year
      const yearDrives = drives.filter(d => new Date(d.start_date).getFullYear() === thisYear);
      const totalKm = Math.round(yearDrives.reduce((sum, d) => sum + d.distance_km, 0));
      const totalEnergy = Math.round(charges.reduce((sum, c) => sum + c.charge_energy_added, 0));
      const avgEfficiency = yearDrives.length > 0
        ? Math.round(yearDrives.reduce((sum, d) => sum + d.efficiency, 0) / yearDrives.length)
        : 0;

      setYearData(yearData);
      setTotalKm(totalKm);
      setTotalEnergy(totalEnergy);
      setAvgEfficiency(avgEfficiency);
    }).catch(err => setError(getApiErrorMessage(err)));
  }, [carId]);

  if (error) return <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-red-700 dark:border-red-900 dark:bg-red-900/20 dark:text-red-200">TeslaMate mileage data unavailable: {error}</div>;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2 text-sm text-gray-500">
        <span className="text-blue-600 font-medium">{new Date().getFullYear()}</span>
      </div>
      <h1 className="text-2xl font-bold">Mileage</h1>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <div className="grid grid-cols-3 gap-4 mb-6 text-center">
          <div>
            <div className="text-3xl font-bold text-blue-600">{totalKm.toLocaleString()}</div>
            <div className="text-xs text-gray-500">Total km</div>
          </div>
          <div>
            <div className="text-3xl font-bold">{totalEnergy.toLocaleString()}</div>
            <div className="text-xs text-gray-500">kWh used</div>
          </div>
          <div>
            <div className="text-3xl font-bold">{avgEfficiency}</div>
            <div className="text-xs text-gray-500">Wh/km avg</div>
          </div>
        </div>

        <ResponsiveContainer width="100%" height={250}>
          <BarChart data={yearData}>
            <XAxis dataKey="month" />
            <YAxis />
            <Tooltip />
            <Bar dataKey="km" fill="#1E88E5" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
