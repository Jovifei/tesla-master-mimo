# MateLink

MateLink 是面向 Tesla 车辆数据的多端产品工程。长期客户端包括 Android App、iOS App、Web 和微信小程序；Web 是正式客户端，将逐步与 App 对齐数据语义和主要交互。未来保留硬件方向，首款设备用途、接口与原理图尚未确定。

| 目录 | 内容 |
| --- | --- |
| `android/` | Kotlin／Compose App、本地 Room 与设备测试 |
| `ios/` | SwiftUI App 工程；编译与签名需要 macOS |
| `web_matelink/` | React／Vite Web 客户端 |
| `miniprogram/` | Taro 微信小程序 |
| `deploy/` | Go 服务端、归档桥与候选部署配置 |
| `shared/` | 共享类型与开发用示例数据 |
| `docs/` | 当前产品方向、数据来源、安全、部署与验证入口 |

多用户正式数据以用户自己的 Tesla 官方授权和 Fleet Telemetry 为来源；Jovi 个人 TeslaMate 仅用于独立历史归档。各客户端必须区分真实数据、估算、未知和演示数据；列表、详情及统计应使用相同的身份、单位和资格口径。源码存在、CI 通过、手机安装、微信体验版和生产部署是不同状态。

从 [文档入口](docs/README.md) 阅读 [产品方向](docs/PRODUCT-DIRECTION.md)、[架构与数据流](docs/ARCHITECTURE.md)和 [验证与发布门](docs/VALIDATION.md)。当前执行项见 [任务页](tasks/todo.md)。旧阶段方案与过程报告已在仓库外按哈希归档，亦可从清理前的 Git 基线 `49ee6c7423d6d61c50b511eb50f5055316ed691a` 查找；本次文档整理不代表功能已上线。

常用本地命令（在对应目录运行）：

```text
android/        .\gradlew.bat :app:testDebugUnitTest
web_matelink/   npm run build
miniprogram/    npm run typecheck && npm run build:weapp
```

提交前分别核对当前分支、工作区改动与对应测试。不要把凭证、车辆轨迹、手机数据库或归档副本提交到仓库。
