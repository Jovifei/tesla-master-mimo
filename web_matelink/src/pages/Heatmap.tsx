export default function Heatmap({ carId }: { carId: number }) {
  const days = 15;
  const hours = 24;

  // Generate mock heatmap data
  const data = Array.from({ length: days }, (_, day) =>
    Array.from({ length: hours }, (_, hour) => ({
      day,
      hour,
      value: Math.random() * 100,
    }))
  ).flat();

  const getColor = (value: number) => {
    if (value < 20) return 'bg-green-100';
    if (value < 40) return 'bg-green-200';
    if (value < 60) return 'bg-green-300';
    if (value < 80) return 'bg-green-400';
    return 'bg-green-500';
  };

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Activity Heatmap</h1>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm overflow-x-auto">
        <div className="min-w-[800px]">
          {/* Hour labels */}
          <div className="flex gap-1 mb-1 ml-10">
            {Array.from({ length: hours }, (_, i) => (
              <div key={i} className="w-6 text-xs text-gray-400 text-center">{i}</div>
            ))}
          </div>

          {/* Heatmap rows */}
          {Array.from({ length: days }, (_, day) => (
            <div key={day} className="flex items-center gap-1 mb-1">
              <div className="w-8 text-xs text-gray-500 text-right pr-2">
                {new Date(Date.now() - (days - day) * 86400000).toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })}
              </div>
              {Array.from({ length: hours }, (_, hour) => {
                const value = data.find(d => d.day === day && d.hour === hour)?.value || 0;
                return (
                  <div
                    key={hour}
                    className={`w-6 h-6 rounded-sm ${getColor(value)} cursor-pointer hover:ring-2 hover:ring-blue-400 transition-all`}
                    title={`${new Date(Date.now() - (days - day) * 86400000).toLocaleDateString()} ${hour}:00 - ${value.toFixed(0)} km`}
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
