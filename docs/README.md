# MateLink 文档入口

MateLink 的长期方向、现有代码与待验证能力分别记录；不要用阶段报告推断当前生产状态。`main`、功能分支、手机安装版、服务端部署版和真实车辆采集须分别核对。

| 阅读目的 | 文档 |
| --- | --- |
| 产品范围与 App／Web／小程序／未来硬件方向 | [产品方向](PRODUCT-DIRECTION.md) |
| 数据从哪里来、身份与保存边界 | [架构与数据流](ARCHITECTURE.md)、[字段目录](DATA-CATALOG.md) |
| Fleet 多用户与个人 TeslaMate 归档的决策 | [现行架构决策](ARCH-DECISION-2026-09-27-fleet-teslamate-hybrid.md)、[归档桥](TESLAMATE-ARCHIVE-BRIDGE.md) |
| 云端接口与部署 | [接口合同](JOURVOLT-CLOUD-API-CONTRACT.md)、[部署与运行资料](DEPLOYMENT.md)、[服务器密钥设置](JOURVOLT-ECS-SECRET-SETUP.md) |
| Android 安装和跨层验收 | [验证与发布门](VALIDATION.md)、[设备操作规程](SOP-ANDROID-DEVICE-DEPLOYMENT-AND-VERIFICATION.md) |
| 小程序接口与实现选择 | [小程序接口](miniprogram/INTERFACE-CONTRACT-M1.md)、[框架决策](miniprogram/ADR-0001-client-framework.md) |
| 提议中的分层存储 | [存储 ADR](ADR-2026-10-02-tiered-history-storage.md)；状态仍是提案，不是已迁移或删除策略 |

当前执行项放在 [任务页](../tasks/todo.md)，可重复使用的纠错规则放在 [经验页](../tasks/lessons.md)。历史阶段方案、审核报告和交接包已按 2026-10-23 清理批次保存在仓库外归档及 Git 基线提交 `49ee6c7423d6d61c50b511eb50f5055316ed691a`；需要旧文件时用该提交的路径检索。归档副本由项目持有人保管，不包含在公开仓库中。
