# MateLink 小程序逐页参数、数据来源与计算审核

日期：2026-10-30  
结论：**AUDIT COMPLETE / CHANGES_REQUIRED**

## 1. 审核范围与基线

- 工作区：`E:\project\tesla_master\app_mimo`
- 分支：`codex/miniprogram-e-migration-20261029`
- 提交：`0e4cd561dc7d79a980f08dff8ad056b19093481b`
- 小程序版本：`0.6.1`
- 本次只修改审核记录和本报告，未修改 Android、iOS 或小程序业务代码。
- 范围内包含全部 9 个注册路由，以及路由内的行程详情、驻车、曲线、充电详情、统计/里程/能耗/时间线/待机、电池健康/续航/费用/长途/当前充电、设置/分时电价/位置/历史位置/胎压/哨兵/软件更新/报告/导出/国家地区/车辆预览、登录授权、微信绑定、退出和注销。

## 2. 总体结论

当前参数链路总方向是正确的：响应单位有归一化，未知值通常不转成零，能量合同要求来源、时间窗和覆盖率，历史页有账号/车辆/会话作用域门，地图、TPMS、授权和缓存也有大量负例测试。

但当前不能判定为“每个参数都正确”。源码与合成反例确认了多项会改变历史归属、费用、驻车时间或车辆身份的高风险问题。自动测试全部通过不构成反证，因为其中至少一项测试主动固化了“当前车辆拥有免费超充即把历史 DC 账单改成 0”的错误规则。

本次确定：

- 未发现 P0 级源码问题。
- 发现 11 项 P1，集中在历史来源身份、驻车邻接、费用语义、地图坐标、功率符号和当前充电身份。
- 发现 10 项 P2，集中在当前充电状态、短充投影、分页边界、默认时间窗、AC/DC 分类和真实零值展示。
- 真实 Tesla 账号、微信开发者工具同账号数据、真机地图、微信文件分享/订阅消息、生产后端与 Tesla OAuth 均未在本次审核中验收。

## 3. P1 问题

### P1-01 历史来源身份被丢弃，缓存会合并不同采集器/来源实例

证据：

- 后端历史响应实际输出 `source_instance_id`、`source_vehicle_id`、`source_record_id`，见 `deploy/jourvolt-dev-mock/history_bounded_read.go:140`、`deploy/jourvolt-dev-mock/history_bounded_read.go:285`。
- Android 合同保留 `source_instance_id` 与 `source_record_id`，见 `android/app/src/main/java/com/matelink/data/api/models/DriveModels.kt:45-51`、`android/app/src/main/java/com/matelink/data/api/models/ChargeModels.kt:39-44`。
- 小程序 `RawDrive`、`RawCharge` 没有这些字段，见 `miniprogram/src/services/types.ts:285`、`miniprogram/src/services/types.ts:348`；`normalizeDrive`/`normalizeCharge` 又从白名单重建对象，见 `miniprogram/src/services/api.ts:573-653`。
- 缓存合并身份只有 `sessionId` 或公开 ID，见 `miniprogram/src/domain/history.ts:24-27`。
- 手动费用身份只有公开 ID/session、来源，见 `miniprogram/src/services/charge_cost.ts:21-24`。
- 长途段身份同样没有来源实例，见 `miniprogram/src/domain/insights.ts:108`。

合成反例：

```text
来源 A：session=same-session，地址=Collector A
来源 B：session=same-session，地址=Collector B
historyRowKey(A) == historyRowKey(B) == session:same-session
mergeHistoryByKey([A], [B]) => 1 条，地址被 B 覆盖
```

影响：

- TeslaMate 数据源重建、切换采集实例或同一辆车重新导入后，公开 ID 相同的历史可能被覆盖或合并。
- 手动费用可能套到另一来源实例的同名会话。
- 长途组合可能引用并保存错来源的行程/充电。
- 详情、曲线、驻车和统计可能混用同一公开 ID 下的不同来源证据。

