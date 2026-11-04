# 设计文档：Fleet 展示字段扩展与车辆控制一期

- 日期：2026-11-05
- 状态：**待 owner 审阅**（本文只做设计，不含代码变更）
- 基线：main @ 427de74e273cd86527aca4b8fd1386e38c1336e8（v2.1.48 / build 67）
- 依据：2026-11-04 全仓通读 + 线上实测（healthz/readyz）；结论先行版见审查记录
- 范围：Android 先行、微信小程序跟进；Web 与 iOS 不在本设计内（按既定顺序后置）

---

## 1. 背景与目标

MateLink 当前是纯展示应用：数据面（Fleet Telemetry 直推 + Fleet API 状态拉取 + TeslaMate 个人归档）已经打通，但只用了官方数据的一个子集，且没有任何车辆命令通道。本设计分三期补齐：

- **一期（展示）**：把 Fleet API 已经返回、但在后端解析层被丢弃的字段透传到 App；新增「附近充电站」。
- **二期（控制）**：在现有 Command Proxy 与虚拟钥匙地基上，加一层带安全约束的命令通道与 Android 控制区，先低风险命令、owner 账号灰度。
- **三期（深化）**：Fleet Telemetry 订阅字段扩展（安全/电池深层字段），地理围栏方案评估。

非目标：不做远程启动（drive）与 PIN 类命令（见 §6.4 排除清单）；不改变多人架构与双来源（telemetry / teslamate_archive）边界；不收集、不经手用户 Tesla 密码（架构红线，见 ARCH-DECISION-2026-09-27 §6）。

## 2. 现状基线（2026-11-04 已核实）

| # | 事实 | 证据 |
| --- | --- | --- |
| B1 | 线上后端健康：fleet 模式、Postgres、非 mock | `GET /healthz` → mode=fleet, persistence=postgres, mock_history=false, build 02d2ea1（2026-11-02） |
| B2 | Fleet Telemetry 已收到真实数据（不再是 awaiting_first_event） | `GET /readyz` → telemetry=ok |
| B3 | 用户 OAuth scope 只有 `openid, offline_access, vehicle_device_data, vehicle_location`，**没有 vehicle_cmds / vehicle_charging_cmds** | `deploy/jourvolt-dev-mock/tesla_oauth.go:55` |
| B4 | 合作伙伴令牌 scope 含 vehicle_cmds + vehicle_charging_cmds（应用层） | `deploy/jourvolt-dev-mock/tesla_partner.go:15` |
| B5 | 后端 vehicle_data 解析未包含车窗/车门细分、媒体、软件更新进度、座椅加热等字段（解析结构体中无对应字段，响应中即使有也被丢弃） | 对 `deploy/jourvolt-dev-mock/*.go` 全量检索 MediaState/FdWindow/SeatHeater/SoftwareUpdate 等均无命中 |
| B6 | 遥测配置下发走 Command Proxy + 用户令牌，已有自动配置与授权后重试机制 | `telemetry_service.go` commandProxyRequestWithToken；`telemetry_automatic_setup.go` maybeAutoConfigure |
| B7 | Telemetry 订阅字段共 32 个，聚焦行车/充电重建 | `telemetry_core.go` telemetryFieldSpecs |
| B8 | Android API 层无任何车辆命令端点；仅有的 POST 是 telemetry/configure、history/import、summaries 上传与邀请校验 | `android/.../data/api/TeslaMateApi.kt` 全量 |
| B9 | App 状态模型入口为 CarStatusResponse/CarStatus，仪表盘呈现集中在 DashboardScreen + VehicleStatusPresentation | `data/api/models/CarModels.kt`、`ui/screens/dashboard/` |

**由 B3 推出的关键前置**：二期控制必须先给用户授权补 vehicle_cmds（充电命令另需 vehicle_charging_cmds），存量用户需要一次增量重新授权；这与 B6 不矛盾——遥测配置下发是系统行为，用户主动命令是另一条授权语义，特斯拉按用户令牌的 scope 校验。

## 3. 总体原则

1. **数据诚实（沿用现有）**：新字段一律可空；缺失显示「不可用/—」，不回填 0 值、不拿旧值冒充实时值；UI 标注来源（telemetry / fleet_api / archive）与数据时间。
2. **兼容优先**：后端状态 JSON 只做「新增可选字段」，不改既有字段语义；旧版本 App 忽略新字段即可，不做强制升级。
3. **命令安全默认拒绝**：命令层默认拒绝一切未明确放行的命令；每条命令声明风险级别、前置状态条件与是否需要二次确认（见 §6）。
4. **灰度与可回滚**：每期先 owner 账号灰度；后端功能开关可独立关停命令层而不影响数据面。
5. **审计先行**：命令审计日志先于命令 UI 上线；没有审计，不开放命令。

