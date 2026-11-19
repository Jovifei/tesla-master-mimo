import { Image, View } from '@tarojs/components'
import accountCircle from './icons/material/account_circle.png'
import analytics from './icons/material/analytics.png'
import attachMoney from './icons/material/attach_money.png'
import batteryStd from './icons/material/battery_std.png'
import bolt from './icons/material/bolt.png'
import cloudQueue from './icons/material/cloud_queue.png'
import directionsCar from './icons/material/directions_car.png'
import history from './icons/material/history.png'
import info from './icons/material/info.png'
import map from './icons/material/map.png'
import route from './icons/material/route.png'
import settings from './icons/material/settings.png'
import speed from './icons/material/speed.png'
import timeline from './icons/material/timeline.png'
import update from './icons/material/update.png'
import verifiedUser from './icons/material/verified_user.png'
import metricEnergy from './icons/material/metric-energy.png'
import metricDistance from './icons/material/metric-distance.png'
import metricTime from './icons/material/metric-time.png'
import metricSpeed from './icons/material/metric-speed.png'
import metricCount from './icons/material/metric-count.png'
import metricDistanceSummary from './icons/material/metric-distance-summary.png'
import metricChargeCount from './icons/material/metric-charge-count.png'
import metricChargeEnergy from './icons/material/metric-charge-energy.png'
import metricCost from './icons/material/metric-cost.png'
import metricAvgCost from './icons/material/metric-avg-cost.png'
import chargeDc from './icons/material/charge-dc.png'

// Filled Material Icons, matching the App's MoreScreen.kt icon choices.
// Source: https://github.com/google/material-design-icons (Apache-2.0; see icons/material/LICENSE).
const icons = {
  account: accountCircle,
  battery: batteryStd,
  chart: analytics,
  clock: history,
  cloud: cloudQueue,
  distance: map,
  empty: info,
  energy: bolt,
  info,
  map,
  money: attachMoney,
  route,
  settings,
  shield: verifiedUser,
  speed,
  timeline,
  update,
  vehicle: directionsCar,
  metricEnergy,
  metricDistance,
  metricTime,
  metricSpeed,
  metricCount,
  metricDistanceSummary,
  metricChargeCount,
  metricChargeEnergy,
  metricCost,
  metricAvgCost,
  chargeDc,
}
export type VisualIconName = keyof typeof icons

/** Local bitmap assets render at a fixed 24dp shape without platform font glyph differences. */
export default function VisualIcon({ name }: { name: VisualIconName }) {
  const metric = name.startsWith('metric')
  return <View className={`visual-icon visual-icon-${name}${metric ? ' visual-icon-metric' : ''}`}><Image src={icons[name]} mode="aspectFit" style={{ width: '100%', height: '100%' }} /></View>
}
