import { Text, View } from '@tarojs/components'

export default function DrivesPage() {
  return (
    <View className="page">
      <View className="card">
        <Text className="title">行程</Text>
        <Text className="muted">M1 先保留分页和证据状态边界。没有车辆会话时不生成示例行程；详情与路线将在 M4 接入。</Text>
      </View>
      <View className="card">
        <Text className="section-title">等待账号关联</Text>
        <Text className="muted">云端历史只按已验证账号和稳定车辆恢复，不能凭 VIN、车辆名或客户端 ID 认领。</Text>
      </View>
    </View>
  )
}
