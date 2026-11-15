# 02 充电：全字段数据链审计

审计日期：2026-10-26。工作区：`E:\project\tesla_master\app_mimo`，HEAD `cdfe0b26`，Owner 工作区已有 dirty；本审计只新增本文，不修改页面、业务、测试、配置、设备或数据库。范围为 Android `ChargesScreen`、`ChargeDetailScreen`、`CurrentChargeScreen`，包含全部可见分支、筛选、汇总、曲线与费用编辑。

证据级别：以下为**当前工作区源码静态核查**，未调用真实 API、未读取用户充电记录、未运行新测试、未宣称设备或生产通过。父审计提供的既有 1356/variant 测试通过属于继承证据，不能证明本文新发现通过。图 MCP 已由父审计尝试并被批准门阻止，按指令使用 scoped `rg`/范围读取，不重复失败调用。

## 结论与根因归并

三个页面均有可追溯字段；历史详情的能量资格、点数据落盘与单点显示已有较完整实现。发现四类 P1 根因，足以阻断“所有充电数据已打通”的发布声明。另有三类 P2 口径/空态风险。未发现本范围 P0。

### CHG-P1-01：旧聚合把未知充电模式写成 AC，列表/筛选/统计与详情不一致

- 触发：无 `energy_contract` 的历史充电，有功率点而没有 `fast_charger_present`/`charger_phases`；例如当前云端 `teslamate_archive` 桥接链的通常输出。
- 源码证据：`data/sync/DetailAggregateMapper.kt:56-79` 的 `any { fast==true || power!=null && phases==0 }` 无命中即生成 `isFastCharger=false`。`ChargesViewModel.kt:984-988` 随后将全部已处理且非 DC 的 ID 当 AC。`AggregateDao.kt:389-430` 的索引只按 car/charge/quality 取聚合，未提供“模式未知”状态。相反，`ChargeStatsCalculator.kt:137-171` 无显式证据返回 UNKNOWN。
- 完整上游证明：`deploy/teslamate-home-docker/archive-bridge/model.go:62-69` 的 `ChargePoint` 没有 phases/fast；`db.go:150-190` 解码不保留这两字段；`deploy/jourvolt-dev-mock/telemetry_service.go:1377-1379` 历史 `charger_details` 仅输出 power。因此不能把“处理过详情”作为 AC 证据。
- 影响：同一 DC 或未知会话，详情可为未知，列表为 AC；AC/DC 筛选、图表分段、免费资格随之改变。现有 `ChargeModeListContractTest` 验证消费者中 processedIds 的既定行为，没有覆盖生产者把未知写 false 的路径。
- 最小非布局建议：让聚合/索引保留 UNKNOWN 或仅给有正向 AC 证据的 ID 授予 AC；重算旧聚合时保留旧 raw receipt，不靠功率阈值猜模式。测试真实 `toAggregate -> DAO index -> historyChargeType`，包含只有 power 的 DC/未知输入。

### CHG-P1-02：免费资格和费用投影缺少会话级适用证明，且离线重开会改口径

- 确定触发 A：`freeSupercharging=true`、`isDcCharge=true`，会话实际有正数 bill（例如第三方 DC 或不享受权益的付费 DC）。`ChargeCostProjection.kt:97-110` 在检查正数 provider 账单前直接返回 FREE 0，仅检查 DC，未核验 Supercharger/权益适用会话。输入 `(free=true, dc=true, cost=100, matching currency)` 的代码路径确定得到 0。
- 确定触发 B：同一被在线判免费会话，冷启动离线。列表默认 `freeSupercharging=false`（`ChargesViewModel.kt:148`），只有成功 `getCar` 才设 true（339-353）；详情 offline 分支 `loadCost(..., false, ...)`（`ChargeDetailViewModel.kt:399-402`）。有合格能量时可能变为默认电价估算，无能量时变未知。用户没有改费用，会话金额却改变。
- 相关安全性已有：手动金额优先；币种、窗口、来源、实例、record ID 受绑定，provider 0 不自动当免费；这不修复“全 DC 都免费”的资格问题。手动明确 0 可以作为已知 0。
- 最小非布局建议：免费是同会话的明确来源/用户证明，不能由车级权益直接推广到任意 DC；正数已确认 bill 不应被未经会话核验的车级权益覆盖。将同作用域免费资格/适用说明持久化并在列表、详情、离线重开使用同一投影。增加付费第三方 DC、源 bill 与权益冲突、冷启动离线一致性消费者测试。

### CHG-P1-03：历史 load 在持久化保护前预合并，跨窗口借用同 ID 的旧字段