## 4. 一期：展示字段透传

### 4.1 字段清单

来源均为后端已在拉取的 vehicle_data 响应区段（core），改动集中在「解析 → 快照合并 → 状态序列化 → App 模型 → 呈现」一条链。

| 分组 | 字段（Fleet 名） | 展示位置（Android） | 备注 |
| --- | --- | --- | --- |
| 车身 | df/dr/pf/pr（四门）、fd/fp/rd/rp_window（四窗）、ft/rt（前后备箱）、is_user_present、center_display_state | 仪表盘新增「车身状态」卡：车图/文字矩阵呈现开合 | 与 telemetry 的 DoorState 位图交叉校验；以 vehicle_data 为准展示 |
| 媒体 | media_state / media_info（display_title、artist、album、playback 状态） | 仪表盘媒体条（有播放时才出现） | 无播放时整条隐藏，避免空占位 |
| 软件更新 | software_update（status、download_percent、install_percent、expected_duration_sec） | 仪表盘更新提示升级为进度条；updates 屏复用 | 现有仅有「有无更新+版本号」 |
| 空调 | driver_temp_setting、passenger_temp_setting、fan_status、seat_heater_*（五座）、steering_wheel_heater、is_front/rear_defroster_on、defrost_mode、climate_keeper_mode、cabin_overheat_protection(+temp) | 仪表盘空调区展开为详情组；temperature 屏复用 | 目标温度与当前温度并列，解决「设了多少度」不可见 |
| 充电 | conn_charge_cable、fast_charger_brand/type、charge_port_latch、battery_heater_on、scheduled_charging_start_time、scheduled_departure_time、minutes_to_full_charge | charges/current 充电中页面补全；仪表盘充电卡显示预约信息 | 预约类字段是二期「预约充电/出发」控制的展示前置 |
| 限速/代客 | speed_limit_mode（active、current_limit、pin_code_required）、valet_mode | 仪表盘安全行（与锁、哨兵并列） | 只展示状态；开关控制不在一期 |

### 4.2 后端改动点（deploy/jourvolt-dev-mock）

- `fleet_provider.go`：扩展 vehicle_data 解析结构（charge_state / climate_state / drive_state / vehicle_state 四个区段的新字段），保持现有「core 与 location 分开取」逻辑不变。
- 快照合并（fleet snapshot merge）：新字段纳入合并与新鲜度判定；规则沿用现有——位置成功不算完整快照，新字段缺失不污染旧字段。
- 状态序列化：在现有 CarStatus JSON 上新增可选字段（snake_case，与既有风格一致）；字段命名在实施前出一张对照表（Fleet 名 → API 名 → App 模型名）附在 PR 里。
- 测试：沿用现有 fleet_snapshot_merge_test 等模式，为每个新区段加解析与合并用例；mock 数据同步补齐（mock/real 边界不变，mock 必须显式标注）。

### 4.3 Android 改动点

- `data/api/models/CarModels.kt`：CarStatus 扩展新字段（全可空、带默认值，保证旧后端可解析）。
- `ui/screens/dashboard/VehicleStatusPresentation.kt` + `DashboardScreen.kt`：车身状态卡、媒体条、更新进度、空调详情组、预约信息行。
- 数据诚实：每个新组件处理三态（有值 / 无值 / 过期），过期沿用现有 readiness 的时间标注方式。

### 4.4 小程序跟进（一期后段）

- 同字段清单在 miniprogram 侧对齐：先仪表盘（车身/空调/充电预约），再 charges 页；与正在进行的「小程序与 App 对齐」PR 线（#25/#24/#19）合并规划，避免双线改同一页面。

## 5. 一期附带：附近充电站

- 后端新增代理端点 `GET /api/v1/cars/{carId}/nearby-charging-sites`，透传 Fleet API nearby_charging_sites（按车辆当前位置），返回站点名、距离、桩数/可用数、类型（超充/目的地）。
- 配额纪律：该接口计入数据类调用；后端做 10 分钟级缓存（按车辆），App 只在用户打开补能页或下拉刷新时请求，不进自动轮询。
- Android 新增「附近补能」页：列表 + 地图标记（复用现有高德地图能力）；小程序同页后置到 §4.4 同批。

## 6. 二期：车辆控制一期

### 6.1 前置条件（缺一不可）

