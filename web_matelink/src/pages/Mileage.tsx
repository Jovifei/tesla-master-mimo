import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

const yearData = [
  { month: 'Jan', km: 1200 }, { month: 'Feb', km: 980 }, { month: 'Mar', km: 1450 },
  { month: 'Apr', km: 1100 }, { month: 'May', km: 1350 }, { month: 'Jun', km: 850 },
];

export default function Mileage({ carId }: { carId: number }) {
  return (
    <div className="space-y-6">
      <div className="flex items-center gap-2 text-sm text-gray-500">
        <span className="text-blue-600 font-medium">2026</span>
      </div>
      <h1 className="text-2xl font-bold">Mileage</h1>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <div className="grid grid-cols-3 gap-4 mb-6 text-center">
          <div>
            <div className="text-3xl font-bold text-blue-600">6,930</div>
            <div className="text-xs text-gray-500">Total km</div>
          </div>
          <div>
            <div className="text-3xl font-bold">985</div>
            <div className="text-xs text-gray-500">kWh used</div>
          </div>
          <div>
            <div className="text-3xl font-bold">142</div>
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