建议：在 DTO、归一化、缓存、列表合并、详情回填、费用覆盖、长途段、曲线和清单导出中统一使用 `source + source_instance_id + source_record_id + 时间窗` 的 canonical 身份；缺失或不一致时 fail closed。

### P1-02 行程页先筛选再推导驻车，会跨过被隐藏的短途

证据：

- 页面先过滤可见行程，再调用 `buildDriveHistoryItems(visibleItems)`，见 `miniprogram/src/pages/drives/index.tsx:496-500`。
- 函数只保留 `distance >= 0.5 km` 的边界，然后让这些非相邻记录配对，见 `miniprogram/src/domain/drive_history.ts:27-37`。
- `isAppRouteDrive` 仍允许 `0.2–0.5 km` 的合格行程或把缺距离/质量记录保留为可见行，见 `miniprogram/src/pages/drives/drive_parity.ts:8-11`。
- Android 已先从完整序列建立 `verifiedParkedPairs`，再投影筛选结果，见 `android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt:429-470`。

合成反例：

```text
A 结束 19:00
B（0.3 km）22:10–22:15
C 开始 23:20
当前小程序输出：C、A→C 驻车 260 分钟、B、A
```

影响：驻车起止时间、地址、SOC 变化和详情入口错误；若用户用日期/距离筛选，窗口还会随筛选集合漂移。

### P1-03 驻车邻接会跨过隔离行程，且列表驻车未检查来源一致

证据：

- `buildParkedWindows` 在排序前删除 quarantine，再让剩余记录相邻，见 `miniprogram/src/domain/analysis.ts:35-45`。
- 列表侧的 `createParkedSegment` 没有来源或来源实例检查，见 `miniprogram/src/domain/drive_history.ts:47-60`。

合成反例：

```text
A（observed）→ B（quarantined）→ C（observed）
当前 buildParkedWindows 输出：A→C 驻车 260 分钟
```

影响：时间轴、待机页、驻车详情和“当时在哪里”可能越过被隔离的中间行程，生成不存在的驻车区间。

建议：在完整同源、同车辆、同采集实例的连续序列中先验证相邻对；任何短途、未知、quarantine、来源或实例断层都成为硬边界，之后才应用列表/日期/距离投影。

### P1-04 当前车辆“免费超充”标志会覆盖历史正数账单

证据：

- `normalizeCar` 从当前 `car_settings.free_supercharging` 读取车辆级布尔值，见 `miniprogram/src/services/api.ts:465`。
- 费用覆盖顺序为手动 → `freeSupercharging && DC` → 上游账单 → 估算，见 `miniprogram/src/services/charge_cost.ts:62-77`。
- 现有测试接受 DC 正账单被免费标志覆盖，见 `miniprogram/src/services/charge_cost.test.ts:115-126`。
- 项目规则明确“只有显式用户操作可以把充电标记为免费”，见 `tasks/lessons.md:74-77`。

反例：车辆目前设置 `free_supercharging=true`，历史 DC 会话来自付费第三方并返回 `10 EUR` 正账单；当前小程序显示“免费”、金额 `0`。

影响：费用列表、摘要、详情、报告和导出金额错误。车辆当前权益不能证明某次历史会话当时免费，更不能覆盖已存在的正数账单。

### P1-05 合法但未列出的币种或币种冲突会被转成 CNY 估算

证据：

- `chargeCurrency` 只接受固定币种白名单；其他合法 ISO 币种或字段冲突都返回 `null`，见 `miniprogram/src/services/api.ts:182-190`。
- 默认估价器把 `null` 当作“未提供/未冲突币种”，可改用 `1.14 CNY/kWh`，见 `miniprogram/src/services/charge_cost.ts:52-59`、`miniprogram/src/services/charge_cost.ts:63-77`。

合成反例：

```text
TeslaMate 原始币种 TRY、cost=null、合格电池充入 10 kWh
当前输出：≈ CNY 11.40，来源 estimate
正确结果：币种无法确认，不应生成 CNY 金额
```

同一问题也会发生在 `currency_code=CNY`、`cost_currency=USD` 等冲突响应。