- 触发：同 car、chargeId、source、sourceInstanceId、sourceRecordId（旧接口两者都缺失也算相同），远端纠正为新 start/end，但 cost/SOC/energy/address 某些字段为 null；本地旧窗口有这些字段。
- 证据：`UnifiedHistoryRepository.kt:291-292` 先 `mergeChargesRaw(remote, local)`，`391-400` 以 ID 或窗口定位；`ChargeData.mergeWith`（460-489）检查 source/instance 后直接 `copy(cost = cost ?: old.cost, batteryDetails = ... , chargeEnergyAdded = ... , energyContract = ...)`，**没有 same-window 检查**。`314-316` 再把这个已借值的 raw 转成 incoming row。后面的 `mergeStoredCharge:763-775` 虽识别 distinctSession，却只能保留这个已混合的 incoming，无法恢复真正 remote null。
- 具体例：旧窗口 A 的 cost=100、SOC=20→80；同 ID 新窗口 B 的 cost/SOC 缺失。`mergeChargesRaw` 先输出窗口 B+cost100+旧SOC，再落盘。费用投影可把旧正数金额当 B 的源账单；旧合同通常因窗口不匹配被拒，故能量合同的窗口校验不能保护 cost/SOC/address；无合同 legacy scalar 同样可能借旧能量。
- 最小非布局建议：像 drive load 的 actual-candidate 路径（同文件 297-308）一样保留真实 remote 候选，跨物理窗口禁止字段级 fallback，再交由同作用域 DAO merge/归档处理。测试真实 `UnifiedHistoryRepository.load -> Room reopen -> list/detail/cost`，而非只测 `mergeStoredCharge` 的未混合输入。

### CHG-P1-04：当前充电存在原始数据旁路、会话被快照覆盖和身份生命周期缺口

这些表现归属同一条 current-charge 读取/投影链，不重复算多项 P1：

1. **当前能量绕过合同**：`CurrentChargeScreen.kt:699-702` 直接显示 `detail.chargeEnergyAdded`；`CurrentChargeViewModel.kt:174-180` 没有 `withQualifiedEnergy`。明确 unknown 合同、未验证 Fleet scalar 或 source-record 冲突，只要 raw 数字非负仍可显示；历史列表/详情相同记录会拒绝它（`ChargeModels.kt:127-143`）。进行中会话不能硬用要求 end 的完成态合同，应提供明确 live counter/window 投影。
2. **当前图表不按信号时间配对**：VM 176-178 无条件 reverse，假定 API newest-first；当前云端 `historySessionMap` 遍历会话存储顺序，未承诺 newest-first。Screen 303-304、343-344 分别 `mapNotNull`，却用全部原点的共同 index 取时间（305-310）；中间缺值/乱序/重复冲突/越窗点导致曲线或选中时间错配。使用 `ChargePoint.chargerPower/Voltage/Current` 的 Int getter（`ChargeModels.kt:155-160`）还提前取整，0.4 kW 变 0；历史详情使用 Double 值。SOC 曲线/起止/目标也未在当前消费者验证 0..100。
3. **后端覆盖已知历史**：`telemetry_http.go:175-180` 先生成 open session，随后 `mergeCurrentChargeStatus:232-234` 把整个 battery_details 替换成只含 current SOC；`263-274` 将完整 charge_details 替成一个当前点/空数组。即使 session 含起始 SOC 和多点，正常 status 成功也会丢失，当前页显示起点未知、无曲线。这是代码链丢字段，不是 provider 未采集。Fleet-only fallback 的 start 在 214-226 被设为 status.observedAt（缺时间甚至 time.Now），UI `ElapsedTimeCounter:812-832` 当真正充电起点，每次刷新后 elapsed 接近零；它只是快照时刻。
4. **身份及失败状态缺口**：VM `loadCurrentCharge:85-87` 切 car 仅取消循环，不清旧 `chargeDetail`；`fetchData:107` 在无 try/catch 下调用可能抛 `HistoryIdentityUnavailableException` 的 `requireLocalHistoryCarId`（`VehicleContextRepository.kt:128-134,236-237`）。缺身份/离线首次无映射时可能以未处理协程异常终止。它也没有 source/account/generation 变动监听或最后发布 currentness fence。`TeslamateRepository.getCurrentCharge:549-559` 不校验 response.car.carId（详情路径有校验）。NoActive+status失败（VM226-232）或新会话 starting（200-209）保留旧 detail，UI132 只有 detail==null 才呈 starting，旧会话可能继续被新 snapshot badge/参数装饰。DC finished 分支保留详情，elapsed 仍无限计时（Screen631-634,828-832）。

最小非布局建议：建立当前会话带作用域/generation/observedAt 的统一投影；按信号排序去重并保留时间和值配对；current response 校验 car 身份；快照只补 current 字段，保留会话已知起点与点序列；无会话起点不以快照时间替代；完成态 elapsed 截至已知结束；身份失效清当前作用域数据并转有限失败状态。现有后端 `telemetry_current_charge_test.go` 仅覆盖单快照电气字段，未覆盖这些实际消费者路径；本轮未找到 CurrentChargeViewModel 行为回归。

### CHG-P2-01：详情离线重建把缺失时长当真实 0，单位依赖另一次 status

- `toLocalSummary` 将缺 durationMin 存为 0（`UnifiedHistoryRepository.kt:597`），raw apiEvidence 仍保留 null；正常 `toAnalysisChargeData` 可恢复 null。详情 offline 重建却用 `localSummary.durationMin`（VM374-375）而不是 `cached.durationMin`，于是原本无时长→0 分钟，且忽略 raw durationStr。在线 header 优先 durationMin，列表优先 durationStr，两者冲突时显示可不同（DetailScreen564-565；ChargesScreen855-856）。
- 历史温度 response/Room receipt 不存温度单位；详情 `units` 从随后独立 status 读取（VM274-282），失败变 null，`UnitFormatter.kt:87-107` 默认 °C 且不转换。self-hosted API 若返回 preferred °F 的历史温度，offline reopen 会把同数值标成 °C；偏好变化也无同响应单位证明。云端 Celsius 链不受此具体触发影响。
- 建议：优先 scoped raw/presentation nullable 时长；存 receipt 对应温度单位/规范化值，未验证单位时不重新贴单位。增加在线/离线同字段与 F/C 回归。

