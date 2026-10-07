# 字段与通知链路审计（已部署/安装，真实数据验收仍有缺口）

## 实际交付（2026-10-07）

- API产品提交：bb09fac04d11796ce676555dad094776cd1ef0ce，tree e06e83b2dde8ba40601f7a931821f7a6c59d360f。CI-only分支头5ff1d49497b1cfd25be9f96d8d5c88ff3d491b94。
- PR15 Draft：https://github.com/Jovifei/tesla-master-mimo/pull/15；CI37611040504 verify成功，包含全Go/PG、focused/race/vet/build。实现agent本地282记录零失败/跳过，父审查纠正双侧重叠和数组资源问题。
- API实际19:13:20–19:13:42北京时间切换，新build `api-fields-bb09fac-tree-e06e83b2dde8`，imageconfig `5ee77870f46a584201cc16d4564d3113ade3f502dbbe97f08d5891c26c2e09bf`。仅API服务，schema/config/effective-env盲hash不变，历史UPDATE0，bridge未改。原image339ad919及原三配置保留回滚。下次重建必须复用当前四个compose config files，不能沿用旧命令退回旧API。
- 手机产品提交：14ca59d54ba8c470abb45d01798a23919f47bb63；PR16 Draft：https://github.com/Jovifei/tesla-master-mimo/pull/16。
- Debug656、Release656均零失败（Release8跳过），lint0错误/242警告/8信息；最终同签Release/R8成功。APK SHA256 `b7c46f7fbffb88febb9477ae888da4be0f96a6347200a059175ef108546a3fd4`。
- 已实际 `adb install -r` 44→45，即2.1.26/build45；证书9AB144…匹配，firstInstallTime仍2026-08-31 22:36:47，data_dir不变。没有卸载、清数据、清日志或真实设备instrumentation。
- 原远端源码审核：PASS WITH REMAINING ACCEPTANCE GATES；自然新增通知及真实30天胎压手机历史仍OPEN。CI/安装不代替它们。
- 启动采样launch成功、PID16039存活、FATAL0；通用采样器报告全局ANR1/OEM SQLITE_OK，需要按MateLink进程和明确ANR包名复核，尚不将其当MateLink ANR或启动全PASS。
- 最近30天胎压源只读观测：19:01:11北京时间/11:01:11 UTC，181510源记录中7113条有真实胎压；尚未导入手机。补入这批真实历史还是只自然积累的用户范围答复仍待。未知旧历史不自动回填。

### 关键本机证据

- E:/Claude_allow/Download/matelink-api-fields-20261007/actual-api-deployment-receipt.json
- E:/Claude_allow/Download/matelink-api-fields-20261007/candidate-release-identity.json
- E:/Claude_allow/Download/matelink-phone-fields-20261007/actual-device-install.json
- E:/Claude_allow/Download/matelink-phone-fields-20261007/apk-verification.json
- E:/Claude_allow/Download/matelink-phone-fields-20261007/phone-signed-build.log
- E:/Claude_allow/Download/matelink-phone-fields-20261007/verify_tpms_migration_sqlite.py：合成SQLite20→21保留行/零/空并与Room21 schema匹配；不是用户数据库读取验收。

其余以下为本轮审计/实施过程，不覆盖上述当前状态。

## 基线与观测

- 本轮审计起点手机：2.1.25/build44，源码2d1828be；API起点产品源码36d630a8。当前交付见上节。
- 数据观测时间：2026-10-07 18:19:13北京时间 / 10:19:13 UTC。不能当作未来实时数据。
- 最近自然行程源/云均有3100条速度、功率、SOC采样；源70条车外温度，云没有该历史温度字段；云行程总能量缺失。
- 最近三条充电云端有37/35/36条SOC采样与充电能量，费用未知。未知不填0。
- 18:31手机安全UI确认错误发生于驻车详情，文字为`history_not_collected`，不是整体历史断连。用户亦确认页面。
- 本轮只读聚合没有输出用户身份、轨迹、VIN或凭证，没有车辆唤醒、制造行程或历史回填。

## 已确认问题与修复合同

| 字段/功能 | 已确认问题 | 目标行为 |
|---|---|---|
| 能耗 | 内部Wh/km；部分视图直接拼单位；功率积分截断亚秒间隔 | 展示kWh/100km，值为Wh/km÷10；内部和导出单位不偷换；保持估算来源，低于90%采样覆盖不提供整程能耗 |
| 最高速度 | 云端样本有值，API摘要固定null | 当前分页只聚合采样最高速度/均值标量，详情一致；不宣称连续真实峰值 |
| 充电SOC | 轻量列表没有charge_points首尾SOC | 两端独立有效0..100，保留0/null，显示`起始% → 结束%` |
| 驻车 | 旧生产不提供驻车详情，直接显示服务错误标识 | 同owner/车/source且真实相邻已完成合格行程推导older.end→newer.start及观测SOC；无观测的停车能耗/温度/功率保持null |
| 胎压 | 样本不足时用默认值制造7个历史点并写Room；默认7天 | 移除造数据与数值身份回退；默认30天；保留旧记录并标记来源无法验证，不参与真实趋势/提醒 |
| 胎压变化 | 无独立变化幅度提醒 | 仅同车同轮真实有时间观测，明确幅度/窗口规则，去重且尊重通知权限与用户偏好 |
| 行程通知 | 首次空同步没有建立baseline | 成功完整空同步也建立基线；后续自然新增完成记录可通知；旧历史首次导入不重播 |
| 充电通知 | 实时状态轮询不等于新增历史通知 | 完整历史同步后的身份隔离持久事件，系统通知与前台提示独立消费，权限失败可重试 |

## 数据来源限制

Tesla Fleet Telemetry官方定义`EnergyRemaining`为kWh，但字段定义存在不代表本项目个人TeslaMate归档实际采集到它。当前自然行程能耗只能在功率覆盖充分时推导并标明估算，不用SOC百分点直接冒充kWh。

官方字段说明：https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data

## 协作与发布状态

原远端Project/会话审核确认缺口，创建文档提交6db28fbf，但连续三轮未交付源码，报告缺少安全中间编辑能力。本地接手两个精确底座隔离分支，完成后GitHub回传原远端审核，不创建新会话。

- 手机实施分支：codex/phone-field-repair-20261007，底座2d1828be。
- API实施分支：codex/api-field-repair-20261007，底座36d630a8。
- PR12整版驻车不直接合入；旧六条SOC回填未执行；bridge未重复升级。
- 测试、迁移合成验证、签名、部署和覆盖安装已完成；手机全页面真实数据复验、自然新增通知及30天真实胎压历史仍OPEN，不能称整体最终PASS。

## 经验

1. 确认页面和错误字面后再解释；可选驻车未采集不等于行程历史断连。
2. 每个字段分别核对源、存储、接口、缓存、计算、展示；列表与详情契约不同。
3. 不能因样本不足制造历史；旧数据来源无法恢复时保留并隔离未验证证据，不能靠形状猜测删除。
4. 改能耗单位必须同时换数值，直接拼单位的视图也要检查，避免双重转换。
5. 采样功率积分要处理毫秒和覆盖率；部分采样能量除全程距离会误导用户。
6. 通知需要完整分页基线、身份、历史导入边界和持久去重，Android权限成功不代表自然事件闭环已验收。
