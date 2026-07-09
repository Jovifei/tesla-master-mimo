import mockData from '../mock_data.json';

export default function CountriesVisited() {
  const regions = mockData.visited_regions;

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Countries & Regions Visited</h1>

      <div className="bg-white dark:bg-gray-800 rounded-2xl shadow-sm divide-y divide-gray-100 dark:divide-gray-700">
        {regions.map(region => (
          <div key={region.region} className="p-4">
            <div className="flex items-center gap-3">
              <span className="text-2xl">🇨🇳</span>
              <div className="flex-1">
                <div className="font-medium">{region.region}</div>
                <div className="text-sm text-gray-500">
                  {region.drive_count} drives · {region.charge_count} charges
                </div>
              </div>
              <div className="text-right">
                <div className="font-bold">{region.distance_km.toLocaleString()} km</div>
                <div className="text-sm text-gray-500">{region.energy_used.toLocaleString()} kWh</div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