### CHG-P2-02：缺充电坐标时无界借用相邻行程的位置

`ChargeDetailViewModel.resolveChargeCoordinates:500-526` 在缺坐标时直接取该 car 任意最近前序行程终点或后序行程起点，未限制时间差、未证明停车连续、未核验该 drive 的信号资格；DAO 是前/后查询（`DriveSummaryDao.kt:185-192`）。同车隔数日缺充电定位时，也会展示旧行程地点地图，且 UI 视作充电点无来源区别。真实坐标恰为赤道/本初子午线的某一轴 0 又在 fallback 中分别被过滤（VM380-388,506-507）。建议仅在同停车窗且证据满足时回退并携带推导资格，否则维持 no-position；沿用现有布局。

### CHG-P2-03：总能量无覆盖数，短会话隐藏与摘要母集不同

列表摘要 `calculateSummary:934-944` 只求合格已知能量和，但 UI `SummaryCard:711-718` 不显示 energy-known/total coverage；10kWh+一条未知会话时显示“10kWh”而无法识别部分总量，费用则有覆盖计数。图表亦只有大于0 coverage 门而无部分覆盖提示（Screen1091-1116）。`showShortDrivesCharges=false` 隐藏 ≤0.1kWh 记录（VM626-632），摘要/图表仍包含这些记录（642-647）。这是显式代码选择，不是筛选窗口失效，但卡片数与摘要会不同；建议统一当前产品口径或在已有摘要文案说明母集及能源覆盖，补短会话/未知能量消费者回归。不要把未知算0或用全会话数稀释已知平均。

## 路径与链路索引

下文 UI 行号简称都对应当前工作区以下绝对路径（可用文件名在仓库唯一定位）：

- CS = `E:\project\tesla_master\app_mimo\android\app\src\main\java\com\matelink\ui\screens\charges\ChargesScreen.kt`
- CVM = 同目录 `ChargesViewModel.kt`；DS = 同目录 `ChargeDetailScreen.kt`；DVM = 同目录 `ChargeDetailViewModel.kt`。
- LS = 同目录 `CurrentChargeScreen.kt`；LVM = 同目录 `CurrentChargeViewModel.kt`；Calc = 同目录 `ChargeStatsCalculator.kt`。
- CM = `android/app/src/main/java/com/matelink/data/api/models/ChargeModels.kt`；EM = 相同 models 目录 `EnergyModels.kt`。
- UHR = `android/app/src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt`；CP = `android/app/src/main/java/com/matelink/domain/analytics/ChargeCostProjection.kt`。
- HM = 同 analytics 目录 `HistorySummaryMapper.kt`；EB = 同目录 `ChargeEnergyBinding.kt`；HDR = `data/repository/HistoryDetailRepository.kt`；HDP = 同目录 `HistoryDetailPersistence.kt`。

API：`TeslaMateApi.kt:79-98` 为 `GET /api/v1/cars/{carId}/charges`（start_date/end_date/page/show）、`/{chargeId}`、`/current`。`TeslamateRepository.kt:514-587` 通过当前 connection mode/API factory 读取，mock 独立；列表 Moshi `ChargesResponse -> data.charges[]`，详情/当前 `ChargeDetailResponse -> data.car/data.charge`，均 `@JsonClass(generateAdapter=true)`。详情核验 chargeId 和存在时的 carId（57-70）；current 未核验。历史 UHR 每页50读取，raw/qualified 分离，Room 主键 `(localHistoryCarId,chargeId)`，localHistoryCarId 来自 account/origin/stable vehicle 映射，禁止直接用远端 numeric carId 充当全局身份。

历史 raw 已知 DTO 字段编码在 `ChargeSummary.apiEvidence`（不是原始 HTTP 字节、也不证明保留未知 JSON 字段）；物理能量/详情呈现与 raw 分开。HDP19-61 在事务内按 HDR20-82 的作用域、source/instance/record、exact start/end 校验后合并与保存详情点。HDR127-145 支持 limits/rejection；224-253 离线读只接受匹配头和 complete candidate。receipt 的 responseComplete 不等于 providerTotalVerified，UI有对应 notice。

本地云适配实现：`deploy/jourvolt-dev-mock/main.go:737-755` 路由真实 telemetry/history 或明确 mock；`telemetry_service.go:1272-1284,1351-1413` 映射 finished session。MQTT 完成能量由 `energy_history_contract.go:108-173` 用 DCChargingEnergyIn battery counter 与 ACChargingEnergyIn AC counter 的窗口/端点/模式资格生成；混合/未知模式不授予全 AC 输入。TeslaMate archive 桥 `db.go:32-44` 读取 charging_processes + charges，但当前桥只保留少量点字段，见下表缺数据项。这是本地源码能力，不证明同 SHA 已部署或实际 provider 有这些值。

## 参数矩阵

状态含义：`SOURCE_OK`=源码路径、资格/单位与当前用途可解释；不代表真车已通过。`BUG`=上文确定源码缺陷；`SOURCE_DATA_MISSING`=当前生产链不提供所需证据/字段，不能用0填；`UNVERIFIED`=需要实际 provider/设备或产品合同证明。测试栏均为**已存在测试源码覆盖**，本次未执行；`无`为未找到相关消费者覆盖。矩阵共 **67 行**。

