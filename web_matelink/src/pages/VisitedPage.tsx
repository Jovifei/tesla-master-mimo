import { useMemo } from 'react'
import { Panel } from '../components/v3'
import { CarPicker, ErrorNote, LoadingNote, MapPanel, PageTitle } from '../components/tm'
import { useCar } from './useCar'
import { useHistory } from './useHistory'

/**
 * 到访地图 — TeslaMate Visited 的复刻：全部到访点位（行程起终点
 * 与充电地点坐标）落在同一张地图上。坐标数据齐全；地图瓦片需要
 * 高德 Web JS key，未配置时组件给出占位面板与点位清单。
 */

export default function VisitedPage() {
  const { cars, carId, setCarId, error: carError } = useCar()
  const history = useHistory(carId)

  const points = useMemo(() => {
    const map = new Map<string, { lat: number; lng: number; label: string }>()
    const add = (lat: number | null, lng: number | null, label: string | null) => {
      if (lat == null || lng == null) return
      const key = `${lat.toFixed(3)},${lng.toFixed(3)}`
      if (!map.has(key)) map.set(key, { lat, lng, label: label ?? '到访点' })
    }
    for (const drive of history.drives) {
      add(drive.startLatitude, drive.startLongitude, drive.startAddress)
      add(drive.endLatitude, drive.endLongitude, drive.endAddress)
    }
    for (const charge of history.charges) {
      add(charge.latitude, charge.longitude, charge.address)
    }
    return [...map.values()].slice(0, 300)
  }, [history.drives, history.charges])

  return (
    <div>
      <PageTitle title="到访地图">
        <CarPicker cars={cars} carId={carId} onChange={setCarId} />
      </PageTitle>
      {carError ? <ErrorNote text={carError} /> : null}
      {history.error ? <ErrorNote text={history.error} /> : null}
      {history.loading && !history.drives.length && !history.charges.length ? (
        <LoadingNote />
      ) : points.length ? (
        <MapPanel title={`全部到访点位（${points.length} 个）`} points={points} height={520} />
      ) : (
        <Panel>
          <p style={{ fontSize: 13.5, color: '#8a8478' }}>已加载记录里暂无带坐标的点位。</p>
        </Panel>
      )}
    </div>
  )
}
