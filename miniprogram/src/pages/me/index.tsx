import { Button, Text, View } from '@tarojs/components'
import Taro from '@tarojs/taro'
import { matelinkApi } from '../../services/api'

export default function MePage() {
  const startWechatSession = async () => {
    try {
      await matelinkApi.loginWithWechat()
    } catch (error) {
      Taro.showToast({
        title: error instanceof Error ? error.message : '暂不可用',
        icon: 'none',
        duration: 2600,
      })
    }
  }

  return (
    <View className="page">
      <View className="card">
        <Text className="title">我的</Text>
        <Text className="muted">微信身份只用于建立小程序会话。Tesla 授权仍在官方页面完成，令牌只留在服务端。</Text>
        <Button className="button" onClick={startWechatSession}>微信登录（M2）</Button>
      </View>
      <View className="card">
        <Text className="section-title">当前 M0/M1 边界</Text>
        <Text className="muted">AppID、主体、业务域名、账号关联接口和两平台真机授权尚未验证。请勿将测试配置当作发布能力。</Text>
      </View>
    </View>
  )
}
