export default function Trips({ carId }: { carId: number }) {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">Trips</h1>
        <button className="px-4 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700">
          + Create Trip
        </button>
      </div>

      <div className="bg-white dark:bg-gray-800 rounded-2xl p-8 shadow-sm text-center">
        <div className="text-6xl mb-4">🗺️</div>
        <div className="text-xl font-semibold mb-2">No Road Trips Yet</div>
        <div className="text-gray-500 text-sm max-w-md mx-auto">
          <p className="mb-3">A road trip is detected automatically when you have:</p>
          <ul className="text-left space-y-1">
            <li>• 2 or more drives in a row</li>
            <li>• linked by at least one DC fast-charge stop</li>
            <li>• covering 300 km or more in total</li>
          </ul>
          <p className="mt-3">Wait for one to be detected, or tap + to build a trip yourself.</p>
        </div>
      </div>
    </div>
  );
}
