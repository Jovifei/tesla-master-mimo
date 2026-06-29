export default function TopDestinations({ carId }: { carId: number }) {
  const destinations = [
    { name: '家 - 上海市浦东新区张江镇', visits: 350, km: 0, lat: 31.2304, lng: 121.4737 },
    { name: '公司 - 上海市浦东新区陆家嘴', visits: 320, km: 7450, lat: 31.2398, lng: 121.4998 },
    { name: '特斯拉超级充电站 - 浦东嘉里城', visits: 45, km: 675, lat: 31.2200, lng: 121.5600 },
    { name: '苏州市姑苏区观前街', visits: 8, km: 960, lat: 31.3100, lng: 120.6200 },
    { name: '杭州市西湖区', visits: 5, km: 600, lat: 30.2741, lng: 120.1551 },
  ];

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Top Destinations</h1>

      {/* Map Placeholder */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-6 shadow-sm">
        <div className="bg-gray-100 dark:bg-gray-700 rounded-xl h-64 flex items-center justify-center">
          <div className="text-center text-gray-500">
            <div className="text-4xl mb-2">🗺️</div>
            <div>Leaflet Cluster Map</div>
            <div className="text-xs">Markers sized by visit count</div>
          </div>
        </div>
      </div>

      {/* Destination List */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-sm divide-y divide-gray-100 dark:divide-gray-700">
        {destinations.map((dest, i) => (
          <div key={dest.name} className="p-4 flex items-center gap-4">
            <div className="w-8 h-8 rounded-full bg-blue-100 text-blue-600 flex items-center justify-center font-bold text-sm">
              {i + 1}
            </div>
            <div className="flex-1">
              <div className="font-medium text-sm">{dest.name}</div>
              <div className="text-xs text-gray-500">{dest.visits} visits · {dest.km} km total</div>
            </div>
            <div className="w-12 h-12 rounded-full bg-blue-500 flex items-center justify-center text-white font-bold"
              style={{ width: `${Math.max(32, dest.visits / 10)}px`, height: `${Math.max(32, dest.visits / 10)}px` }}>
              {dest.visits}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
