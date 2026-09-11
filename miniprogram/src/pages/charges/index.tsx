import { Text, View } from '@tarojs/components'

export default function ChargesPage() {
  return (
    <View className="page">
      <View className="card">
        <Text className="title">充电</Text>
        <Text className="muted">M1 不扩大 Tesla 充电控制权限。没有服务端真实会话时，当前充电和历史均显示不可用。</Text>
      </View>
      <View className="card">
        <Text className="section-title">数据来源</Text>
        <Text className="muted">能量、功率、费用和地点分别保留来源；缺失费用不会显示为 0。</Text>
      </View>
    </View>
  )
}