### 列表、筛选、摘要、统计图（25行）

| 页面/字段 | 真实来源 / wire / DTO | 计算 | 存储/作用域 | 当前 UI 文件:行 | 无数据原因 / 0 | 测试覆盖/证据 | 判定 |
|---|---|---|---|---|---|---|---|
| 列表：默认日期/今天/7/30/90/365/全部 | start_date；DateFilter；clock zone | start=now-(days-1)，end次日排除；按会话开始归窗 | SavedStateHandle 筛选；Room同车全缓存再过滤 | CS502-540；CVM357-374,615-618 | 无开始时间在有界窗被排除；ALL保留 | ChargeHistoryWindowTest；ChargesRoomCostConsumerTest | SOURCE_OK |
| 列表：自定义/更早历史 | customStart/End，API start/end | loadMore向前30日，转CUSTOM；摘要/列表/图表同窗 | SavedStateHandle；UHR page50 | CS250-275,450-469；CVM377-413 | 空页不删除既有缓存；ALL不继续自动延窗 | Charge64PageRoomConsumerTest；旧日Room消费者 | SOURCE_OK |
| 列表：AC/DC筛选与标签 | energy_contract mode，或AggregateDao index | 显式合同优先；legacy processed非DC→AC | Room aggregate同car/charge，未保留UNKNOWN | CS563-610,941-964；CVM968-988 | UNKNOWN不进入AC/DC；legacy误判见01 | ChargeModeListContractTest仅消费者 | BUG CHG-P1-01 |
| 列表：有/无费用筛选 | EffectiveChargeCost | >0为有；null/0（含已知免费）归无 | 同币种会话费用投影 | CS617-661；CVM183-187,667-683 | “无费用”混合未填、0、免费，源码明确 | ChargeSummaryMetricTest；投影合同 | SOURCE_OK（语义有限） |
| 列表：地点选项/搜索/选中数 | ChargeData.address | visible窗口内distinct/sort；选中地址精确比较 | 同车当前窗，SavedStateHandle选中集 | CS1159-1248；CVM649-659 | null地址不入选项；空集合=全部 | 源码核查；未找到全UI地点组合回归 | SOURCE_OK |
| 列表：短充电隐藏 | batteryInputKwh+showShort | ≤0.1kWh合格会话隐藏；unknown仍可见 | 列表母集不同于summary | CS399-450；CVM626-647 | 真0可被设置隐藏；不是未知 | 源码核查 | BUG CHG-P2-03 |
| 卡片：地点/地址 | address -> toChineseDisplayAddress | 显示格式化 | raw/presentation同车会话 | CS830-842；CM21 | null/blank→unknown location | 源码核查；真实地址未读 | SOURCE_OK |
| 卡片：开始日期时间/排序 | start_date String | formatEditorialDate；列表按字符串降序；is24Hour固定true | Room raw/presentation | CS836-837,796；CVM718 | 未解析日期formatter fallback；不同offset字串排序不保证Instant序 | 窗口测试覆盖筛选；排序/12h无消费者覆盖 | UNVERIFIED（排序与24h偏好） |
| 卡片：时长 | duration_str / duration_min | string优先，min fallback，未校验负值/冲突 | nullable raw receipt | CS854-857 | null→N/A；真0显示0m | 源码核查 | BUG CHG-P2-01 |
| 卡片：电池输入kWh | charge_energy_added或energy_contract.battery_input | qualified batteryInputKwh；一位小数 | raw与合格投影分开 | CS786,860-864；CM49,132-143 | unknown合同不复活raw；真实0保留 | ChargeQualifiedEnergyFlowTest；ChargeEnergyPresentationTest | SOURCE_OK |
| 卡片：SOC起/止 | battery_details.start/end_battery_level | formatSocRange检查0..100 | raw/presentation；跨窗预合并风险 | CS787-788,873-877 | 单侧缺失保持单侧未知；0合法 | UnitFormatter；rawmerge缺实际消费者回归 | BUG CHG-P1-03（绑定） |
| 卡片：费用值/免费/≈ | cost、manual、free、default price | CP同一投影；优先manual/free/source/estimate | ChargeCostOverride Room + source session | CS790-794,866-870 | null不当0；手动0是已知；免费范围过宽 | ChargesRoomCostConsumerTest；CP合同 | BUG CHG-P1-02/03 |
| 卡片：估算来源说明/费用编辑入口 | 当前effectiveCost.source、onEditCost | ≈与估算文字、编辑按钮；旧pending/其他币种/实例说明已被外部变更移除 | 底层证据仍保留，但当前列表不再渲染这些旧说明 | 冻结CS888–935 | 不把旧未渲染说明计当前字段；费用根因不因删除文字关闭 | 源码冻结复核；旧测试是否仍匹配UNVERIFIED | SOURCE_OK（估算/编辑入口）；历史说明不计当前可见覆盖 |
| 卡片：费用编辑/保存/清除 | 用户总金额；Currency setting | finite且≥0；版本冲突拒绝；clear tombstone | source/window/instance/record/currency会话键 | CS294-337,882-884；CVM901-927 | 无有效币种不能保存；旧legacy不自动绑定 | ChargeListCostEditContractTest；ChargePriceOverrideTest | SOURCE_OK |
| 摘要：充电次数 | analysis-eligible filtered charges | charges.size | 当前同车日期/类型/地址/费用筛选；含短充电 | CS701-706；CVM622-647,940-947 | incomplete列表可见但不计分析；空集合0 | ChargeSummaryMetricTest | SOURCE_OK（母集见P2-03） |
| 摘要：总能量kWh/MWh | 每会话batteryInputKwh | mapNotNull finite≥0后sum | 相同filtered stats set | CS711-718；CVM934-935 | 全未知→N/A；部分未知只求已知和，无覆盖提示 | ChargeSummaryMetricTest | BUG CHG-P2-03 |
| 摘要：总费用/≈ | 与卡片相同EffectiveChargeCost | 合格finite≥0求和；有estimate前缀≈ | 同币种；coveredCost集合 | CS723-731；CVM171-179,937 | 无covered→null；真0总计保留 | ChargesRoomCostConsumerTest | BUG CHG-P1-02/03（来源） |
| 摘要：平均每次费用 | same projected costs | total / coveredCount，非全部会话数 | 同币种已知费用分母 | CS736-740；CVM177,945 | 无样本→N/A；不会除0 | ChargeSummaryMetricTest；Room消费者 | SOURCE_OK（来源受P1影响） |
| 摘要：费用覆盖/估算数 | count有效投影/ESTIMATE | covered/total，estimatedCount | 同筛选同币种 | CS749-755；CVM162-163 | estimated0仍为覆盖；unknown不覆盖 | ChargeSummaryMetricTest | SOURCE_OK |
| 摘要：平均能量（未渲染） | avgEnergyPerCharge状态 | totalKnownEnergy / energyValues.size | 计算存在，无当前UI分支 | CVM944；CS697-742未消费 | 不应宣称页面已显示此指标 | 源码字段 inventory | SOURCE_OK（未显示） |
| 图：日/周/月标签与分桶 | start_date -> Instant -> clock.zone | ≤30差值日、≤90周、其他月；Locale周首 | 当前筛选；非UTC日期口径 | CS1049-1063；CVM731-854 | 缺开始点不分桶；空charges无图 | ChargeHistoryWindowTest覆盖日期；周月组合未实跑 | SOURCE_OK |
| 图：总kWh及AC/DC/未知段 | batteryInputKwh+historyChargeType | 分组已知和，coverage>0显示；未知类型单独段 | 当前窗/过滤集 | CS1091-1103；CVM865-872,897 | 无合格能量显示N/A；图数值内部0不等于观测0 | energy-flow测试；类型生产链缺口 | BUG CHG-P1-01 / P2-03 |
| 图：总费用及AC/DC/未知段 | same projected cost | 分段sum；有estimate前缀≈ | 同币种 | CS1105-1118；CVM874-896 | 全未知N/A；空桶内部0 | ChargesRoomCostConsumerTest | BUG CHG-P1-01/02/03 |
| 图：次数及AC/DC/未知段 | charges count/type | count.size；三段和=count | eligible filtered set | CS1120-1129；CVM878-894 | 空桶0；unknown不是AC | ChargeModeListContractTest | BUG CHG-P1-01 |
| 列表：历史/离线/部分数据notice | UHR chargesSyncError、identity、archive pending | 20s超时保留缓存；identity失效清空 | scopeChanges监听；LatestHistoryLoad | CS121-146,239-254；CVM255-279,468-603 | 可验证同身份缓存才显示；空库错误不同于0记录 | ChargesRoomCostConsumerTest；64-page Room消费者 | SOURCE_OK |