| # | 条件 | 责任方 |
| --- | --- | --- |
| P1 | 开发者后台 MateLink 应用配置核对：应用本身允许 vehicle_cmds / vehicle_charging_cmds | **owner**（开发者账号在其手中） |
| P2 | `tesla_oauth.go` 用户授权 scope 增加 vehicle_cmds、vehicle_charging_cmds | 开发（本文档批准后） |
| P3 | 存量用户增量授权：后端能识别令牌 scope 不足 → App 内引导重新授权（一次跳转，密码仍只在特斯拉页面）→ 后端置 commands_ready 标记 | 开发 |
| P4 | 命令审计表与限频先行上线（§6.3） | 开发 |

### 6.2 命令通道设计

- 执行路径复用现有 Command Proxy 通道与用户令牌（B6），不新建签名基建；命令目标车辆必须已完成虚拟钥匙配对（未配对直接返回 `key_not_paired`，App 引导去配对）。
- 新增端点（后端）：

```text
POST /api/v1/cars/{carId}/commands/{command}
Body: { "params": { ... }, "confirm_token": "<二次确认凭证，敏感命令必填>" }
200:  { "result": "accepted|executed", "command": "...", "verified_state": { ...回读结果... } }
4xx:  { "error": "not_owner | vehicle_moving | not_plugged_in | key_not_paired |
                  scope_missing | confirmation_required | rate_limited | commands_disabled" }
```

- 每条命令的服务端流程：归属校验（用户-车辆）→ 功能开关与灰度校验 → scope 校验 → **前置状态校验**（读最新快照：行驶类状态 gear≠P 或 speed>0 时拒绝车身/充电类命令；充电命令校验 plugged_in）→ 下发 → **结果回读复核**（短延迟后刷新快照，比对期望状态，回读不一致标 `unverified` 并如实返回，不报假成功）→ 写审计。
- 幂等与超时：同一命令 10 秒内重复提交合并为一次；命令超时按「未知结果」处理并提示用户以车况为准，不自动重试开锁类命令。

### 6.3 审计与限频

- 审计表 `vehicle_command_audit`：id、user_id、vehicle_id、command、params（脱敏摘要）、pre_state 摘要、result、error_class、tesla_request_id（如有）、created_at。只追加，不修改；App 提供「我的命令历史」只读入口。
- 限频：per-user 与 per-vehicle 双维度日预算（初值建议：普通命令 30 次/日、敏感命令 5 次/日，灰度期 owner 不限但全记录）；Fleet 侧配额告警阈值在后端监控（接近应用级配额时先降级非必要数据拉取，命令优先）。
- Kill switch：后端配置 `commands_enabled=false` 立即停用命令层，数据面不受影响。

### 6.4 一期命令集与风险分级

| 命令 | 级别 | 前置条件 | 二次确认 |
| --- | --- | --- | --- |
| lock 上锁 | 低 | 无（行驶中亦可，幂等） | 否 |
| flash-lights 闪灯 / honk 鸣笛 | 低 | 车辆非行驶 | 否 |
| climate-on/off、climate-set-temp | 低 | 无 | 否 |
| charge-port-open/close | 低 | 非行驶 | 否 |
| charging-start/stop、charging-set-limit | 中 | 已插枪（start/stop）；set-limit 0–100 校验 | 否（结果回读复核） |
| windows-vent/close | 中 | 非行驶 | 否 |
| sentry-mode on/off | 中 | 非行驶 | 关哨兵需确认 |
| navigation-request 发送目的地 | 中 | 参数为合法地址/坐标 | 否 |
| unlock 解锁 | **高（敏感）** | 非行驶 + App 会话新鲜度（近期生物识别/重新登录） | **是** |

**明确排除（不在二期）**：drive 远程启动、frunk/trunk 开启、valet/PIN-to-drive/家长控制、钥匙增删、软件更新安装触发。各自单独评审后再议。

### 6.5 Android 控制区

- 仪表盘下方新增控制区：低级别命令做快捷按钮行（锁、空调、充电口、闪灯）；中/高级别收进「全部控制」页按组呈现。
- 交互规范：点击 → （需确认的先弹确认层，写明将发生什么）→ 执行中态 → 结果态（成功显示回读后的真实状态，如「已上锁 · 刚刚回读确认」；失败显示原因与建议动作）。
- 命令历史入口挂在控制页右上角（读 §6.3 审计）。

## 7. 三期：遥测扩展与地理围栏（先验证，后全量）

### 7.1 候选遥测字段（以特斯拉官方 Fleet Telemetry 字段字典为准，实施前逐项核对名称与车型可用性）

