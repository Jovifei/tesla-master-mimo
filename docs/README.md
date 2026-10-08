# MateLink 工程审核与故障记录入口

当前阶段入口：[能耗阶段本地签名与实机验证](ENERGY-LOCAL-DEVICE-QUALIFICATION-20261009.md)。2026-10-09：能耗候选2.1.27/build46已同签保留数据安装；真实认证历史仍遇TLS失败，main尚未合并，生产API仍是独立底座。不要将构建、安装或health200等同真实数据验收。

- [完整能耗修复范围与官方公式依据](ENERGY-REPAIR-CLEANUP-STAGE-20261008.md)。
- [固定源码交接与根因、回滚](ENERGY-STAGE-FIXED-SHA-HANDOFF-20261009.md)。
- [实机TLS分型与安全排查](ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md)。
- [历史基线：2026-10-02部署与待修复状态](RPT-2026-10-02-review-baseline.md)。

- [云登录 502 与历史读取内存故障](DBG-2026-10-02-login-oom.md)：运行证据、源码风险、优化方案和验收条件。
- [充电费用与历史同步验收](BUG-REPAIR-2026-10-01-sync-charge-cost.md)。
- [TeslaMate 归档桥接](TESLAMATE-ARCHIVE-BRIDGE.md)。
- 最新执行清单位于 `../tasks/todo.md`，经验规则位于 `../tasks/lessons.md`。

日期较早的文档是历史记录，不代表当前部署或完成状态。代码已合并、测试通过、手机已安装、云端已部署、Fleet 实车采集通过是五个独立门禁。