### 历史充电详情（24行）

| 页面/字段 | 真实来源 / wire / DTO | 计算 | 存储/作用域 | 当前 UI 文件:行 | 无数据原因 / 0 | 测试覆盖/证据 | 判定 |
|---|---|---|---|---|---|---|---|
| 详情：记录/车辆/窗口身份 | data.car.car_id、charge_id、source/instance/record/start/end | repository ID校验+Room identity exact匹配 | verified history context；HDP事务 | DS103-167；DVM230-321 | 无summary拒接详情；不凭numeric ID借点 | ChargeDetailResponseIdentityTest；ChargeSourceInstanceAdmissionTest | SOURCE_OK（持续已显示scope变更未验证） |
| 详情：地址 | ChargeDetail.address | Chinese display | nullable wire；detail presentation snapshot | DS532-534 | unknown location；不自动用实时地址冒充历史 | ChargeDetailLocalPersistenceTest | SOURCE_OK（rawmerge风险） |
| 详情：开始/结束时间 | start_date/end_date | formatMonthDayTime本地格式 | 同会话receipt | DS552-560 | null→unknown | static；等价Instant持久化测试 | SOURCE_OK |
| 详情：header时长/电池区时长 | duration_min/string | header min优先；stats取min≥0 | online raw；offline重建scalar0 | DS563-566,298；DVM374-375 | raw缺失可被offline伪0；负值header无过滤 | 源码核查；offline point测试未覆盖nullable时长 | BUG CHG-P2-01 |
| 详情：header电池输入kWh | batteryInputKwh | qualified amount 2 decimals | detail energy contract或legacy兼容 | DS588-601；DVM287；CM127 | finite非负；unknown不借raw；0保留 | ChargeQualifiedEnergyFlowTest | SOURCE_OK |
| 详情：能量added | batteryInputKwh | Calc106 | battery input物理侧；非SOC×容量 | DS285 | 不证明DC柜侧输入；缺合同→N/A | ChargeEnergyBalanceTest；flow测试 | SOURCE_OK |
| 详情：能量used | inputEnergyKwh -> ac_input | Calc107；仅合格AC session | ac_charger_input侧，绝不当电池输入 | DS286 | archive桥没此字段；DC/混合AC拒绝 | ChargeQualifiedEnergyFlowTest | SOURCE_DATA_MISSING（部分源） |
| 详情：效率 | AC input与battery input同窗/源/time_basis | battery/input×100，input>0，0≤battery≤input | EB15-33；不可用legacy两scalar证明 | DS287；Calc109-111 | 0/0或DC/不同窗→N/A；battery0/input>0→0% | ChargeEnergyBalanceTest | SOURCE_OK（源可能缺） |
| 详情：首/末观测SOC | sorted unique in-window点；无任何SOC点才summary fallback | Calc81-98；单点按end/start资格分侧 | 原点Room+summary raw | DS295-296 | 0合法；冲突/out-window不复活summary | ChargeStatsCalculatorTest单点/冲突/零 | SOURCE_OK |
| 详情：观测SOC变化 | 首末有效SOC | end-start，允许负 | 相同series | DS297 | 缺任一侧→N/A；不估电量 | ChargeStatsCalculatorTest | SOURCE_OK |
| 详情：SOC曲线/单点 | point.date、battery_level | Calc.project排序、同刻唯一、0..100、会话闭区间 | HDR points/chunks | DS301-310,1046-1068 | ≥2曲线；1真实点；0无曲线 | ChargeSingleObservationTest（instrumented源码） | SOURCE_OK |
| 详情：功率max/min/avg | point.charger_details.charger_power Double kW | 合格点极值取Int；avg算术样本平均，不是时间加权 | Calc43,57-61 | DS314-333 | 无点整节不显示；真0保留；非finite/负排除 | ChargeStatsCalculatorTest | SOURCE_OK |
| 详情：功率曲线/单点 | 同power合格series | 与统计完全同一series，保留时刻 | 原始点缓存 | DS324-333,959-975 | 不从能量/时长生成曲线 | stats/series一致性测试 | SOURCE_OK |
| 详情：电压max/min/avg | charger_details.charger_voltage V | 非负finite；max/minInt；样本mean | Calc44,63-66 | DS345-347 | 当前archive桥丢此字段，不是UI计算失败 | stats calculator测试；bridge模型 | SOURCE_DATA_MISSING |
| 详情：电流max/min/avg | charger_details.charger_actual_current A | 非负finite；max/minInt；样本mean | Calc45,68-71 | DS348-350 | 当前archive桥/历史session模型无电流 | stats calculator测试；bridge模型 | SOURCE_DATA_MISSING |
| 详情：电压/电流曲线/单点 | 各自qualified time/value pairs | 独立信号时间，≥2曲线，1点 | 同receipt；不靠AC/DC决定可否显示 | DS353-373,979-1015 | 有哪一项显示哪项；两者均无整节省略 | ChargeStatsCalculatorTest | SOURCE_OK（源缺字段） |
| 详情：温度max/min/avg | outside_temp点；fallback outside_temp_avg | 样本极值/mean；仅所有点都无temp才fallback均值 | raw温度无单位receipt；status.units后取 | DS378-400；Calc73-79 | summary均值不伪造min/max；0/负温保留 | temperature/stats测试 | BUG CHG-P2-01（单位）；SOURCE_DATA_MISSING（bridge丢温度） |
| 详情：温度曲线/单点 | qualified outside_temp series | ≥2/1/0分支；仅贴API单位无二次转换 | HDR raw points | DS389-400,1019-1042 | 旧bridge不保留outside_temp；无点不造曲线 | ChargeStatsCalculatorTest | BUG CHG-P2-01（单位绑定） |
| 详情：费用总额/来源/≈ | CP投影 | actual/manual/free/estimate/unavailable映射 | same source/window/currency | DS200-212,404-418,615-630 | provider0不自动免费；实例缺失有提示 | ChargeDetailCostPresentationTest；CP合同 | BUG CHG-P1-02/03 |
| 详情：每kWh价格 | same projected cost / batteryInputKwh | only energy finite>0，三位小数 | 当前币种 / **电池输入**kWh | DS405-409,417 | energy0/unknown→N/A；这是电池侧有效价，不能证明电表电价 | ChargeDetailCostPresentationTest；源码 | SOURCE_OK（口径有限） |
| 详情：手动金额dialog/遗留/外币/clear | bound override+legacy+other currency | validManualChargeTotal finite≥0；version CAS | ChargeCostOverrideStore/DAO；source/window/currency绑定 | DS663-731；DVM449-488 | 外币不转汇；手动0不等于missing | ManualChargeAmountTest；CP合同 | SOURCE_OK |
| 详情：地图/打开地图 | latitude/longitude；缺失时邻接drive fallback | Amap usableCoordinate；外部geoURI | 同car；回退未证实同停车窗 | DS735-829；DVM492-529 | 缺点显示no-position，但可能错误回退；轴0丢失 | offline point测试未覆盖稀疏邻接位置 | BUG CHG-P2-02 |
| 详情：所属行程/移除 | TripRepository.findTripContaining(localCar,TYPE_CHARGE,id) | 保存行程名称/leg reference | 同localHistoryCarId；remove前后context check | DS235-242；DVM266-268,532-541 | 无所属行程不显示；不是实时推断 | 源码核查；本轮未读真实行程 | SOURCE_OK |
| 详情：缓存/完整性/来源提示 | receipt sourceInstance/sourceId/providerTotalVerified/reason | response delivery与完整覆盖分离 | HDR/HDP limits、chunk、complete receipt | DS225；DVM315-320,407-416 | no points/损坏/总数未验证有notice；不造raw曲线 | ChargeDetailOfflinePointConsumerTest；ChargeDetailRoomAndroidTest | SOURCE_OK（实际provider总点数UNVERIFIED） |

