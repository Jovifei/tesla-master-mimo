import { subscribeAppearance, syncAppearanceChrome } from './services/appearance'
import { PropsWithChildren, useEffect } from 'react'
import Taro from '@tarojs/taro'
import { ensureWechatSession } from './services/api'
import './app.scss'

export default function App({ children }: PropsWithChildren<unknown>) {
  useEffect(() => {
    void ensureWechatSession()
    const applyTheme = ({ theme }: { theme: string }) => syncAppearanceChrome(theme)
    syncAppearanceChrome()
    const unsubscribe = subscribeAppearance(() => syncAppearanceChrome())
    Taro.onThemeChange(applyTheme)
    return () => { Taro.offThemeChange(applyTheme); unsubscribe() }
  }, [])
  return children
}
