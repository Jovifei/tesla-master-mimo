import { useEffect, useState } from 'react'
import { getCars } from '../api/matelink'
import type { WebCar } from '../api/matelink-types'

/** Shared car loading/selection for the W2 pages (same pattern as the dashboard). */
export function useCar() {
  const [cars, setCars] = useState<WebCar[]>([])
  const [carId, setCarId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const controller=new AbortController()
    getCars(controller.signal).then(list => {
      if(controller.signal.aborted)return
      setCars(list)
      setCarId(current => current!=null && list.some(car=>car.id===current) ? current : list[0]?.id??null)
      setError(list.length?null:'当前账号暂无车辆。')
    }).catch(()=>{if(!controller.signal.aborted)setError('读取车辆列表失败，请稍后重试。')})
    return()=>controller.abort()
  }, [])

  const car = cars.find(item => item.id === carId) ?? null
  return { cars, car, carId, setCarId, error }
}
