# MateLink 工程审核与故障记录入口

当前阶段入口：[能耗传输、部署TLS与已部署bb09兼容性终审](ENERGY-TRANSPORT-COMPATIBILITY-FINAL-STAGE-20261009.md)；[旧设备签名实测、认证TLS待确认门](ENERGY-TLS-DEVICE-DIAGNOSTIC-20261009.md)。源码CI通过不代表手机认证或Fleet自然数据通过。

- [完整固定SHA本地接收、独立签名安装与人类验收矩阵](TLS-ENERGY-FIXED-STAGE-LOCAL-RECEIVE-20261009.md)：由PR17最终完成评论获取不可变版本；原本地Codex只作独立验证，不代远端修业务代码。

历史审核入口：[2026-10-02 主分支、部署与待修复状态](RPT-2026-10-02-review-baseline.md)。

- [10月4日历史同步与详情复发](BUG-REPAIR-2026-10-04-phone-history-stale.md)：首次恢复、07:51再现、安全异常分型、未知SOC及43候选验收边界。
- [云登录 502 与历史读取内存故障](DBG-2026-10-02-login-oom.md)：运行证据、源码风险、优化方案和验收条件。
- [充电费用与历史同步验收](BUG-REPAIR-2026-10-01-sync-charge-cost.md)。
- [TeslaMate 归档桥接](TESLAMATE-ARCHIVE-BRIDGE.md)。
- 最新执行清单位于 `../tasks/todo.md`，经验规则位于 `../tasks/lessons.md`。

日期较早的文档是历史记录，不代表当前部署或完成状态。代码已合并、测试通过、手机已安装、云端已部署、Fleet 实车采集通过是五个独立门禁。
