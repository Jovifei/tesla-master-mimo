import { useParams, Link } from 'react-router-dom';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import mockData from '../mock_data.json';

export default function ChargeDetail() {
  const { id } = useParams();
  const charge = mockData.charges.find(c => c.id === Number(id));

  if (!charge) return <div>Charge not found</div>;

  const chartData = Array.from({ length: 20 }, (_, i) => ({
    time: `${i * 5}min`,
    power: charge.charging_type === 'DC' ? 100 + Math.random() * 60 : 7 + Math.random() * 5,
    voltage: charge.charging_type === 'DC' ? 350 + Math.random() * 100 : 220 + Math.random() * 20,
    temperature: 25 + Math.random() * 15,
  }));

  return (
    <div className="space-y-6">
      <Link to="/charges" className="text-blue-600 text-sm">← Back to Charges</Link>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h1 className="text-2xl font-bold mb-4">Charge Detail</h1>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div className="text-sm text-gray-500">Date</div>
            <div className="font-medium">{new Date(charge.start_date).toLocaleDateString('zh-CN')}</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Duration</div>
            <div className="font-medium">{charge.duration_min} min</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Energy Added</div>
            <div className="font-medium">{charge.charge_energy_added} kWh</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Cost</div>
            <div className="font-medium text-green-600">¥{charge.cost || 'N/A'}</div>
          </div>
        </div>
      </div>

      {/* Map */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Location</h2>
        <div className="bg-gray-100 dark:bg-gray-700 rounded-xl h-48 flex items-center justify-center">
          <div className="text-center text-gray-500">
            <div className="text-4xl mb-2">📍</div>
            <div>{charge.address}</div>
          </div>
        </div>
      </div>

      {/* Power Chart */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Power Curve</h2>
        <ResponsiveContainer width="100%" height={250}>
          <AreaChart data={chartData}>
            <XAxis dataKey="time" />
            <YAxis />
            <Tooltip />
            <Area type="monotone" dataKey="power" stroke="#FB8C00" fill="#FFF3E0" />
          </AreaChart>
        </ResponsiveContainer>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold text-orange-600">{charge.power_max} kW</div>
          <div className="text-xs text-gray-500">Max Power</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{charge.start_battery_level}% → {charge.end_battery_level}%</div>
          <div className="text-xs text-gray-500">Battery Level</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{charge.outside_temp_avg}°C</div>
          <div className="text-xs text-gray-500">Outside Temp</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{charge.charging_type}</div>
          <div className="text-xs text-gray-500">Type</div>
        </div>
      </div>
    </div>
  );
}
