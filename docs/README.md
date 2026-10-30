# MateLink 工程审核与故障记录入口

当前最新交付：[驻车详情重设计与冷启动缓存修复 (Build 70 / v2.1.51)](ANDROID-RELEASE-LOG.md)。2026-10-25（第三轮）：驻车详情分层卡片重设计（概要/电量/云端遥测，消除满屏"暂无数据"）；充电/行程列表冷启动经离线映射直读 Room 缓存（实测杀进程 2 秒渲染 67 次充电记录）。

上一交付：[驻车详情本地优先修复 (Build 69 / v2.1.50)](ANDROID-RELEASE-LOG.md)。2026-10-25（第二轮）：驻车详情由相邻本地行程秒渲染 + 20s 云端增强 + 降级横幅/重试；充电段不再冒充能耗估算；同签覆盖安装并实机验证。

上一交付：[实机 UI 审计修复 (Build 68 / v2.1.49)](ANDROID-RELEASE-LOG.md)。2026-10-25：充电/行程页 20s 云端同步超时降级、行程卡片完整 kWh/100km 单位、驻车 SOC 格式统一、能耗徽标对齐、仪表盘温度占位；全量测试 1026×2 通过，同签覆盖安装并实机逐项验证。

上一交付：[充电历史与行程页面加载体验、本地持久化与无限滚动方案 (Build 67 / v2.1.48)](TECH-2026-10-23-history-loading-cache-pagination.md)。2026-10-24：完成彻底消除整屏空白遮罩、本地 SQLite 优先读取 0ms 呈现、充电默认 30 天 / 行程默认 7 天、触底无限自动分页及真机（OnePlus 7 Pro）实测通过。

历史阶段入口：[能耗最终源码实测与待确认门](ENERGY-LOCAL-FINAL-QUALIFICATION-8625D28E-20261009.md)。2026-10-09：能耗候选2.1.27/build46已同签保留数据安装；真实认证历史仍遇TLS失败，main尚未合并，生产API仍是独立底座。不要将构建、安装或health200等同真实数据验收。

- [完整能耗修复范围与官方公式依据](ENERGY-REPAIR-CLEANUP-STAGE-20261008.md)。
- [固定源码交接与根因、回滚](ENERGY-STAGE-FIXED-SHA-HANDOFF-20261009.md)。
- [实机TLS分型与安全排查](ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md)。
- [历史基线：2026-10-02部署与待修复状态](RPT-2026-10-02-review-baseline.md)。

当前阶段入口：[能耗传输、部署TLS与已部署bb09兼容性终审](ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md)；[旧设备签名实测、认证TLS待确认门](ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md)。源码CI通过不代表手机认证或Fleet自然数据通过。

- [完整固定SHA本地接收、独立签名安装与人类验收矩阵](TLS-ENERGY-FIXED-STAGE-LOCAL-RECEIVE-20261009.md)：由PR17最终完成评论获取不可变版本；原本地Codex只作独立验证，不代远端修业务代码。

历史审核入口：[2026-10-02 主分支、部署与待修复状态](RPT-2026-10-02-review-baseline.md)。

- [10月4日历史同步与详情复发](BUG-REPAIR-2026-10-04-phone-history-stale.md)：首次恢复、07:51再现、安全异常分型、未知SOC及43候选验收边界。
- [云登录 502 与历史读取内存故障](DBG-2026-10-02-login-oom.md)：运行证据、源码风险、优化方案和验收条件。
- [充电费用与历史同步验收](BUG-REPAIR-2026-10-01-sync-charge-cost.md)。
- [TeslaMate 归档桥接](TESLAMATE-ARCHIVE-BRIDGE.md)。
- 最新执行清单位于 `../tasks/todo.md`，经验规则位于 `../tasks/lessons.md`。

日期较早的文档是历史记录，不代表当前部署或完成状态。代码已合并、测试通过、手机已安装、云端已部署、Fleet 实车采集通过是五个独立门禁。