### 当前充电（18行）

| 页面/字段 | 真实来源 / wire / DTO | 计算 | 存储/作用域 | 当前 UI 文件:行 | 无数据原因 / 0 | 测试覆盖/证据 | 判定 |
|---|---|---|---|---|---|---|---|
| 当前：loading/starting/unsupported/no-active | current endpoint outcome + status.isCharging | 30s轮询；starting4s；404停止 | 仅内存；DC marker DataStore | LS128-191；LVM85-117,199-265 | status失败应未知；已有detail会遮蔽starting | 后端单snapshot测试；无VM消费者测试 | BUG CHG-P1-04 |
| 当前：LIVE/recent/history/mixed badge | adapter source/observedAt/fieldSources；legacy teslamate_api | snapshotEvidence freshness | 只描述status，非charge receipt | LS107-110,196-214；LVM270-293 | 失败unavailable；可能与保留旧detail混在一起 | SnapshotEvidence相关源码 | BUG CHG-P1-04（绑定） |
| 当前：开始SOC | battery_details.start_battery_level | 直接显示，不验证0..100 | current response；后端merge覆盖 | LS512,537-544 | backend有session值仍被删；unknown不伪0 | backend static | BUG CHG-P1-04 |
| 当前：当前SOC | current_battery_level ?: end_battery_level | 直接显示 | current detail；无raw point资格核验 | LS513,562-570 | null unknown；非法整数未过滤 | 无当前消费者测试 | BUG CHG-P1-04 |
| 当前：目标SOC/进度条 | status.chargeLimitSoc | all nonnull才进度；start/current/target | status与detail无同会话证明 | LS514,581-602；LVM123 | null不画；范围/0目标未在消费者筛 | 无当前消费者测试 | BUG CHG-P1-04 |
| 当前：已充SOC delta | current-start | "+%d%%"；负差会显示"+-N%" | 同header输入 | LS694-698 | 缺侧unknown；0为+0% | 源码核查 | BUG CHG-P1-04 |
| 当前：已充kWh | raw charge_energy_added | finite≥0 两位小数；绕过合同/source conflict | 内存raw current detail | LS699-704 | unknown合同仍可显示raw；0保留 | flow测试仅历史卡片/详情，未覆盖此消费 | BUG CHG-P1-04 |
| 当前：瞬时功率 | last chronologicalPoint.chargerPower Int | roundToInt后显示；未用detail.chargerPower | VM reverse原数组 | LS508-510,672-674 | 最后点缺power隐藏；top-level有值也不fallback | ChargePointMqttPrecisionTest仅DTO Double | BUG CHG-P1-04 |
| 当前：AC/DC badge | status.chargerPhases优先，否则Calc.detectChargeType | 0 DC/1..3 AC；status冲突未与contract核验 | current status + current detail | LS681,723-746；LVM131-136,181 | unknown有第三态；非空status phase压过合同 | ChargePresentationTest只phase helper | BUG CHG-P1-04 |
| 当前：elapsed | detail.start_date | 每秒now-start | 内存，无session start可信度 | LS631-634,807-855 | null unknown；snapshot时刻误作起点，finished还增长 | 无实际current elapsed测试 | BUG CHG-P1-04 |
| 当前：预计完成时刻/剩余时长 | status.timeToFullCharge hours | now+round(hours×60) | status本次值；未用observedAt校正 | LS639-649,965-974；LVM122 | ≤0隐藏；无过期字段资格/finite上界 | 无当前ETA消费者测试 | BUG CHG-P1-04（freshness绑定） |
| 当前：充电口开/关 | status.chargingDetails.chargePortDoorOpen | Boolean已知才显示 | status字段 | LS422-426；LVM124,145 | null省略，false是已知关闭 | ChargePresentationTest相关helper有限 | SOURCE_OK（scope见P1-04） |
| 当前：充电相位类型 | status.chargerPhases | 0 DC/1 single/2或3 three | status字段 | LS429-438；ChargePresentation14-19 | unknown相位不显示 | ChargePresentationTest | SOURCE_OK（scope见P1-04） |
| 当前：电压/实际电流/请求电流/最大请求电流 | status charger_voltage / charger_actual_current / charge_current_request / max | formatChargeMetric仅过滤非finite，保留一位小数 | 本次status，失败清instant fields | LS440-450；LVM126-129,139-155 | null/非finite省略；真0显示；负电压/请求电流也会显示 | ChargePresentationTest（未拒绝负值） | BUG CHG-P1-04（资格）；数值边界P2 |
| 当前：计划充电开始 | status.scheduledChargingStartTime | parseable ISO才显示本地时间 | status字段 | LS452-453；LVM130 | null/不可解析省略 | ChargePresentationTest | SOURCE_OK |
| 当前：功率/SOC曲线与不足样本数 | chronologicalPoints各自mapNotNull | ≥2曲线；否则samples insufficient；x按总点索引 | 不落Room；reverse不保证排序 | LS301-338,370-395,903-945 | 缺值时x错配；SOC不校验；数值先取整 | 无当前曲线消费者测试 | BUG CHG-P1-04 |
| 当前：AC电压+电流双轴曲线 | raw chargerDetails voltage/current Int | isDcCharge==false才画；两组≥2 | 各自过滤但共用全部point的时间 | LS341-367 | 有一侧不足不画；DC不显示此图；两侧不同缺值错位 | 无当前双轴消费者测试 | BUG CHG-P1-04 |
| 当前：DC充完未拔提醒/完成时刻 | status.isChargeCompletePluggedIn + persisted wasLastSessionDc + stateSince | bool合取；插枪/拔枪更新marker | ChargeSessionStateDataStore按localCar；未存sessionID | LS286-289,859-895；LVM159-169,212-224 | 冷启未观察过DC可能无marker；未知状态不伪完成 | ChargingNotificationIdentityTest非此VM完整路径 | UNVERIFIED（会话marker生命周期） |

