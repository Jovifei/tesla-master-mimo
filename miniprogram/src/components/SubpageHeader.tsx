import { Button, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { getAppNavGeometry } from '../services/nav_geometry'

type Props = {
  title: string
  subtitle?: string | null
}

export default function SubpageHeader({ title, subtitle }: Props) {
  const nav = getAppNavGeometry()
  const goBack = async () => {
    try {
      const stack = typeof Taro.getCurrentPages === 'function' ? Taro.getCurrentPages() : []
      if (stack.length > 1) await Taro.navigateBack({ delta: 1 })
      else await Taro.switchTab({ url: '/pages/me/index' })
    } catch {
      try { await Taro.switchTab({ url: '/pages/me/index' }) } catch { /* navigation remains unavailable */ }
    }
  }

  return <View
    className="subpage-header"
    style={{ paddingTop: `${nav.navBarTop}px`, paddingRight: `${nav.capsuleRightPadding}px`, minHeight: `${nav.headerTotalHeight}px` }}
  >
    <Button className="subpage-back" ariaLabel="返回" onClick={() => { void goBack() }}>‹</Button>
    <View className="subpage-heading">
      <Text className="subpage-title">{title}</Text>
      {subtitle ? <Text className="subpage-subtitle">{subtitle}</Text> : null}
    </View>
  </View>
}