- 安全：BlindSpotWarning（左右）、ForwardCollisionWarning、AutopilotState
- 驾驶：AcceleratorPedalPosition、BrakePedalState、SteeringAngle
- 电池：电芯最高/最低电压与温度（如 BrickVoltageMin/Max、CellTempMin/Max 类字段）、LifetimeEnergyUsed

流程：先在 owner 单车灰度订阅 → 统计一周字段可用率与空值率 → 可用率达标才进 desiredTelemetryConfiguration 全量（沿用现有 config_synced 校验）→ App 侧先落电池健康页（电芯温差/压差曲线）与安全事件时间线两个呈现。

### 7.2 地理围栏评估

TeslaMate 模型是「围栏（家/公司/常用充电站）+ 按地点电价 + 改围栏后历史行程/充电按距离重算归属」；MateLink 现行是分时电价（峰平谷）。建议：先做轻量版「常用地点命名」（只影响地址显示与统计分组，不动计价）；地点电价作为可选覆盖层后评。不建议整体移植 TeslaMate 的围栏重算逻辑——其复杂度主要服务于单机自托管场景，多人云端要另做租户隔离设计。

## 8. 并行小线：接入自动化打磨

目标：新用户只做两步（特斯拉页登录授权 → 特斯拉 App 点确认虚拟钥匙），其余全自动。

- OAuth 回调成功后，App 自动深链跳钥匙确认页（auth.teslalink.joviluma.com 域已就绪），并回跳 App 继续。
- 配对与配置状态由后端自动轮询检测（沿用 maybeAutoConfigureAfterAuthorization 与授权后重试），App 只呈现进度与最终态，失败给出分级原因（未配对 / 配置未同步 / 等待首事件）。
- 文案红线：登录页明示「密码只输入在特斯拉官方页面，MateLink 不经手、不保存」。

## 9. 里程碑与验收

| 阶段 | 交付 | 验收（owner 真机） |
| --- | --- | --- |
| M1 一期后端 | 字段解析+序列化+测试，灰度开关下对 owner 车辆生效 | 对照特斯拉官方 App 同一时刻状态逐项核对新字段 |
| M2 一期 Android | 仪表盘新组件 + 附近补能页 | 真机逐屏核对；断网/旧后端兼容各验一次 |
| M3 二期前置 | scope 变更 + 增量授权流 + 审计/限频 | owner 账号走通重新授权全流程 |
| M4 二期命令 | 命令层 + Android 控制区（§6.4 清单） | 每条命令真机执行并核对车辆实际状态；行驶中拦截、未配对拦截各验一次 |
| M5 小程序同步 | §4.4 + 控制区小程序版（命令集同 §6.4，确认交互适配小程序规范） | 体验版真机验收 |
| M6 三期 | 遥测灰度报告 → 全量决策；地点命名轻量版 | 灰度数据报告由 owner 审阅后决定 |

回滚：后端每期独立开关；App 新组件可随版本回退；命令层 kill switch 见 §6.3。

## 10. 风险与开放问题

- **R1 scope 变更的存量影响**：补 scope 后老令牌不自动升级，未重新授权的用户命令不可用（展示不受影响）；增量授权的提示时机要避免打扰（只在用户点控制功能时提示）。
- **R2 命令配额成本**：命令计入 Fleet 应用级配额（按车型/套餐计费档不同）；灰度期统计真实消耗后再定限频数值与是否需要付费档。
- **R3 车型/固件差异**：媒体、座椅加热等字段随车型与固件变化，必须按「有则显示」实现并在测试中覆盖无字段车型（沿用数据诚实原则）。
- **R4 中国区差异**：命令与字段以中国区 Fleet API 实际返回为准，M1/M4 验收以真车为准，不以海外文档下结论。
- **R5 个人归档链独立性**：本设计不触碰 TeslaMate 归档桥；但本地 TeslaMate 若升级到 4.x，其表结构变化可能影响桥的查询——单独立项核查（需 owner 提供本地 TeslaMate 版本）。

## 11. 待 owner 决策清单

- **D1**：开发者后台 MateLink 应用是否已含 vehicle_cmds / vehicle_charging_cmds？（决定 P1 是否需要先改应用配置）
- **D2**：unlock 是否进二期一期命令集？（本文按「进，但高敏感规格」设计；也可推迟到二期第二批）
- **D3**：灰度范围：仅 owner 本人账号，还是含少量种子用户？
- **D4**：地理围栏按 §7.2 轻量路线走，还是暂缓？
- **D5**：限频初值（普通 30/日、敏感 5/日）是否接受，或另定？

---

*本文档由 Muse 基于 2026-11-04 通读与实测撰写，供 owner 审阅；批准后按 §9 里程碑逐期开 PR 实施，每期范围以 PR 描述为准。*
