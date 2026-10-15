import { PropsWithChildren, useEffect } from 'react'
import Taro from '@tarojs/taro'
import './app.scss'

export default function App({ children }: PropsWithChildren<unknown>) {
  useEffect(() => {
    const applyTheme = ({ theme }: { theme: string }) => {
      const dark = theme === 'dark'
      void Taro.setNavigationBarColor({ frontColor: dark ? '#ffffff' : '#000000', backgroundColor: dark ? '#111315' : '#f5f7f9' }).catch(() => {})
      void Taro.setTabBarStyle({ color: dark ? '#b7bdc6' : '#737373', selectedColor: dark ? '#f5f6f7' : '#171717', backgroundColor: dark ? '#15181c' : '#ffffff', borderStyle: dark ? 'black' : 'white' }).catch(() => {})
    }
    applyTheme({ theme: Taro.getSystemInfoSync().theme || 'light' })
    Taro.onThemeChange(applyTheme)
    return () => Taro.offThemeChange(applyTheme)
  }, [])
  return children
}
