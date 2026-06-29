import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer } from 'recharts';

const acDcData = [
  { name: 'AC', value: 180, color: '#43A047' },
  { name: 'DC', value: 54, color: '#FB8C00' },
];

export default function Statistics({ carId }: { carId: number }) {
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Statistics for Nerds</h1>

      {/* Records */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Personal Records</h2>
        <div className="grid grid-cols-2 gap-4">
          {[
            { label: 'Longest Drive', value: '120.5 km', detail: 'Jun 21, 2026' },
            { label: 'Top Speed', value: '120 km/h', detail: 'Jun 21, 2026' },
            { label: 'Most Efficient', value: '142 Wh/km', detail: 'Jun 23, 2026' },
            { label: 'Longest Trip', value: '2h 30min', detail: 'Jun 21, 2026' },
          ].map(record => (
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
            <div className="flex items-center gap-2">
              <div className="w-3 h-3 rounded-full bg-green-500" />
              <span className="text-sm">AC: 180 sessions (77%)</span>
            </div>
            <div className="flex items-center gap-2">
              <div className="w-3 h-3 rounded-full bg-orange-500" />
              <span className="text-sm">DC: 54 sessions (23%)</span>
            </div>
          </div>
        </div>
      </div>

      {/* Temperature Stats */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <h2 className="font-semibold mb-4">Temperature Stats</h2>
        <div className="grid grid-cols-3 gap-4 text-center">
          <div>
            <div className="text-2xl font-bold">28.5°C</div>
            <div className="text-xs text-gray-500">Average</div>
          </div>
          <div>
            <div className="text-2xl font-bold text-red-500">38°C</div>
            <div className="text-xs text-gray-500">Highest</div>
          </div>
          <div>
            <div className="text-2xl font-bold text-blue-500">2°C</div>
            <div className="text-xs text-gray-500">Lowest</div>
          </div>
        </div>
      </div>
    </div>
  );
}
