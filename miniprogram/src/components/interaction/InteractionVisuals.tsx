import { Button, Image, Text, View } from '@tarojs/components'
import { useState } from 'react'
import vehicle from '../../assets/interaction/vehicle-connection.png'
import vehicleTop from '../../assets/interaction/vehicle-position.png'
import { hasVisualRouteEndpoints, VISUAL_WHEEL_NAMES, type ConnectionPhase } from '../../domain/interaction_visuals'
import { restoreTpmsSnapshot, TPMS_WHEELS, type TpmsSnapshot, type TpmsWheel } from '../../domain/tpms'
import { historyDetailDateTime } from '../../domain/history_query'
import type { RecordedChargeInterval } from '../../services/analysis_types'
import { useVisualMotion } from './useVisualMotion'
import './interaction.scss'

const phaseLabels: Record<ConnectionPhase, string> = {
  idle: '尚未建立连接', connecting: '正在核对连接状态', action_required: '请完成页面提示的操作',
  connected: '连接状态已确认', unavailable: '连接状态暂不可用',
}
type ConnectionProps = { phase: ConnectionPhase; kind?: 'vehicle' | 'tesla' | 'wechat'; active?: boolean; reducedMotion?: boolean }
export function ConnectionVisual({ phase, kind = 'tesla', active = true, reducedMotion = false }: ConnectionProps) {
  const motion = useVisualMotion(active, phase === 'connecting', reducedMotion)
  const label = phase === 'connected' ? (kind === 'wechat' ? '微信绑定已确认' : kind === 'tesla' ? 'MateLink 会话已建立' : phaseLabels[phase]) : phaseLabels[phase]
  return <View className={`interaction-visual connection-visual connection-${phase}`} data-phase={phase} ariaLabel={label}>
    <View className="connection-pair">
      {kind === 'vehicle' ? <Image className="connection-vehicle" src={vehicle} mode="aspectFit" ariaLabel="内置车型插图，不代表当前绑定车辆" /> :
        <View className="connection-end"><Text className="connection-lock">◇</Text><Text>{kind === 'wechat' ? 'MateLink' : 'Tesla'}</Text></View>}
      <View className={`connection-bridge ${motion ? 'connection-bridge-moving' : ''}`} data-motion={motion}>
        <View className="connection-track" /><Text className="connection-symbol">{phase === 'connected' ? '✓' : phase === 'unavailable' ? '—' : '↔'}</Text>
      </View>
      <View className="connection-end"><Text className="connection-account">◎</Text><Text>{kind === 'wechat' ? '微信' : 'MateLink'}</Text></View>
    </View>
    <Text className="interaction-caption">{label}</Text>
  </View>
}

/** A/B only describes endpoint order. It is not a geographic path or vehicle playback. */
export function RouteInteractionVisual({ start, end }: { start?: string | null; end?: string | null }) {
  const [selected, setSelected] = useState<0 | 1>(0)
  const complete = hasVisualRouteEndpoints(start, end)
  return <View className="interaction-visual route-interaction" data-complete={complete}>
    <View className={`route-schematic ${complete ? '' : 'route-schematic-incomplete'}`} ariaLabel="起终点顺序示意，非实际轨迹">
      <View className="route-schematic-line" /><View className={`route-point route-point-a ${selected === 0 ? 'route-point-selected' : ''}`}>A</View>
      <View className={`route-point route-point-b ${selected === 1 ? 'route-point-selected' : ''}`}>B</View>
    </View>
    {[start, end].map((address, index) => <Button key={index} className={`route-endpoint ${selected === index ? 'route-endpoint-selected' : ''}`}
      data-endpoint={index === 0 ? 'A' : 'B'} ariaLabel={`${index === 0 ? '起点' : '终点'} ${address?.trim() || '地点未知'}`}
      onClick={() => setSelected(index as 0 | 1)}><Text className="route-endpoint-letter">{index === 0 ? 'A' : 'B'}</Text>
      <Text className="route-endpoint-address">{address?.trim() || '地点未知'}</Text></Button>)}
    <Text className="interaction-caption">起终点顺序示意 · 非实际轨迹</Text>
  </View>
}

