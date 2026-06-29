import { ScatterChart, Scatter, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

const data = Array.from({ length: 50 }, () => ({
  temp: -5 + Math.random() * 45,
  efficiency: 120 + Math.random() * 80 - Math.abs(22 - (-5 + Math.random() * 45)) * 2,
}));

export default function EfficiencyCurve({ carId }: { carId: number }) {
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
