import mockData from '../mock_data.json';

export default function CurrentCharge({ carId }: { carId: number }) {
  const status = mockData.statuses[String(carId) as keyof typeof mockData.statuses];

  if (!status || status.state !== 'charging') {
    return (
      <div className="text-center py-20">
        <div className="text-6xl mb-4">🔋</div>
        <div className="text-xl font-semibold">Not Charging</div>
        <div className="text-gray-500">The vehicle is not currently charging</div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">Current Charge</h1>

      {/* Progress Ring */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-8 shadow-sm text-center">
        <div className="relative inline-block">
          <svg className="w-48 h-48 transform -rotate-90">
            <circle cx="96" cy="96" r="88" stroke="#E0E0E0" strokeWidth="12" fill="none" />
            <circle cx="96" cy="96" r="88" stroke="#FB8C00" strokeWidth="12" fill="none"
              strokeDasharray={`${status.battery_level * 5.53} 553`} strokeLinecap="round" />
          </svg>
          <div className="absolute inset-0 flex items-center justify-center">
            <div>
              <div className="text-4xl font-bold">{status.battery_level}%</div>
              <div className="text-sm text-gray-500">{status.usable_battery_range_km} km</div>
            </div>
          </div>
        </div>
      </div>

      {/* Charging Data */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold text-orange-600">{status.charge_energy_added} kWh</div>
          <div className="text-xs text-gray-500">Added</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{status.charger_power} kW</div>
          <div className="text-xs text-gray-500">Power</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{status.charger_voltage}V</div>
          <div className="text-xs text-gray-500">Voltage</div>
        </div>
        <div className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm text-center">
          <div className="text-2xl font-bold">{status.time_to_full_charge}h</div>
          <div className="text-xs text-gray-500">Remaining</div>
        </div>
      </div>

      {/* Location */}
      <div className="bg-white dark:bg-gray-800 rounded-2xl p-4 shadow-sm">
        <div className="text-sm text-gray-500">Charging Location</div>
        <div className="font-medium">📍 Home - Shanghai</div>
      </div>
    </div>
  );
}
