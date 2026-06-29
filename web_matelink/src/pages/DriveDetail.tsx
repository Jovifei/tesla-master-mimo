import { useParams, Link } from 'react-router-dom';
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import mockData from '../mock_data.json';

export default function DriveDetail({ carId }: { carId: number }) {
  const { id } = useParams();
  const drive = mockData.drives.find(d => d.id === Number(id));

  if (!drive) return <div>Drive not found</div>;

  // Generate mock position data for chart
  const chartData = Array.from({ length: 30 }, (_, i) => ({
    time: `${i}min`,
    speed: 30 + Math.random() * 60,
    power: -20 + Math.random() * 150,
    elevation: 5 + Math.random() * 20,
  }));

  return (
    <div className="space-y-6">
      <Link to="/drives" className="text-blue-600 text-sm">← Back to Drives</Link>

      {/* Header */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h1 className="text-2xl font-bold mb-4">Drive Detail</h1>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div>
            <div className="text-sm text-gray-500">Date</div>
            <div className="font-medium">{new Date(drive.start_date).toLocaleDateString('zh-CN')}</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Duration</div>
            <div className="font-medium">{drive.duration_min} min</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Distance</div>
            <div className="font-medium">{drive.distance_km} km</div>
          </div>
          <div>
            <div className="text-sm text-gray-500">Efficiency</div>
            <div className="font-medium">{drive.efficiency} Wh/km</div>
          </div>
        </div>
      </div>

      {/* Route Map Placeholder */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Route Map</h2>
        <div className="bg-gray-100 dark:bg-gray-700 rounded-xl h-64 flex items-center justify-center">
          <div className="text-center text-gray-500">
            <div className="text-4xl mb-2">🗺️</div>
            <div>Leaflet Map</div>
            <div className="text-xs">{drive.start_address} → {drive.end_address}</div>
          </div>
        </div>
      </div>

      {/* Statistics Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold text-blue-600">{drive.speed_max}</div>
          <div className="text-xs text-gray-500">Max Speed (km/h)</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold text-green-600">{drive.power_max}</div>
          <div className="text-xs text-gray-500">Max Power (kW)</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{drive.outside_temp_avg}°C</div>
          <div className="text-xs text-gray-500">Avg Temp</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{drive.elevation_gain}m</div>
          <div className="text-xs text-gray-500">Elevation Gain</div>
        </div>
      </div>

      {/* Chart Tabs */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <div className="flex gap-2 mb-4">
          {['Speed', 'Power', 'Elevation'].map(tab => (
            <button key={tab} className="px-3 py-1 rounded-lg text-sm bg-blue-100 text-blue-700 dark:bg-blue-900/30">
              {tab}
            </button>
          ))}
        </div>
        <ResponsiveContainer width="100%" height={250}>
          <LineChart data={chartData}>
            <XAxis dataKey="time" />
            <YAxis />
            <Tooltip />
            <Line type="monotone" dataKey="speed" stroke="#1E88E5" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>

      {/* Weather */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-4 shadow-sm">
        <div className="text-sm text-gray-500">Weather Along The Way</div>
        <div className="font-medium">🌡️ {drive.outside_temp_avg}°C Sunny</div>
      </div>
    </div>
  );
}
