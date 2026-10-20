# MateLink build62 数据真实性第二轮复审包

状态：源码候选，最终 HEAD/tree/parent、完整 CI、手机和网页端终审待填。此文只记录可验证的源码与隔离测试，不把合成回放当自然 Fleet 或真机结论。

## 身份与保护边界

- 基于 `main@49ee6c7423d6d61c50b511eb50f5055316ed691a` 的独立 `codex/apk-data-ui-integrity-20261021` 分支；上一候选 `7512fd843a9d61a19ef5a2e702981d4212882fc2` 的 [Actions 39493998325](https://github.com/Jovifei/tesla-master-mimo/actions/runs/39493998325) 16/16 SUCCESS，但同任务 C2C 对其裁决为 `CHANGES_REQUIRED`。[完整 A–F 方案](https://github.com/Jovifei/tesla-master-mimo/blob/1c905040a9477d6feea27a78236a6bd546a8ed46/docs/PLAN-build61-data-ui-c2c-8f2d.md)与 [PR #22](https://github.com/Jovifei/tesla-master-mimo/pull/22) 继续沿用，不新建任务。
- 原 `E:\project\tesla_master\app_mimo` 是小程序 Owner 的脏工作树，不从那里构建 APK，不重置或覆盖。手机当前仍为 build61；本轮只使用隔离模拟器、Room 和合成服务，没有安装新包、清理手机数据、访问生产数据库或唤醒车辆。

## 第二轮源码接线与数据合同

| 问题 | 当前修复与用户可见语义 | 实际证据 |
| --- | --- | --- |
| Stats/年度跨修订 | 同一 Room 事务读取有界摘要、手工费用、首日及 Deep 聚合，事务外生成 Quick、coverage、月图与年度卡；身份/代际前后守护。同步进度与外部服务能力仍是独立时钟，不宣称数据库 revision ID。 | 真实 Room 写事务闸门、跨年和 UNKNOWN 类聚焦；完整复跑待填。 |
| 费用/币种 | v21→22 增量迁移保留旧纯金额为“币种待确认”；新手工价以本地车辆、充电 ID、source/source instance、精确会话窗、币种和版本绑定。同数字 ID 的不同会话并存；迟到版本和清除墓碑不能覆盖。原始无币种金额可见但不计入当前币种的可比总额；手工 0 是有效值，原始 0 不冒充免费。 | 费用 6 项实际 RED→GREEN；真实迁移与费用 UI 隔离 API35 15/15 PASS。 |
| 费用跨页 | History、详情、Cost、Stats 最贵记录、Timeline、Mileage、Trips、Annual 以相同 typed 投影消费；不把手工价写回原始 API receipt，时间线不再写死人民币符号。 | 9 类聚焦 59/59 PASS；完整复跑待填。 |
| 账号/车辆迟到发布 | Efficiency、Mileage 辅助单位读取及 Cost/Timeline 的请求代际与 origin/账号/稳定车辆/source proof 守护；同数字 ID A→B→A 不能发布旧值、旧错误或旧货币/单位。 | 实际 ViewModel 12 项中 11 项旧行为 RED，修复后相关聚焦 GREEN。 |
| D12 同 ID 充电 | 同 source 标签和 Instant 会话窗之外，已知 source-instance/source record 元组必须严格一致；一侧缺失不继承旧合格证明。双方未知沿既有 legacy 边界保留原 receipt，不生成实例证明；弱 null、显式 unknown 与真 0 各自保留语义。 | 真实 DAO/Unified/64 条两页/Room 重开 5 项中 3 项 RED→新类 6/6 GREEN，相关 20/20 PASS。不是手机 64 条真实原始回执对账。 |
| D10 详情原始点 | v22→23 只新增 receipt/chunk 表和索引；充电/行程详情与后台 Sync 共用同 scope/source/instance/record/Instant 窗的事务准入。Moshi typed 点块按序存 null、0、负值与同刻样本，受单块/单响应/总库预算限制；配额或取消回滚本次摘要+点写入，旧可读修订留存。损坏块重复下载可追加修复修订。断网详情只从同一已验证摘要读 hash/count/身份正确的点，不从聚合重画曲线。 | v22 真实 Room 缺表 1 项 RED；新 Room6/6、codec3/3、充电/行程 VM 各1/1、真实 Sync9/9，合计20/20 GREEN；v21/v22→23 隔离 API35 13/13 PASS。 |
| 剩余统计与文案 | 有证据的 AC/DC 与 UNKNOWN 分开；零/负能耗仍合法；行程样本算术均速/功率标“采样均值”，API 真均值含 0 保持原标签；年度待机按 Instant、本地时区及相邻有效 SOC 窗计算，无可信容量则未知。 | Stats/年度/采样与待机聚焦 GREEN；完整复跑待填。 |

## 实际 RED→GREEN 位置

- 本地完整日志与 XML 只保存在 `E:\Claude_allow\Download\matelink-ui-audit-20261020\`。其中 `iter2-cost-d12-red-01.log` 是费用契约 6 项真实 RED，64 条合成两页旧链 3 项 PASS；`iter2-scope-red-01.log` 是 ViewModel/标签 12 项中 11 项 RED；`iter2-d12-instance-red-02.log` 是来源实例真实 DAO/分页 5 项中 3 项 RED；`iter2-detail-room-red-01.log` 是 v22 真实 Room 缺表 1 项 RED。
- 修复后的 `iter2-integrated-focused-03.log` 9 类 59/59 PASS；`iter2-d12-green-d10-focused-03.log` 20/20 PASS；`iter2-v22-migration-native-02.log` 15/15 PASS；`iter2-v23-migration-native-02.log` 13/13 PASS；`iter2-d10-repair-consumers-04.log` 20/20 PASS。上述测试均为合成/模拟器证据，不能代替真实设备同修订的来源回执。
- 第二轮完整本地 `iter2-full-android-precommit-02.log` 已实际退出 0：Debug JUnit XML 1191/1191，Release XML 1191 项、0 fail/error、8 项既有预期 skip；Debug lint 0 error/288 warning、Release lint 0 error/268 warning；Debug/Release assemble 及 Release R8 成功。此构建仍内嵌上一候选 Git SHA，最终固定提交后须重新构建、验签并单独计算正式包哈希。
- 第二轮完整隔离模拟器 `iter2-full-emulator-precommit-01.log` 已实际退出 0：API35 最终 XML `tests=76`、0 fail/error、2 项既有“两次安装”流程 skip；控制台汇总包含该两项，不能写作已完成真实升级前后验证。原生迁移、Room 与 Compose 均在本轮源码树执行。XML 仅存本机允许证据目录；不是手机自然历史或 provider 验收。

## 资格限制与待验收

- 详情 API 目前没有 provider 总点数、游标或完整修订号；本地状态是“本次响应已保存、提供方总历史完整性未验证”。编码器保存 DTO 可识别的原始点字段，不是 HTTP 逐字节归档；未知 JSON 字段仍可能丢失。超预算停止新写入并保留旧可读点，不能宣称完整冷副本。
- 驻车 API 对该停车窗口没有温度/功率逐点证据时继续未知；本轮没有补造。现实手机 64 条充电的 967→920 kWh 变化尚未取得同 scope/window/revision 的 wire/Room 前后哈希，合成 64 条测试不能证明现实总计应为任何固定值。
- 自然新 Fleet 行程、充电、逐轮胎压与提醒送达，多用户实际账户、真机页面/无障碍截图、微信小程序体验版和生产部署均为独立验收。原 build61 APK 已只读保留；v23 升级后旧 APK 未必能直接读取新 Room，不能把“装回旧 APK”写成已验证数据回滚。
- 下一步：完整 Debug/Release JUnit、双 lint、R8、完整隔离 API35、GitHub 同新 SHA Go/PG/race/TLS/Web/Mini/审计及同一 C2C 终审。全部通过后再核签名/安装资格、手机保数据覆盖与主分支合并；生产 DDL/回填、车辆 wake、历史清理不在本轮执行。