## 可用性、未渲染字段与真实来源门

- **续航增加**：CM28-29,61-63,96-97,175-178 有 range_ideal/rated、point battery_info 但当前三页无 added-range 展示分支；不能把 DTO 有字段写成页面有指标，也不能用SOC或名义容量制造续航。当前云历史 `telemetry_service.go:1354-1355` 将 range 设 nil；current合并只提供end range。标记 SOURCE_DATA_MISSING（历史源）/UNVERIFIED（未来展示合同）。
- **里程表**：CM31,99 有odometer，当前三页没有对应可见数值；Cloud历史映射nil，current有status值。不能作为当前充电距离或续航证明。
- **电池温度**：这些页面只消费 `outside_temp`；没有 pack/battery-temperature wire→UI链。温度卡不是电池温度；缺字段时保持缺失。
- **AC/DC/电池各物理侧**：UI“added”是 batteryInput；“used”仅qualified AC charger input；DC柜侧输入、stored-energy gain、损失kWh未在当前三页单独渲染。`battery_input` 来源 DCChargingEnergyIn 的语义与实际车辆上报、同窗完整端点仍要真实provider验证；不能用名称或2xx证明物理准确。
- **默认电价估算**：CP112-115、ManualChargeAmount 通过非负有限energy×defaultPrice；是所选币种的本地估算。不能当账单、不能当AC电表能量收费。正数源bill币种缺失会返回unavailable而不静默套本地币种，UI保留来源未确认金额说明；当前桥/云历史没输出 currency_code，是 SOURCE_DATA_MISSING，而非应放宽币种资格的理由。
- **历史电气/温度缺失的真实位置**：TeslaMate SQL最初聚合整条charge行，但decoder/model丢 voltage/current/phases/fast/outside_temp；import的charge point也不接电压/电流/相位；最终historySessionMap只映射power及已有outside temp。Android有DTO和图表不代表后端已贯通。需要桥/import/session/history一致扩字段并证明真实源实际非null；不得用固定240V、16A或名义电池值填。
- **详情原点完整性**：已持久化的是本次DTO已接收点，完整receipt仅证明写入/校验；provider总点数及分页证明若没有，仍显示total unverified。曲线只显示已接收且合格点，不证明整个充电过程的连续覆盖；样本mean不应当时间平均功率。
- **数据保留**：UHR远端空页/失败不删除旧缓存；前景scope失效列表会清旧身份内容。历史详情没有持续scopeChanges collector，装载期间有checkContext但已显示后切账号/同numeric car重绑应增加实际导航/生命周期回归；当前页缺口已计入P1-04。没有读取私有数据库来证明真车当前scope无错。