### P1-06 TeslaMate 的 `cost=0` 在充电页与分析页含义相反

证据：

- 充电页请求估算契约时，把 TeslaMate 原始 0 视为“未填写”，见 `miniprogram/src/services/charge_cost.ts:69-77`。
- 分析页调用费用覆盖时不传估算契约，因此同一记录被视为真实 `actual 0`，见 `miniprogram/src/pages/insights/index.tsx:126-134`。
- 现有测试同时要求充电页估算和分析页真实 0，说明冲突已经进入测试预期，见 `miniprogram/src/pages/charges/charge_detail.test.tsx:274`、`miniprogram/src/pages/insights/insights_page.test.tsx:74`。

影响：同一条充电记录可能在列表显示估算，在费用分析、年度报告、时间线和导出中显示 0。不同页面无法用于同一份财务结论。

建议：建立唯一费用投影结果，并在所有消费者间复用；明确区分 `actual zero`、`TeslaMate unspecified zero`、`manual zero`、`session-verified free`、`estimate` 和 `unknown`。

### P1-07 当前充电没有验证嵌套 `data.car.car_id`

证据：

- 后端当前充电响应把车辆身份放在 `data.car`，见 `deploy/jourvolt-dev-mock/telemetry_http.go:173-192`。
- 小程序只校验 unwrap 后的包装对象和 `charge`，没有校验 `data.car`，见 `miniprogram/src/services/api.ts:1276-1280`。

影响：若响应错误、缓存映射或上游路由返回另一辆车的 `charge`，当前充电页仍可能接受并展示。该风险与普通历史响应已执行的 `assertVehicle(unwrap(response))` 不同。

建议：同时验证 `data.car.car_id`、charge 内身份、当前请求车辆和 stable UID；缺少嵌套车辆身份时 fail closed。

### P1-08 行程地图直接使用原始坐标并跨缺测点连线

证据：

- 行程详情把 evidence 点直接送入 `Map`，没有调用 `mapCoordinate`，见 `miniprogram/src/pages/drives/index.tsx:609-619`。
- 先把无坐标点过滤掉，再把剩余点作为一条 `polyline`，缺测两侧会被直线连接；`(0,0)` 也没有被排除。
- 其他地图消费者使用一次性 WGS84→GCJ-02 转换，见 `miniprogram/src/domain/map_coordinate.ts:1-18`。

影响：国内路线可能整体偏移；隧道、失联或坐标缺失区段会画出不存在的直线；无效零坐标可能通过范围检查进入地图。

建议：逐点验证非零坐标，执行一次地图坐标转换，并按连续有效区段拆成多条 polyline。

### P1-09 “峰值输出/峰值回收”没有符号资格

证据：

- 当前将所有有限功率样本的 max 显示为“峰值输出”，min 显示为“峰值回收”，见 `miniprogram/src/pages/drives/index.tsx:683-696`。

反例：

- 样本均为 `[5, 20]` 时，“峰值回收”显示 `5 kW`，但该段没有回收。
- 样本均为 `[-20, -5]` 时，“峰值输出”显示 `-5 kW`，但该段没有输出。

建议：输出仅取 `power > 0` 的最大值并显示正值；回收仅取 `power < 0` 的最小值并按产品合同显示负值或绝对值；没有对应符号样本时显示未知。平均功率应标明“样本均值”，因为 evidence 响应不保证完整时间覆盖。

### P1-10 费用单位电量分母与 App/既定合同不一致

证据：

- 小程序使用合格 `inputEnergyKwh`，见 `miniprogram/src/domain/insights.ts:31-49`。
- Android `ChargeCostTotals.kt` 使用同笔电池充入量，见 `android/app/src/main/java/com/matelink/domain/analytics/ChargeCostTotals.kt:11`。

示例：费用 `12`、AC 输入 `12 kWh`、电池充入 `10 kWh` 时，小程序显示 `1/kWh`，App 合同为 `1.2/kWh`；DC 没有合格 AC 输入时，小程序直接把单位费用显示为未知。