export function VehiclePositionSchematic() {
  return <Image className="interaction-vehicle-top" src={vehicleTop} mode="aspectFit" ariaLabel="中性车辆位置示意，不代表当前车型外观" />
}
export function TpmsVehicleVisual({ snapshot, selected, onSelect, now = Date.now() }:
  { snapshot?: TpmsSnapshot | null; selected: TpmsWheel | null; onSelect: (wheel: TpmsWheel | null) => void; now?: number }) {
  const current = restoreTpmsSnapshot(snapshot, now)
  return <View className="interaction-visual tpms-vehicle-visual">
    <View className="tpms-position-board"><VehiclePositionSchematic />{TPMS_WHEELS.map(wheel => {
      const reading = current.wheels[wheel]
      const status = reading.state === 'observed' ? '已观测' : reading.state === 'stale' ? '旧观测' : '暂无合格观测'
      return <Button key={wheel} className={`tpms-position-wheel tpms-position-${wheel} tpms-reading-${reading.state} ${selected === wheel ? 'tpms-position-selected' : ''}`}
        data-wheel={wheel} data-state={reading.state} data-selected={selected === wheel} ariaLabel={`${VISUAL_WHEEL_NAMES[wheel]} ${reading.pressureBar == null ? '未知' : reading.pressureBar.toFixed(2) + ' bar'} ${status}`}
        onClick={() => onSelect(selected === wheel ? null : wheel)}><Text className="tpms-position-name">{VISUAL_WHEEL_NAMES[wheel]}</Text>
        <Text className="tpms-position-value">{reading.pressureBar == null ? '—' : reading.pressureBar.toFixed(2)}{reading.pressureBar == null ? '' : ' bar'}</Text>
        <Text className="tpms-position-status">{status}</Text></Button>
    })}</View>
    {selected ? <Text className="interaction-caption">{VISUAL_WHEEL_NAMES[selected]} · {current.wheels[selected].observedAt ? historyDetailDateTime(current.wheels[selected].observedAt) : '独立观测时间未知'}</Text> : null}
    <Text className="interaction-caption">点击轮位查看对应历史；再次点击显示全部。位置示意不代表车型识别。</Text>
  </View>
}

type OpeningProps = { doors?: boolean | null; windows?: boolean | null; frunk?: boolean | null; trunk?: boolean | null }
export function VehicleOpeningsVisual({ doors, windows, frunk, trunk }: OpeningProps) {
  const [expanded, setExpanded] = useState(false)
  const values = [{ key: 'doors', label: '车门', value: doors }, { key: 'windows', label: '车窗', value: windows },
    { key: 'frunk', label: '前备箱', value: frunk }, { key: 'trunk', label: '后备箱', value: trunk }]
  return <View className="interaction-visual openings-visual">
    <Button className="interaction-disclosure" onClick={event => { event.stopPropagation(); setExpanded(!expanded) }}>{expanded ? '收起开口位置示意' : '查看开口位置示意'}</Button>
    {expanded ? <><View className="opening-position-board"><VehiclePositionSchematic />{values.filter(row => row.value === true).map(row =>
      <View key={row.key} className={`opening-highlight opening-${row.key}`} data-opening={row.key} ariaLabel={`${row.label}报告为打开`} />)}</View>
      <View className="opening-status-list">{values.map(row => <Text key={row.key} className={row.value === true ? 'opening-reported' : ''}>{row.label}：{row.value === true ? '报告打开' : row.value === false ? '报告关闭' : '状态未知'}</Text>)}</View>
      <Text className="interaction-caption">车门、车窗为汇总状态；不能据此判断具体哪一扇打开。</Text></> : null}
  </View>
}

export function EmptyHistoryVisual({ kind, failed = false }: { kind: 'drive' | 'charge'; failed?: boolean }) {
  return <View className={`interaction-visual empty-history-visual empty-${kind}`} ariaLabel={failed ? '读取暂不可用' : kind === 'drive' ? '行程空态示意' : '充电空态示意'}>
    {failed ? <Text className="empty-cloud">☁</Text> : kind === 'charge' ? <><Image src={vehicle} mode="aspectFit" className="empty-charge-vehicle" ariaLabel="内置车型示意" /><View className="empty-charge-post"><Text>ϟ</Text></View></> :
      <View className="route-schematic"><View className="route-schematic-line" /><View className="route-point route-point-a">A</View><View className="route-point route-point-b">B</View></View>}
  </View>
}

/** Separate actual intervals; unknown gaps never acquire a stop/charge label. */
export function ParkedChargeTimeline({ sessions, complete = false }: { sessions?: readonly RecordedChargeInterval[]; complete?: boolean | null }) {
  const [selected, setSelected] = useState<string | null>(null)
  if (!sessions?.length) return null
  return <View className="interaction-visual parked-charge-timeline"><Text className="interaction-section-title">已记录充电时段</Text>
    {sessions.map(session => <View className="recorded-charge-row" key={`${session.id}:${session.startDate}`}>
      <View className={`recorded-charge-rail ${session.endDate ? '' : 'recorded-charge-rail-open'}`} ariaLabel={session.endDate ? '已记录起止时间' : '结束时间未记录'} />
      <View className="recorded-charge-copy"><Button className="recorded-charge-select" onClick={() => setSelected(selected === session.id ? null : session.id)}>
        <Text>{historyDetailDateTime(session.startDate)} → {session.endDate ? historyDetailDateTime(session.endDate) : '结束未记录'}</Text>
        <Text className="interaction-caption">{session.energyKwh == null ? '充入电量未记录' : `${session.energyKwh.toFixed(2)} kWh`}</Text></Button>
        {selected === session.id ? <Text className="interaction-caption">本条已保存会话的时间区间；不代表两条会话之间一直未充电。</Text> : null}
      </View></View>)}
    <Text className="interaction-caption">{complete ? '已返回查询范围内保存的会话；不等于车辆全部充电均已采集。' : '部分已保存会话；不据空白区间推断停充或耗电。'}</Text>
  </View>
}