## 验证清单与结果

- [x] 3页全部可见值/条件分支、filters、summary、3类统计图、5类历史曲线、当前3类曲线、费用编辑与notice已列入67行矩阵；未渲染字段另外登记。
- [x] UI→ViewModel→物理资格/费用投影→Moshi DTO→repository→Room summary/point receipt→本地backend/archival字段映射已追踪。
- [x] 检查已知0、缺失、非finite、负数、partial denominator、同车/同币种/同会话窗口、源实例与record ID、冷启动/离线/旧响应问题。
- [x] 精确定位四项P1与三项P2；根因共享处未重复计数。
- [x] 只写本文；没有git add/commit、构建、运行新测试、设备/网络操作。
- [ ] 父审计独立确认P1，补真实调用路径RED回归（尤其预merge、未知aggregate、current VM身份/合同/点序与免费资格）。
- [ ] 同SHA真实provider样本核验（按隐私限制）、已安装版本三页同record ID/窗口/币种/冷启比对。

源码存在测试：ChargeHistoryWindowTest、ChargeSummaryMetricTest、ChargeModeListContractTest、ChargeQualifiedEnergyFlowTest、ChargeStatsCalculatorTest、ChargesRoomCostConsumerTest、ChargeDetailOfflinePointConsumerTest、ChargeDetailResponseIdentityTest、ChargeDetailLocalPersistenceTest、ChargeSourceInstanceAdmissionTest、Charge64PageRoomConsumerTest、ChargeCostProjectionContractTest、ChargeEnergyBalanceTest，以及AndroidTest ChargeSingleObservationTest/ChargeDetailRoomAndroidTest。本次只核查测试源码，不把测试名或旧pass当本轮执行结果。