建议：先由产品明确“账单对应的计费能量”字段，再让 App、小程序、摘要、月度、年度和导出统一使用同一字段；当前至少应作为合同分歧阻断，不能宣称跨端一致。

### P1-11 分析页旧长途段可能覆盖较新的历史费用视图

证据：

- `allCostCharges` 先加入当前筛选历史，再加入已保存长途段，最后加入 current，见 `miniprogram/src/pages/insights/index.tsx:124-130`。
- 这些记录按身份写入 Map，后加入的旧长途段会覆盖同键的当前历史对象。

影响：旧长途组合中保存的过期费用、币种或能量字段可能让费用分析回退到旧值。若 current 也同键，current 最终会胜出，但不能修复普通历史已被旧长途覆盖的区间。

## 4. P2 问题

### P2-01 当前充电把实时快照当完成历史解析

- `current_battery_level` 没有进入 `RawCharge`/`Charge`。
- `is_charging` 没有进入 `Charge`，页面只凭对象非空显示“正在充电”。
- 开放会话或 Fleet live 快照的 `charge_energy_added` 可被能量合同/legacy source 规则拒绝，导致已充入显示未知。
- `duration_min` 通常不存在，Insights 的“已持续”直接读取该字段。
- Fleet-only 响应把快照观测时间写入 `start_date`，小程序标为“开始时间”。

相关位置：`miniprogram/src/services/types.ts:348-375`、`miniprogram/src/domain/drive_energy.ts:323-338`、`miniprogram/src/pages/charges/index.tsx:656-664`、`miniprogram/src/pages/insights/index.tsx:258-262`。

### P2-02 当前充电状态机混淆返回 null、请求失败、starting 和停止

- current 请求失败时，`Promise.allSettled` 把它折成 `null`，可能进入“充电会话尚未生成”。
- 只识别精确 `charging`，后端已识别的 `starting`/`charging_started` 不会触发 4 秒启动轮询，见 `miniprogram/src/pages/charges/index.tsx:331-351`。
- current 返回 `is_charging=false` 时，只要对象非空仍可能显示“正在充电”。

### P2-03 短充开关没有同步作用于摘要和图表

- 列表使用 `appChargeListRows(..., showShortCharges)`，见 `miniprogram/src/pages/charges/index.tsx:571-572`。
- 摘要和图表固定使用 `appChargeStatisticsRows`，不读取该开关，见 `miniprogram/src/pages/charges/index.tsx:568-573`。

结果：同一页面的列表、摘要、月度电量/费用/次数使用不同集合。

### P2-04 默认时间窗不是项目要求的 7/30 天

- 行程和充电页初始都调用 `emptyHistoryQuery()`，即“全部时间”，见 `miniprogram/src/pages/drives/index.tsx:106`、`miniprogram/src/pages/charges/index.tsx:134`。
- 项目明确要求行程默认近 7 天、充电默认近 30 天，见 `tasks/lessons.md:6`。

### P2-05 自定义日期入口与反向区间不一致

- 公共日期 chip 只有一个日期选择器，选择开始日后把结束日强制设为同一天，见 `miniprogram/src/components/HistoryDateChips.tsx:33`。
- 原有独立起止日期入口被 CSS 隐藏，见 `miniprogram/src/pages/drives/index.scss:596-598`、`miniprogram/src/pages/charges/index.scss:407-409`。
- Insights 独立日期选择器直接 `setQuery`，没有经过 `normalizeHistoryQuery`，开始日晚于结束日时显示空结果，见 `miniprogram/src/pages/insights/index.tsx:195-198`。

### P2-06 AC/DC 分类在不同消费者间不一致

- `normalizeCharge` 会从 `charge_type`/`fast_charger_present` 生成顶层 fallback mode，见 `miniprogram/src/services/api.ts:191-195`。
- 列表和详情只读取能量合同的 verified mode，见 `miniprogram/src/pages/charges/index.tsx:55-58`。
- 分析页和月统计会读取顶层 fallback mode。
- `charger_phases` 存在但没有参与历史分类。

