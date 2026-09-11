import { Button, Text, View } from '@tarojs/components'
import { useEffect, useState } from 'react'
import ReadinessChecklist from '../../components/ReadinessChecklist'
import MetricValue from '../../components/MetricValue'
import { matelinkApi } from '../../services/api'
import type { Car, CarStatus } from '../../services/types'

export default function VehiclePage() {
  const [car, setCar] = useState<Car | null>(null)
  const [status, setStatus] = useState<CarStatus | null>(null)
  const [readinessStatus, setReadinessStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    matelinkApi.getCars()
      .then(async cars => {
        if (!active) return
        const firstCar = cars[0] ?? null
        setCar(firstCar)
        if (firstCar) {
          const [nextStatus, readiness] = await Promise.all([
            matelinkApi.getCarStatus(firstCar.id),
            matelinkApi.getReadiness(firstCar.id),
          ])
          setStatus(nextStatus)
          setReadinessStatus(readiness.items.find(item => item.key === 'live_status')?.status ?? null)
        }
      })
      .catch(reason => {
        if (active) setError(reason instanceof Error ? reason.message : '无法读取 MateLink 服务')
      })
    return () => { active = false }
  }, [])

  return (
    <View className="page">
      <View className="hero card">
        <Text className="eyebrow">MATELINK / WECHAT</Text>
        <Text className="title">车辆</Text>
        <Text className="muted">只展示服务端已确认的车辆观测，不用占位数据填充缺失字段。</Text>
      </View>

      <ReadinessChecklist status={readinessStatus} />

      {car ? (
        <View className="card">
          <Text className="section-title">{car.displayName || car.name || '车辆名称待确认'}</Text>
          <Text className="muted">车型：{car.model || '车型待识别'}</Text>
          {status ? (
            <View className="metric-grid">
              <MetricValue label="状态" value={status.state || '暂无数据'} source={status.source} />
              <MetricValue label="电量" value={status.batteryLevel == null ? '暂无数据' : `${status.batteryLevel}%`} source={status.source} />
              <MetricValue label="续航" value={status.ratedRange == null ? '暂无数据' : `${status.ratedRange} km`} source={status.source} />
              <MetricValue label="里程" value={status.odometer == null ? '暂无数据' : `${status.odometer} km`} source={status.source} />
            </View>
          ) : null}
        </View>
      ) : (
        <View className="card">
          <Text className="section-title">尚未绑定车辆</Text>
          <Text className="muted">请先建立微信会话并完成 Tesla 官方关联。没有真实响应时不显示示例车辆。</Text>
          {error ? <Text className="error">{error}</Text> : null}
          <Button className="button" onClick={() => setError('微信会话与账号关联接口尚未在后端实现')}>连接 MateLink</Button>
        </View>
      )}
    </View>
  )
}
