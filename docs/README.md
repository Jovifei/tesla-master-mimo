# MateLink 工程审核与故障记录入口

当前审核入口：[2026-10-02 主分支、部署与待修复状态](RPT-2026-10-02-review-baseline.md)。

- [10月4日月柱数值与日期范围](BUG-REPAIR-2026-10-04-monthly-chart-values.md)：常驻真实数值、最高速度标题、选定结束日和部分周；完整Android与手机视觉仍待验收。
- [10月4日电量字段与端点](BUG-REPAIR-2026-10-04-battery-source-contract.md)：前向SOC遗漏、单点误算、旧schema验证及尚未执行的有界恢复方案。
- [10月4日历史页面源链核查](RPT-2026-10-04-history-source-audit.md)：逐项区分真实缺测、漏传、硬编码null、筛选错误和图表展示遗漏。

- [10月4日历史同步与详情复发](BUG-REPAIR-2026-10-04-phone-history-stale.md)：首次恢复、07:51再现、安全异常分型，以及43完整构建/保数据安装与真实详情成功；间歇失败根因仍未闭合。
- [云登录 502 与历史读取内存故障](DBG-2026-10-02-login-oom.md)：运行证据、源码风险、优化方案和验收条件。
- [充电费用与历史同步验收](BUG-REPAIR-2026-10-01-sync-charge-cost.md)。
- [TeslaMate 归档桥接](TESLAMATE-ARCHIVE-BRIDGE.md)。
- 最新执行清单位于 `../tasks/todo.md`，经验规则位于 `../tasks/lessons.md`。

日期较早的文档是历史记录，不代表当前部署或完成状态。代码已合并、测试通过、手机已安装、云端已部署、Fleet 实车采集通过是五个独立门禁。