同一记录可能出现列表“模式未确认”，而 AC/DC 统计把它计入 DC。

### P2-07 部分真实零值被显示为未知

- 行程详情把 `0` 分钟按 falsy 处理，见 `miniprogram/src/pages/drives/index.tsx:649`。
- 距离均速用 `selected.distanceKm &&`，0 距离显示未知，见 `miniprogram/src/pages/drives/index.tsx:668-677`。
- 充电 HUD 仅在电压存在时同时显示电压/电流；只有电流或真实 `0 A` 时被隐藏，见 `miniprogram/src/components/dashboard/DashboardParts.tsx:42-44`。

### P2-08 无分页元数据时，一个畸形行会提前停止分页

- `historyResult` 先过滤无效行，再用过滤后的长度推断 `hasMore`，见 `miniprogram/src/services/api.ts:543-556`、`miniprogram/src/services/api.ts:663-672`。
- 原始满 20 行中若有 1 行无合法 ID，无 meta 响应会被当作 19 行最后一页，后续历史不可达。

### P2-09 “同意条款/隐私指引”状态由会话存在与否推断

- `termsAccepted`、`privacyAccepted` 初始化为 `Boolean(initialSession)`，见 `miniprogram/src/pages/me/index.tsx:32-35`。
- 重新显示页面后，只要存在 session 就再次置为 true，见 `miniprogram/src/pages/me/index.tsx:117-125`。
- session 没有保存已接受的条款/隐私版本。

当前版本未变更时通常不会暴露问题；版本更新或恢复旧会话时，勾选状态没有证明当前版本已被本人接受。

### P2-10 部分“历史”入口实际只有当前状态或未实现的地理推断

- 温度趋势页只显示当前车内/外温度。
- 哨兵历史页只显示当前哨兵状态，没有事件历史。
- 到访国家/地区页只列已有地址，明确不从文字推断国家或行政区。
- 软件更新下一页仅在当前数组恰好 20 条时出现，没有真正消费分页元数据。

这些页面大多用文案诚实说明能力不足，因此不构成伪造数据，但不能算完整功能验收。

## 5. 逐页参数与来源矩阵

