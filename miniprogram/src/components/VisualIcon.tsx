import { Text, View } from '@tarojs/components'

const marks = { clock: '◷', distance: '↔', speed: '↗', energy: 'ϟ', battery: '▰',
  vehicle: '▱', chart: '▥', cloud: '⌁', shield: '◇', account: '◎', route: '↝', empty: '○' }
export type VisualIconName = keyof typeof marks

/** Local monochrome marks supplement the visible label; they never imply a capability or state. */
export default function VisualIcon({ name }: { name: VisualIconName }) {
  return <View className={`visual-icon visual-icon-${name}`}><Text>{marks[name]}</Text></View>
}
