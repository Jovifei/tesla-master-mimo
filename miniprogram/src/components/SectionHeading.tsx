import { Text, View } from '@tarojs/components'
import VisualIcon, { type VisualIconName } from './VisualIcon'

type Props = {
  icon: VisualIconName
  title: string
}

export default function SectionHeading({ icon, title }: Props) {
  return <View className="android-section-heading">
    <VisualIcon name={icon} />
    <Text className="section-title">{title}</Text>
  </View>
}