| 页面/功能 | 主要参数 | 数据来源/计算 | 结论 |
|---|---|---|---|
| 仪表盘车辆资料 | 名称、车型、版本、颜色、轮毂、外观图 | `/api/v1/cars` 的 `display_name/name/model/car_details/car_exterior`；本地人工外观覆盖单独标注 | PASS（源码/组件）；真实外观素材未验 |
| 仪表盘实时状态 | 状态、SOC、续航、里程、车内/外温度、位置、锁/窗/门/箱、空调、哨兵、插枪、速度、挡位、功率 | snapshot 优先，404/501 才回退 status；长度/温度按同响应单位换算；功率保留正负 | PASS_PATH；有 P2-07、地图真实运行未验 |
| 充电 HUD | 已充入、充电功率、电压、电流、剩余时间、目标 SOC | `charge_energy_added/charger_power/charger_voltage/charger_actual_current/time_to_full_charge/charge_limit_soc`；时间单位为小时 | CHANGES_REQUIRED（电流门控） |
| 胎压 | 四轮胎压、逐轮时间、旧观测、本机提醒 | 只接受显式 `bar`、`telemetry_mqtt`、`collector_received_at` 和逐轮时间；报告值只展示、不告警/不写历史 | PASS（源码/组件）；真实硬件未验 |
| 就绪/配对 | telemetry readiness、pairing、配置同步、错误类别 | `/data-readiness`、`/telemetry/pairing`、`POST /telemetry/configure` | PASS（源码/组件）；真实 Fleet 未验 |
| 行程列表摘要 | 次数、距离、时长、最高速度、覆盖数 | 已加载 interval 再按同页筛选；最高速度仅接收单位有效的 server peak | PASS_PATH；默认窗口和来源身份见 P1/P2 |
| 行程列表卡片 | 时间、起终点、SOC、能耗、距离、最高速度、时长 | 历史 API 规范化；能耗 `kWh/100km` 由合格净能量/正里程计算 | CHANGES_REQUIRED（P1-01/P1-02、零值） |
| 行程驻车卡片 | 地址、时长、SOC 变化、详情 | 相邻行程推导；详情端点补温度/能量/充电重叠 | CHANGES_REQUIRED（P1-02/P1-03） |
| 行程详情 | 地图路线、起止时间、距离、时长、能耗、峰值/均速、功率、SOC、净能量、温度 | 详情 summary + `drive_details` points；功率积分有完整/部分覆盖规则 | CHANGES_REQUIRED（地图、功率符号、平均值标注） |
| 行程曲线 | 速度、累计距离估算、功率、SOC、海拔 | 原始时间点；缺测断线；距离用速度对时间积分并归一到合格总距离，明示估算 | PASS（源码/组件）；真实采样未验 |
| 充电列表摘要 | 次数、电池充入、费用、单次均价、覆盖 | 列表/统计投影、同币种合计、未知不按 0 | CHANGES_REQUIRED（短充集合、费用身份/币种/0 语义） |
| 充电列表卡片 | 地点、时间、模式、时长、电池充入、费用、SOC、输入电量 | 历史 API；费用手动/实际/估算/未知投影 | CHANGES_REQUIRED（P1-04至P1-06、P2-06） |
| 当前充电 | 开始、地点、已充入、功率 | `/charges/current`，30 秒轮询，启动态 4 秒轮询 | CHANGES_REQUIRED（P1-07、P2-01/P2-02） |
| 充电详情 | 付款来源、坐标、电池充入、输入电量、AC 效率、SOC、时长、报告功率、样本最高/最低/平均功率、AC 电压/电流、温度 | 详情 + evidence；AC 效率还要求两端计数器、来源、时间窗和模式证据 | PASS_PATH；费用和 AC/DC 问题见 P1/P2 |
| 充电曲线 | SOC、功率、电压、电流、温度 | 原始 evidence；缺测断线、最多 600 个渲染点，游标仍读原样本 | PASS（源码/组件）；真实采样未验 |
| 统计概览 | 总距离、配对平均能耗、净能量、电池充入、记录、总时间、平均行程距离、费用/币种、AC/DC、温度分组、月度里程 | 历史分页 + 合格能量/正里程配对；各覆盖分母独立 | CHANGES_REQUIRED（来源身份、模式、费用合同） |
| 里程统计 | 年度/月度/日里程、次数、净能量、年历 | 本机日期分组；不冒充车辆终身里程 | PASS_PATH；默认窗口/来源身份 |
| 能耗分析 | 平均能耗、覆盖、距离、趋势、近90天/季节、最低/中位/最高、速度分组、百分位 | 仅 observed/derived（含 remote-quality-unavailable 例外）；净能量与正里程按同条配对 | PASS_PATH；来源身份未覆盖 |
| 时间轴 | 行程、充电、驻车事件 | 当前已加载历史按本机日期排序，费用沿用统一覆盖 | CHANGES_REQUIRED（驻车、费用） |
| 驻车损耗 | 服务端 standby 窗口与本机相邻行程窗口、SOC、能量、平均功率 | 只有服务端完整 `EnergyRemaining` 端点合同进入合格能量；SOC 不猜容量 | CHANGES_REQUIRED（本机窗口邻接） |
| 驻车详情 | 起止、地址、SOC、储能、平均功率、环境温度、采样/覆盖、关联充电 | 服务端 parked 端点 + 本机事实 | CHANGES_REQUIRED（上游窗口来源身份） |
| 电池健康 | 当前/原始容量、健康百分比、衰减、额定效率、最大/当前报告续航 | `/battery-health` actual 字段；不支持时不从 SOC 猜容量 | PASS_PATH；真实字段未验 |
| 续航分析 | 同温/速度分组的个性化续航、额定下降偏差、季节和速度影响 | 近90天；分组至少5段/100km，否则全部条件至少10段/300km；需要来源和时间合格的容量 | PASS（计算）；真实响应未验 |
| 费用分析 | 合计、单次均价、输入电量、单位费用、月度 AC/DC、地点、自定义费率估算 | 手动覆盖 + 合格输入能量；币种分组 | CHANGES_REQUIRED（P1-06/P1-10/P1-11） |
| 长途行程 | 本机命名组合、距离/时长/能量/费用、编辑删除 | 只保存选中段的脱敏投影；最多20条、50段、64KiB | PASS_PATH；身份键见 P1-01 |
| 更多/账号 | 会话、微信绑定、授权、退出、注销 | 本地 session + server binding/auth/account endpoints | PASS（组件/负例）；P2-09；真实微信平台未验 |
| 设置 | 账号状态、单位、提醒能力、隐私入口 | 会话/静态配置；单位显示为归一后的 km/km·h/°C/bar | PASS（源码）；微信权限与订阅消息未验 |
| 分时电价 | 币种、峰平谷价格、时段、默认价、时区 | 本机按账户/origin 保存；24小时覆盖校验；按保存时 IANA 时区估算 | PASS（单测） |
| 车辆位置 | 当前坐标、报告时间、地图 | snapshot 坐标经坐标转换；位置不写缓存 | PASS（源码）；真实地图未验 |
| 当时在哪里 | 指定本机日期时间、历史地点 | 只取覆盖该时刻的行程采样、充电地点或相邻行程窗口，不插值 | CHANGES_REQUIRED（驻车邻接依赖） |
| 胎压历史/设置 | 逐轮历史、提醒阈值、变化阈值 | 30天/最多500点/每轮缓存；只比较同轮有效遥测 | PASS（组件/单测）；真实遥测未验 |
| 温度趋势 | 当前车内/外温度 | 当前 status | NOT_VERIFIED：无趋势历史 |
| 哨兵历史 | 当前哨兵状态 | 当前 status | NOT_VERIFIED：无事件历史 |
| 软件更新 | 版本、开始时间、分页 | `/updates?page&show=20` | PASS_PATH；下一页启发式见 P2-10 |
| 年度报告 | 年份、次数、已知距离、净能量、配对能耗、费用、手填数 | 本机年份 + 已有历史；完整/部分覆盖明确 | CHANGES_REQUIRED（费用合同、来源身份） |
| 数据导出 | CSV/JSON、日期、类型、覆盖 | 固定脱敏字段；公式注入防护；不含精确位置/轨迹/身份/令牌 | PASS（单测）；文件分享未在微信实测 |
| 国家/地区 | 已有历史地点 | 只列地址，不从文本推断国家/行政区 | NOT_VERIFIED：完整地理能力未实现 |
| 车辆预览 | 线框旋转、缩放、开门/前备箱/充电口示意 | 纯本地示意，不调用车辆命令 | PASS（源码）；非实车3D |
| 授权页 | Tesla URL、状态、claim、错误 | URL 白名单；WebView 消息只作唤醒提示，最终以服务端 owned transaction 状态为准 | PASS（组件/安全负例）；真实 Tesla OAuth 未验 |
| 法务页 | 条款/隐私版本和正文 | 本地静态文档，版本与 API consent 常量一致 | PASS（单测） |

