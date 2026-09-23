import { Image, Text, View } from '@tarojs/components'
import loadingLogo from '../assets/matelink_loading_logo.png'

type Props = {
  label?: string
  compact?: boolean
}

export default function LoadingIndicator({ label = '正在加载数据…', compact = false }: Props) {
  return (
    <View className={`loading-indicator ${compact ? 'loading-indicator-compact' : ''}`}>
      <View className="loading-mark">
        <Image className="loading-logo" src={loadingLogo} mode="aspectFit" />
        <View className="loading-beacon" />
      </View>
      <Text className="loading-label">{label}</Text>
    </View>
  )
}
