import mockData from '../mock_data.json';

export default function SoftwareVersions({ carId }: { carId: number }) {
  const updates = mockData.software_updates;

  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">Software Updates</h1>

      {updates.map((update, i) => (
        <div key={update.id} className="bg-white dark:bg-gray-800 rounded-xl p-4 shadow-sm">
          <div className="flex items-center justify-between">
            <div>
              <div className="font-medium">{update.version}</div>
              <div className="text-sm text-gray-500">
                Installed: {new Date(update.install_date || update.date).toLocaleDateString('zh-CN')}
              </div>
            </div>
            <div className="text-right">
              {i === 0 && (
                <span className="px-2 py-0.5 rounded-full text-xs bg-green-100 text-green-700">Latest</span>
              )}
              <div className="text-sm text-gray-400 mt-1">
                Running for {Math.floor((Date.now() - new Date(update.install_date || update.date).getTime()) / 86400000)} days
              </div>
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