## 6. 已验证正确的关键计算

- 距离：响应长度单位 `km/mi` 只在已声明单位下换算；未知单位不生成距离。
- 速度：峰值速度必须有已知速度单位；英里速度转 km/h。
- 温度：车内、车外和充电采样温度支持同响应摄氏度/华氏度换算。
- SOC：只接受 `0–100`；真实 `0%` 可展示。
- 能耗：有合同证据时按同窗口和来源 `Wh/km → kWh/100km`；无证据不猜容量。
- 行程能耗平均：只把同一条行程的合格净能量与正距离配对；总距离、总能量和平均能耗分母独立。
- 曲线：最多绘制 600 个真实点，保留首尾、极值、零值和缺测断点；游标读取完整原始样本。
- 充电能量：电池充入与输入电量分开；AC 充电效率只在两端计数器、来源、时间、覆盖率和 AC 模式全部合格时计算。
- 待机合格能量：仅采用来源匹配的 `EnergyRemaining` 端点估算，要求无驾驶/充电、完整覆盖和同窗口；SOC 不转化为容量。
- 费用未知：负数和非有限值被拒绝；真实 0 不自动变成未知；多币种不直接相加。
- 历史质量：quarantined 不进入列表统计；未知来源不能借 observed/derived 标记洗成可信。
- 账号/车辆作用域：API origin、account、generation、stable UID 和多层请求门能阻止常见的迟到响应串账号。
- 缓存隐私：历史缓存不保存原始位置、轨迹或充电采样；账号删除只清理已确认 schema。

