import { useCallback, useEffect, useState } from 'react'
import { getCars } from '../api/matelink'
import type { WebCar } from '../api/matelink-types'

/** Shared car loading/selection for the W2 pages (same pattern as the dashboard). */
export function useCar() {
  const [cars, setCars] = useState<WebCar[]>([])
  const [carId, setCarId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const list = await getCars()
      setCars(list)
      setCarId(current => (current != null && list.some(car => car.id === current) ? current : list[0]?.id ?? null))
      if (!list.length) setError('当前账号暂无车辆。')
    } catch {
      setError('读取车辆列表失败，请稍后重试。')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const car = cars.find(item => item.id === carId) ?? null
  return { cars, car, carId, setCarId, error }
}
