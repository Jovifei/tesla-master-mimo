import { Image, Text, View } from '@tarojs/components'
import loadingLogo from '../assets/matelink_loading_logo.png'
import { useVisualMotion } from './interaction/useVisualMotion'
import './interaction/interaction.scss'

type Props = {
  label?: string
  compact?: boolean
  active?: boolean
  reducedMotion?: boolean
}

export default function LoadingIndicator({ label = '正在加载数据…', compact = false, active = true, reducedMotion = false }: Props) {
  const motion = useVisualMotion(active, true, reducedMotion)
  return (
    <View className={`loading-indicator ${compact ? 'loading-indicator-compact' : ''}`}>
      <View className="loading-mark">
        <Image className="loading-logo" src={loadingLogo} mode="aspectFit" />
        <View className="loading-beacon" data-motion={motion} style={{ animationPlayState: motion ? 'running' : 'paused', animationIterationCount: '2' }} />
      </View>
      <Text className="loading-label">{label}</Text>
    </View>
  )
}