## 7. 测试与构建证据

### 完整回归（本审核链）

- `npm test`：60 个 Vitest 文件、805 项通过。
- 车辆资源完整性：6/6 通过。
- `npm run typecheck`：通过。

### 页面聚焦回归

```text
14 test files passed
262 tests passed
0 failed
```

覆盖：

- 车辆页/仪表盘/TPMS/授权与缓存负例。
- 行程和充电历史页、详情、分页、迟到响应、缓存重开。
- 统计、里程、能耗、时间轴、曲线、驻车、待机。
- 电池、续航、费用、长途、当前充电。
- 报告、导出、分时电价、历史位置。
- 更多/微信绑定/退出/注销。
- Tesla 授权 WebView 和 claim。

### 费用专项回归

```text
8 test files passed
213 tests passed
0 failed
```

这组测试通过的同时，仍存在 P1-04、P1-05、P1-06 等未覆盖或冲突预期。

### 微信构建

```text
Taro/Webpack compiled successfully
vehicle PNG originals: 97
compiled files: 197
content SHA256: c95e11bde3b5862b006a1e7723234d8ae4a589596dd12846a33b7139ccd8b404
```

构建未部署、未上传体验版。

### 合成反例执行

- 行程 A→短途 B→C：错误生成 A→C 驻车 260 分钟。
- 行程 A→隔离 B→C：错误生成 A→C 驻车 260 分钟。
- 两来源同 session：合并后仅 1 条，后来源覆盖前来源地址。
- TeslaMate TRY、缺账单、10 kWh：错误生成 CNY 11.40 估算。

这些是当前源码的确定性执行结果，不是推测。

## 8. 未验证边界

以下均不能由上述测试替代：

- Jovi 本人的真实 Tesla 账号与小程序的同账号逐页数据对照。
- 微信开发者工具在完整登录态下逐页运行和截图。
- iPhone/Android 微信真机地图、分享、文件打开、网络切换、系统明暗。
- 真实 Tesla OAuth、微信绑定、快捷进入、解绑、撤销授权。
- 生产后端的分页、实时充电、TeslaMate 多实例、费用币种和来源身份响应。
- 真实 20,000 点曲线、真实 TPMS/Fleet Telemetry、订阅消息送达。
- 生产部署和体验版上传；本次明确没有执行。

## 9. 建议修复顺序

1. 先统一 canonical source record identity，清除跨来源缓存、费用、长途和详情串用风险。
2. 从完整历史序列建立驻车邻接，在任何筛选/quarantine 之前冻结断点。
3. 重构唯一费用投影：账单优先、免费只接受会话级明确证据、币种保持三态、TeslaMate zero 统一解释。
4. 补齐 current charge 的嵌套车辆校验、live 字段、`is_charging` 和 starting/stopped 状态机。
5. 修复行程地图坐标、断线与符号特定功率。
6. 让短充、日期、距离筛选同时驱动列表、摘要、图表、分析和导出。
7. 补真实账号/微信开发者工具/真机验收后再进入发布判断。

## 10. 最终判定

小程序的核心单位换算、时间窗、证据质量、缓存作用域和大量缺失值处理已有较好基础；但历史来源身份、驻车推导和费用语义目前会实际改变记录归属与金额。

因此本轮结论是：**数据和计算闭环仍需修改，不能以“805 项测试通过”或“构建成功”代替参数正确性验收。**
