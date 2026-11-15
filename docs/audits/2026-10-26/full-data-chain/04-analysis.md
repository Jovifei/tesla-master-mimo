# 分析页全数据链审查：Battery / Efficiency / Range / Vampire / TemperatureTrend / TpmsTrend

审查日期：2026-10-26。执行者：`/root/audit_analysis_final`；实际读取根目录：`E:\project\_worktrees\matelink-release-data-audit-20261026`。本文件仅记录审查，没有修改产品、测试、布局、构建或设备，没有访问账户、车辆、数据库内容或网络。原工作目录在并行写入，不用其当前文件作本页证据。Codegraph 默认索引能响应，但显式指定本隔离 worktree 返回无文件；因此行号以本隔离副本的 scoped rg/读取为准。

## 计划与完成清单

- [x] 核对六条已注册路由、真正调用的 UI、VM 与可见字段。
- [x] 追踪 API wire → nullable DTO → qualified domain → repository → Room/scoped cache → UI。
- [x] 核对公式、单位、首尾配对、时间窗口、真零/负值、样本资格和缺失处理。
- [x] 核对实际存在的测试源码，并区分 helper/源码字符串检查、消费者测试和真实运行。
- [x] 汇总最小非布局修复及源证据缺口；复核当前 Range Room-first 修复，不重新登记已修复问题。

本组 **6/6 路由，55 个矩阵位置，9 个新增源码发现（P1 × 1、P2 × 8）**。另有共享问题仅交叉引用：`01 H02` 缓存单位、`03 D01` 不合格 SOC 能量推断、`07 SYS-04` 旧胎压 profile 键；不重复计数。`SOURCE_OK` 只表示本次查到的源码处理符合该项合同，绝不表示设备/provider/生产验收通过。

| 路由 | 矩阵位置数 | 本组新增发现归属（跨页根因只算一次） |
|---|---:|---|
| Battery | 11 | AN-02/03/04，P2 × 3 |
| Efficiency | 9 | 0；保留共享03 D01影响 |
| Range | 10 | AN-06，P2 × 1；AN-05跨页影响另归TemperatureTrend |
| Vampire | 9 | AN-01，P1 × 1；AN-08/09，P2 × 2 |
| TemperatureTrend | 8 | AN-05/07，P2 × 2 |
| TpmsTrend | 8 | 0；SYS-04仅交叉引用 |
| 合计 | **55** | **9（P1 × 1，P2 × 8）** |

## 文件索引与共同入口

下文短名均相对本隔离根目录，数字为一基源码行。每行的短名与本表组成精确文件位置。

| 短名 | 文件 |
|---|---|
| NAV | `android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt` |
| BS / BV | `android/app/src/main/java/com/matelink/ui/screens/battery/BatteryScreen.kt` / `BatteryViewModel.kt` |
| ES / EV | `android/app/src/main/java/com/matelink/ui/screens/efficiency/EfficiencyScreen.kt` / `EfficiencyViewModel.kt` |
| RS / RV | `android/app/src/main/java/com/matelink/ui/screens/range/RangeScreen.kt` / `RangeViewModel.kt` |
| VS / VV | `android/app/src/main/java/com/matelink/ui/screens/vampire/VampireScreen.kt` / `VampireViewModel.kt` |
| TS / TV | `android/app/src/main/java/com/matelink/ui/screens/temperature/TemperatureTrendScreen.kt` / `TemperatureTrendViewModel.kt` |
| PS / PV | `android/app/src/main/java/com/matelink/ui/screens/tpms/TpmsTrendScreen.kt` / `TpmsTrendViewModel.kt` |
| API / REP | `android/app/src/main/java/com/matelink/data/api/TeslamateApi.kt` / `android/app/src/main/java/com/matelink/data/repository/TeslamateRepository.kt` |
| CAR / BAT / DR / AD | `android/app/src/main/java/com/matelink/data/api/models/CarModels.kt` / `BatteryModels.kt` / `DriveModels.kt` / `AdapterModels.kt` |
| UH / AH | `android/app/src/main/java/com/matelink/data/repository/UnifiedHistoryRepository.kt` / `android/app/src/main/java/com/matelink/domain/analytics/AnalysisHistoryRepository.kt` |
| HM / WIN | `android/app/src/main/java/com/matelink/domain/analytics/HistorySummaryMapper.kt` / `AnalysisHistory.kt` |
| BT / PR / WE / PCT / RD | `android/app/src/main/java/com/matelink/domain/analytics/BatteryTrend.kt` / `PersonalizedRange.kt` / `WeightedEfficiency.kt` / `Percentile.kt` / `RangeDeviation.kt` |
| SW / SH | `android/app/src/main/java/com/matelink/domain/analytics/StandbyWindowAnalysis.kt` / `StandbyHistoryDerivation.kt` |
| TA / TH | `android/app/src/main/java/com/matelink/domain/analytics/TpmsTrendAnalyzer.kt` / `android/app/src/main/java/com/matelink/data/repository/TpmsHistoryRepository.kt` |
| TO / TW | `android/app/src/main/java/com/matelink/data/sync/TpmsObservationEvidence.kt` / `TpmsPressureWorker.kt` |
| DS / DD | `android/app/src/main/java/com/matelink/data/local/entity/DriveSummary.kt` / `android/app/src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt` |
| TP / TD | `android/app/src/main/java/com/matelink/data/local/entity/TpmsPressureSample.kt` / `android/app/src/main/java/com/matelink/data/local/dao/TpmsPressureSampleDao.kt` |
| CACHE / UF | `android/app/src/main/java/com/matelink/data/local/VehicleStatusStore.kt` / `android/app/src/main/java/com/matelink/domain/model/UnitFormatter.kt` |
| CLOUD / TC / HOME | `deploy/jourvolt-dev-mock/main.go` / `deploy/jourvolt-dev-mock/telemetry_core.go` / `deploy/teslamate-home-docker/adapter/cmd/adapter/main.go` |

共同历史链 **H**：`API:99-106` 的 `api/v1/cars/{carId}/drives` → `REP.getDrives` 同响应 `units`/`DR:17-75` → `UH:239-300` 按确认车辆加载全部分页、合并 raw receipt、写入 `DriveSummary` → `HM:108-134` 以同车、同源、同时间窗的 evidence/presentation 资格恢复 → `AH:89-127` 检查 verifiedReadContext 并过滤 analysis-eligible 记录。`DR:55-61` 才把同响应 mi 换算为 km/kmh；`DR:63-75` 要求合格能量合同，保留有限净负能量与真零。

持久化 **R**：`DS:17-20,23-72` 主键 `(localHistoryCarId, driveId)`，标量、quality 与原始 `apiEvidence` 同存；`UH:205-236` 只从已确认负 local ID 的当前 scope 读 Room，标 `STALE`，`fetchedAt=Instant.EPOCH`，不会伪装本次远端观察。`AH:98-125` 再核对 source/origin/account/vehicle binding；内存 snapshot cache 不是冷启动证据。

实时状态链 **S**：`API:74-77` `/status` → `REP:449-460` → `CarStatusWithUnits(status, units)` → `CAR:86-105,151-153` 的 nullable SOC/range/temperature。`UF:9-11,60-65,87-92` 对 raw API 数字只附原响应单位；`UF:17-21` 是规范 km 的专用显示转换。当前 cloud 声明 km/bar/C，自托管 legacy preferred units 不能假定相同。`CACHE:26,73-87` 保存 CarStatus 和时间，未保存 units；共享缺陷归 `01 H02`。

### 六路由可达性

| route | 实际入口与加载 | 范围 |
|---|---|---|
| Battery | `NAV:772-779` → `BS:97-111` → `BV.setCarId` | 容量/健康、历史趋势、实时 SOC/续航、同路由详情弹层；详情不是额外注册路由 |
| Efficiency | `NAV:996-1001` → `ES:40-55` → `EV.load(carId)` | 加权效率、窗口、月趋势、速度分组、个人分布与行程位置 |
| Range | `NAV:1012-1017` → `RS:81-92` → `RV.setCarId` | 额定里程下降偏离、分组、行程、90日个性化模型 |
| Vampire | `NAV:1020-1024` → `VS:41-46` → `VV.load()` | route 明有 carId（`NAV:228`），当前没有传入页面/VM，见 AN-01 |
| TemperatureTrend | `NAV:950-955` → `TS:65-75` → `TV.load(carId)` | 7/30日 Room 行程均温观测、当前快照、极值、insights |
| TpmsTrend | `NAV:941-946` → `PS:71-82` → `PV.load(carId)` | 7/30日已验证四轮压力收据、曲线、首末差与可能因素 |

## 逐位置矩阵

测试编号 `T1…T12` 指后面的实际现存测试源码索引，均未由本执行者运行。`BUG(shared …)` 不纳入本组新增8项。

### Battery（11位置）

| ID / route / metric | UI → VM → wire / DTO / domain / repository | 公式与单位 | Room / scoped cache | null / 0 / negative / coverage | 现有证据与判断 |
|---|---|---|---|---|---|
| B01 Battery 健康可用态/错误/收集中 | `BS:148-170,349-354` → `BV:86-102,300-349` → `REP:649-684` → `API:114-122`、`BAT:18-24` | 正容量字段才 AVAILABLE；readiness 区分 unsupported/collecting | health 无独立 Room；R 只提供趋势 | 空 health 不造容量；404不自动当unsupported；cloud `CLOUD:775-782` 明确 null+unsupported | T1/T3，**SOURCE_OK**；实际测量容量 **SOURCE_DATA_MISSING**，不能把 unsupported 当客户端算错 |
| B02 Battery 当前/原始容量、健康%、损失kWh/% | `BS:227-292,734-849` → `BV:372-400` → BAT、REP health | current/max kWh；health 优先 API 0…100，否则 current/max×100；loss=max−current 下限0 | 仅当前 VM health；无观测容量持久收据 | 缺任一正容量不显示容量卡；max 是接口字段，未证明是出厂实测基线；真负容量拒绝 | T1/T3；nullable门 **SOURCE_OK**；容量/基线 provenance **UNVERIFIED**；云端无容量是源缺失 |
| B03 Battery 当前SOC/进度 | `BS:1073-1153` → `BV:378,439` → S 的 battery_details.battery_level | %，进度SOC/100 | CACHE 同scope；未存单位问题不影响SOC量纲 | hasBatteryStatus基于 observed nullable，真0仍显示；未把缺SOC的内部primitive0画出来 | T2，**SOURCE_OK**；未跑真实状态 |
| B04 Battery 可用SOC | `BS:1078,1158-1166` → `BV:379,433` → CAR.usableBatteryLevel | % | 同S/CACHE | 缺usable被batteryLevel代填，现有UI无 observed 标记 | T2只检查主要SOC存在性，未测此回退；**BUG AN-02** |
| B05 Battery 估计/额定/理想续航 | `BS:1174-1251` → `BV:380-382,440-442` → S/CAR battery_details | raw API距离+原响应单位 | CACHE；旧receipt单位遗失归H02 | 单项null显示不可用；真0保留；没有用另一个range替代缺项 | T2，在线 **SOURCE_OK**；缓存 **BUG(shared 01 H02)** |
| B06 Battery 100%续航外推 | `BS:280-285,1056-1058,1307-1385` → `BV:384-389` → 同一次S SOC和rated range | rated/SOC×100；是续航外推，非实测kWh | VM；缓存仍受H02 | 仅SOC≥10且range>0；低SOC/缺值不除0；未用SOC×名义容量造能量 | T3有不发明fallback的源码检查；**SOURCE_OK**公式；采样同刻性 **UNVERIFIED** |
| B07 Battery 新/当前最大续航、续航损失 | `BS:304-305,892-969` → `BV:403-411` → BAT.max_range/current_range + B06 → `BatteryRangeMetrics.kt` | loss=max−current下限0；单项API range或B06 | health无Room；S缓存有scope | 真0范围保留，负/非finite由BatteryRangeMetrics拒绝；缺max时不造损失 | T1的BatteryRangeMetricsTest；**SOURCE_OK**nullable/公式；health range单位及历史最大基线 **UNVERIFIED** |
| B08 Battery 趋势样本/按日中位点 | `BS:371-445,501-560` → `BV:295-324,446-452` → H/R → BT:62-95 | rated range / (SOC/100)，km；同日中位数 | 原始历史R；趋势每次重算，缓存标STALE | SOC70…100、温度15…30、range有限正；但SOC与range独立首尾fallback可错配 | T4测试纯BT，未覆盖VM配对；**BUG AN-03** |
| B09 Battery 趋势current/baseline/degradation/置信度/覆盖/日期 | `BS:448-485` → BV → `BT:97-145,160-174` | ≥10样本、≥30天；recent末30日中位数；baseline首30日且必须早于recent；(base−current)/base×100；confidence算法上限95 | R，scope证明后重算 | 不足门槛UNAVAILABLE；无独立baseline为TREND_ONLY不出衰减；负衰减保留；规范km被raw formatter附mi | T4；资格公式 **SOURCE_OK**，显示 **BUG AN-04**，输入受AN-03 |
| B10 Battery lifetime kWh / 终身循环次数 / 剩余能量kWh | 当前 `BatteryStats BV:147-169`、`BAT:18-24` 无这些字段；`BS:1015-1067` 详情仅SOC/range | 没有终身累计或cycle公式；B06不是容量/剩余能量 | 无对应持久字段/本页消费者 | 不从有限历史补成lifetime、不从SOC造实测kWh；TC:33-65无EnergyRemaining | **SOURCE_DATA_MISSING**；属于未提供字段/非当前UI位置，不能宣称已有能力或登记虚构显示bug |
| B11 Battery Room先读/身份/刷新/旧结果 | `BS:160-163,220-223` → `BV:226-238,274-299,326-338` → R/CACHE → scoped loader | 同scope cached status/trend先发；网络后更新 | CACHE以origin/account/local ID/stable identity哈希；cloud须UIDdigest | account/origin/ABA更换重置；历史缓存有banner；health非持久；cached status没有units仍受H02 | T3 scoped helper与源码检查；**SOURCE_OK**身份代码；冷启动/整页runtime **UNVERIFIED** |

### Efficiency（9位置）

| ID / route / metric | UI → VM → wire / DTO / domain / repository | 公式与单位 | Room / scoped cache | null / 0 / negative / coverage | 现有证据与判断 |
|---|---|---|---|---|---|
| E01 Efficiency 头部平均效率 | `ES:130` → `EV:223-235,266,317-318` → H → WE:23-44 | 同一合格子集 Σnet kWh / Σkm ×1000，Wh/km；不是行程ratio均值 | R | 正有限距离+有限能量；真0与净负能量保留，缺一对两边都不贡献 | T5/T6；公式 **SOURCE_OK**；上游SOC估算资格 **BUG(shared 03 D01)** 会污染此页，不重复记 |
| E02 Efficiency 样本数/总距离 | `ES:147-153` → `EV:271-272` → WE | count合格能量/距离对；Σ合格km | R重算，非全车辆终身里程 | 缺能量行程不计距离分母；内部0只在driveCount>0才当指标显示 | T5，**SOURCE_OK**；这是合格子集统计，不表示全部来源完整 |
| E03 Efficiency ALL/90日/夏冬/自定义窗口 | `ES:180-189` → `EV:199-222` → WIN:86-106 | 最近90日含今日89日前；夏6…8月/冬12,1,2跨所有年份；custom两端闭区间 | R按startDate日期重算 | 缺日期不入分组；FILTER_EMPTY/INSUFFICIENT_COVERAGE与NO_RECORDS分开 | T5/T12；**SOURCE_OK**；时区按设备LocalDate，provider实车日期 **UNVERIFIED** |
| E04 Efficiency 90日/夏/冬对照卡 | `ES:199-209` → `EV:254-256,279-285` → H/WE/WIN | 每卡独立固定窗口、各自ΣE/ΣD；Wh/km | R | 不依赖当前选中窗口；缺数据null；净负/真0仍有效 | T5/T6；公式 **SOURCE_OK**，上游D01同E01 |
| E05 Efficiency 月趋势 | `ES:168-175,408-433` → `EV:306-315` → H/WE | 年月分组、同合格对加权；点有sampleCount，按年月排序 | R，无合成月样本 | 空月份没有伪0；无日期不出点，负净能耗保留 | T6 monthlyTrendWeightsTheSameQualifiedPairsAndKeepsNetRecovery；**SOURCE_OK**；完整自然月数据 **UNVERIFIED** |
| E06 Efficiency 速度分组柱图 | `ES:277-295,436-450` → `EV:298-304` → DR.speedAvgKmh、WE | 20km/h箱；各箱ΣE/ΣD；规范km/h/Wh/km | R保留原lengthUnit再规范化 | 速度必须正有限；缺/未知单位不编0箱；净负/0能耗在signed chart上保留 | T6速度单位消费者与signedScale；**SOURCE_OK**；provider speed_avg样本均值语义承接03 D14，无新增计数 |
| E07 Efficiency 个人分位/min/median/max | `ES:213-235` → `EV:228-240` → DR.efficiencyWhKm/PCT:14-30 | 当前加权效率放入本车同窗行程效率分布；min/lower-middle median/max，rank0…100 | R当前选窗 | 只比有限样本；空返回null；无公共车群分布 | T5/PCT现存测试；**SOURCE_OK**本车位置；不能称车队排名 |
| E08 Efficiency 四分位桶/最近20条行程/百分位条 | `ES:236-271,352-406` → `EV:237-250` → H/PCT | <25/<50/<75/其余各桶计数；全合格分布算rank，行程按日期倒序，UI仅取20条 | R；展示限制不截断底层分布 | 无qualifying能量对不造位置；正负效率都可比较；公开benchmark显示不可用 | T5/T6，**SOURCE_OK**源码；未挂载图表runtime |
| E09 Efficiency cache/错误/同ID换身份 | `ES:66-110` → `EV:119-194,213-218,257-261` → AH/R | Room先发STALE→远端；身份代次、cancellation、同context发布栅栏 | R；旧账号投影清除 | unreadable Room允许同身份remote恢复；网络失败可保留同身份cache；非数据0空态 | T5真实VM的合成source测试存在；**SOURCE_OK**现有修复；本次未运行 |

### Range（10位置）

| ID / route / metric | UI → VM → wire / DTO / domain / repository | 公式与单位 | Room / scoped cache | null / 0 / negative / coverage | 现有证据与判断 |
|---|---|---|---|---|---|
| R01 Range 总偏离百分比 | `RS:358-413` → `RV:329-341,398-416` → H/DR → RD:7-14 | 单程abs(额定range下降−实际km)/额定下降×100；再按行程算术mean | R | 额定下降>0、实际distance有限≥0；缺端点排除；该量不是预测准确率 | T7 RangeDeviationTest；**SOURCE_OK**公式；未将额定下降称实测电能 |
| R02 Range 合格行程数/总距离 | `RS:439-446` → `RV:341,354-356` → H/toRangeTrip | count有效额定端点行程、Σactual km | R | 真0实际distance可入；无trip显示无记录，不画0统计；不是全部历史里程 | T7；**SOURCE_OK** |
| R03 Range 窗口/空态/缓存提示 | `RS:156-175` → `RV:170-181,329-335` → WIN/H | ALL/90日/夏冬/custom与E03相同 | R first/STALE | 区分源无记录、过滤为空、字段覆盖不足；缺range端点不以当前range回填 | T7/T12；**SOURCE_OK** |
| R04 Range 夏/冬/低速/高速偏离 | `RS:195-200` → `RV:338,342-343,423-427` → H/DR.speedAvgKmh | 夏/冬全历史固定季节；低速≤40km/h、高速>40；各自mean偏离 | R | speed缺/未知不入箱，真0属低速；季节对照不随当前窗截断 | T7 RangeSpeedUnitsConsumerTest；**SOURCE_OK**；来源speed样本均值含义承接03 D14 |
| R05 Range 每程起终/地址/预计与实际距离/偏离 | `RS:219-220,471起RangeTripCard` → `RV:398-417` → H/DR | 预计=起点额定−终点额定，实际=规范km；percent同R01 | R含原始首尾，地址为已验证投影 | 缺任一range端点或distance排除，不用理想range代替；nullable地址有空态 | T7；**SOURCE_OK**；自然行程完整性 **UNVERIFIED** |
| R06 Range 个性化模型分组/效率/预测续航 | `RS:227-306` → `RV:380-393,420-421` → H + S + health → PR:65-113 | 固定近90日；同温度/速度组≥5次且≥100km，否则全局≥10次且≥300km；容量kWh×1000/Wh/km | 样本R；health/status仅VM，无预测receipt | 模型只接受正能量/正距离（有意不把回收行程当全电池续航模型）；无测得容量不发数字；current_capacity是总可用容量，不是当前剩余电能 | T8；容量缺失 **SOURCE_DATA_MISSING**，资格 **SOURCE_OK**；能量输入承接03 D01 |
| R07 Range 当前温度/速度条件标签与分组 | `RS:234-249,342-348` → `RV:276-281,433-436` → S/PR:116-133 | 温度<10/10…25/>25 °C；速度<50/50…90/>90 km/h；当前speed按原lengthUnit规范化 | 当前S不持久 | 速度unknown不造；温度直接取raw outsideTemp却当C，F源会错分组 | T7 status/speed单位测试仅长度；**BUG AN-05**温度；速度修复 **SOURCE_OK** |
| R08 Range 模型sampleCount/distance/confidence/insufficient文案 | `RS:312-337` → RV → PR:85-110,147-152 | 合格模型confidence由样本/距离算50…95；不足门槛时也应报告实际候选覆盖 | R重算 | 当前未达门槛selected.second=null，sampleCount/distance均归0，存在样本仍走no_recent_data | T8未断言不足门槛候选计数；**BUG AN-06** |
| R09 Range 无容量时当前额定range后备 | `RS:298-306` → `RV:279,358,433-436` → S/CAR | raw status同响应unit→规范km，明确额定range后备，不伪装个性化预测 | VM，无health/units跨次借用 | 有合格模型但无容量，才显示此fallback；无status字段不造0 | T7 RangeStatusUnitsConsumerTest；**SOURCE_OK**；cloud容量unsupported属源缺失 |
| R10 Range Room-first/离线/异常/身份栅栏 | `RS:123-138,162-168` → `RV:196-318,321-348` → AH/R | history先发布；health/status各5秒可选增强，不能阻塞或擦除历史 | R、publishedHistoryIdentity/activeIdentity/generation | unreadable/nullcache、抛异常、error、刷新spinner、ABA/换车、取消皆有分支；身份错误必须清旧投影 | T7新增RangeRoomFirstRegressionTest当前18个@Test；**SOURCE_OK**源码，未重新打开旧Room-first问题；18个源码存在不等于本执行者跑过18项 |

### Vampire（9位置）

本页所有数值都受 V01 的身份根因影响；以下字段行仍分别核对其数学/nullable处理，不把同一scope问题重复计数。

| ID / route / metric | UI → VM → wire / DTO / domain / repository | 公式与单位 | Room / scoped cache | null / 0 / negative / coverage | 现有证据与判断 |
|---|---|---|---|---|---|
| V01 Vampire 选中车辆/加载/刷新/换身份 | `NAV:228,1020-1024` → `VS:41-46` → `VV:95-124` → settings.currentCarId → REP | route.carId未使用，VM另读全局ID；load并发未取消/无generation/scope publish guard | allWindows仅VM，换scope无订阅清除 | 旧direct Success在新账号后仍能发布；旧allWindows也不在load/error前清掉 | T9测试fallback/helper和字符串，不覆盖VM身份；**BUG AN-01(P1)** |
| V02 Vampire 7/30/365日/ALL/custom及资格 | `VS:128,365起selector` → `VV:149-184,198-218` → SW:55-74,110-120 | 以窗口开始日期过滤；≥2h、已知下降SOC才eligible；能量/功率另需coverage≥80% | direct无Room；fallback经UH/R | charge overlap本地SH:27排除；SOC不降/未知不编耗电；无样本不造0W/kWh | T9/T10；**SOURCE_OK**资格；date offset取记录本地日，跨日整窗归起始日属口径 |
| V03 Vampire 总SOC损失/合格小时/窗口数/稳定结论 | `VS:155-189,218` → `VV:171-194` → SW:85-97 | −ΣSOC delta，百分点；Σeligible小时；稳定≥5窗且≥20h | direct仅VM；fallbackR邻接SOC | 显示的总SOC是百分点和，可>100，不是当前剩余电量；selected数与eligible数分开 | T10；**SOURCE_OK**；不等于观测kWh；继承V01 |
| V04 Vampire 总能量 | `VS:224` → `VV:188` → SW:89,95 → AD.energy_kwh → HOME:547-587 | 合格有覆盖窗口energy求和，kWh；未知窗不补0 | direct无Room；本地SH:39明确null | UI:134提示部分energy缺失；已有0W且coverage>0的direct producer把真实0kWh留null | T9/T10不覆盖HOME真零；**BUG AN-08**；本地没有驻车功率则 **SOURCE_DATA_MISSING** |
| V05 Vampire 平均功率/每日平均 | `VS:212,421` → `VV:189,248` → SW:88,96 / HOME:589-593 | 当前是窗口平均功率的算术mean；W。单窗HOME=积分kWh×1000/已覆盖小时 | direct仅VM | 无合格功率保持null，真0W保留；跨不同窗时长不是总能量/总时长功率 | T10未验证不同持续时长；数值来源 **SOURCE_OK**，标题是否承诺时间加权均值 **UNVERIFIED**；不擅自把mean改成另一口径 |
| V06 Vampire 每日kWh柱/每日总SOC/周期数 | `VS:230-246,478-510` → `VV:241-253` → SW/AD | 按startDate日期分组；已知kWh求和，SOC百分点求和，periodCount；最多365个日桶 | allWindows内存/SH来源R | 全未知日能量null，图跳过；0kWh若传到UI保留；不是每午夜切分的自然日积分 | T9/T10；**SOURCE_OK**现有口径；原始0丢失承接AN-08 |
| V07 Vampire 最近10个窗口时间/时长/位置/SOC/W | `VS:272,278,406-451` → `VV:178,222-237` → AD/SW | start时间、duration秒/3600小时、loss百分点、W；位置原window地址 | direct仅VM；fallbackR邻接地址 | 缺位置unknown；missing power unknown；显示前eligible保证SOC已知 | T9/T10；nullable **SOURCE_OK**；HOME倒序、SH升序而UI直接take10，**BUG AN-09** |
| V08 Vampire 原因/置信度/建议 | `VS:296,449-451` → `VV:212-217,224,234-237` → AD climate counts | any climateActive>0即CLIMATE，confidence=1f否则0；UI同时固定detected_unknown文案 | direct counts内存；fallback无climate样本 | 有开空调观测不证明全部损失由空调造成，100%不是统计置信区间；HOME active_count实际是any-active布尔1（603-605），不能当完整活跃样本计数 | T9未验证原因消费者；**UNVERIFIED**因果/置信口径；保留未知，不用功率阈值猜哨兵 |
| V09 Vampire direct/fallback/冷重开持久性 | `VS:71-115` → `VV:127-146` → `API:55-58` `/api/matelink/v1/cars/{carId}/standby` → AD:120-136 / SH:14-49 | 非空direct优先；404/空direct用同源历史邻接窗；SH仅SOC边界、无energy/power/temps | direct结果仅allWindows，无写Room；fallback从UH/R重新推导 | 直接能量/覆盖/climate信息冷重开离线不能从R恢复；SH不造功率/能量 | T9真实fallback resolution+源码检查；fallback **SOURCE_OK**；remote receipt离线持久完整性 **UNVERIFIED**，不能宣称全页Room-first通过 |

### TemperatureTrend（8位置）

| ID / route / metric | UI → VM → wire / DTO / domain / repository | 公式与单位 | Room / scoped cache | null / 0 / negative / coverage | 现有证据与判断 |
| T01 TemperatureTrend 当前内/外温度 | `TS:145-165` → `TV:139-159` → S/CAR.climate_details | 当前status值；UI固定°C，VM丢弃S.units | 当前值只VM，不写历史 | 缺/null不画0；有限0和负温度保留；无逐字段verified sample time，明确当前快照非历史 | T11 currentApiSnapshotNeverBecomesHistoryOrHistoricalExtremum；**BUG AN-05**仅温标处理，当前snapshot分离 **SOURCE_OK** |
| T02 TemperatureTrend 历史内/外点与真实时间轴 | `TS:227,269-280,356-367` → `TV:65-107,137` → DD/DS → HM.decodeDrive → H原始receipt | 行程均温锚定drive_start Instant，按实际时间排序/绘点；非停车连续trace | R原始apiEvidence；不信旧scalar温度标签 | 同drive ID、起止Instant一致、正时长、end≤now、可信source/quality；finite0/负值可入；缺单侧不代填 | T11；**SOURCE_OK**观察资格；历史原响应温标未随DriveData保存，legacy F/C资格 **UNVERIFIED**，不假定当前C源证明所有源 |
| T03 TemperatureTrend 7/30日窗口 | `TS:101-111` → `TV:30-31,68-71,261-263` | now−7/30×24h 至now，Instant闭区间，不是7/30个自然日 | R，切窗重读 | future/超窗拒绝；缺时间不补当前时间 | T11；**SOURCE_OK** |
| T04 TemperatureTrend 历史最高内/外温度 | `TS:216-222` → `TV:175-178` → qualified historical points | 各自max，四舍五入1位；VM也算min，但当前UI没有显示min卡 | R重算，不用当前snapshot补极值 | 每侧空为null；负值max不被0顶替；只有一侧仍显示已有侧 | T11；**SOURCE_OK**；未把VM未调用的min当已显示参数 |
| T05 TemperatureTrend 合格样本/总行程/末次观测 | `TS:177-188` → `TV:149-162,180-182` → DD/DS | 分母=窗内非quarantined Room行程，分子=至少一侧合格温度points；last=末次drive_start | R，按localHistoryCarId | N/M是行程覆盖，不是逐秒覆盖或温度传感器样本数；无点时间隐藏 | T11；**SOURCE_OK**口径；自然采样覆盖 **UNVERIFIED** |
| T06 TemperatureTrend 空态/离线/身份/重试 | `TS:123-138,192-204` → `TV:130-165,197-215,242-267` | scope先后核验，token+取消抑制旧响应；Room历史与可选status分离 | R；网络status异常可保留history，但source先resolveRemote | current无值不会抹历史；history全空分no_history/no_qualified；换账号清所有可见值 | T11 scoped/controller测试；**SOURCE_OK**源码；冷启动resolveRemote是否可离线及时完成 **UNVERIFIED** |
| T07 TemperatureTrend solar gain数值 | `TS:228,439-462` → `TV:175-178` → 两个独立历史max | 当前max(inside)−max(outside)，°C；不是同一观测的max(inside−outside) | 两个极值来自R，可不同时间甚至无任何配对 | 各自非null且insideMax>outsideMax就报+Δ；没有配对门 | T11不覆盖insights；**BUG AN-07** |
| T08 TemperatureTrend 空调表现/forecast | `TS:228,469-489` → 无对应VM证据字段；TV:43-59只有温度/覆盖 | “空调表现良好”固定资源，无运行时计算；六页无天气预报API消费者 | 无forecast/AC性能持久收据 | 任意一个历史温度点即可进insights；无climate-on、目标设温、冷却速率/环境控制也下结论 | T11未覆盖固定insight；**BUG AN-07**同根因，不另计；天气预报能力 **SOURCE_DATA_MISSING** |

### TpmsTrend（8位置）

| ID / route / metric | UI → VM → wire / DTO / domain / repository | 公式与单位 | Room / scoped cache | null / 0 / negative / coverage | 现有证据与判断 |
| P01 TpmsTrend 左前压力/曲线 | `PS:166,202-295,343-354` → `PV:191-194` → TA:47-57 → TH:79-86 → TD:79-85 → TP | bar；原始collector_received_at毫秒为X，非手机poll时间 | TP主键(localHistoryCarId,observedAt)，只查verified collector | `TO:13-28` 要bar+telemetry_mqtt+collector时间；pressure有限0…15，真0有效、负拒绝；缺此轮不借其它轮时间 | T12 TO/analyzer/controller测试；**SOURCE_OK**，自然车辆样本 **UNVERIFIED** |
| P02 TpmsTrend 右前压力/曲线 | 同P01，wire `CAR:268,280` pressure_fr/observed_at_fr → TO:25 → TA:55 | bar、此轮独立时间 | 同P01，equal instant才合并 | 异步轮事件不补0、不强行同刻；legacy poll行不晋升verified | T12；**SOURCE_OK** |
| P03 TpmsTrend 左后压力/曲线 | 同P01，`CAR:269,281` → TO:26 → TA:56 | bar、此轮独立时间 | 同P01 | 同P02 | T12；**SOURCE_OK** |
| P04 TpmsTrend 右后压力/曲线 | 同P01，`CAR:270,282` → TO:27 → TA:57 | bar、此轮独立时间 | 同P01 | 同P02 | T12；**SOURCE_OK** |
| P05 TpmsTrend 每轮首末变化/coverage | `PS:339,343-354` → `PV:288-296` → TA:59-65 | 每轮最后−最初，bar；至少2个非null才有delta；coverage.sampleCount=receipt行数，非四轮完整次数 | TP/TD same window | 单点delta=null，不把缺失当0；coverageByWheel在VM存在但当前摘要只显示总receipt数；时间间距不均仍按真实X | T12；**SOURCE_OK**；不能用总行数宣称每轮都有相同覆盖 |
| P06 TpmsTrend 7/30日窗口/空态/retry/身份 | `PS:95-135,158-170` → `PV:71-88,106-123,256-284` → TH/TD | now−7/30×24h 至now（DAO上界now+1排他） | TH按确认负local ID读取TP/DS，同scope前后verify | generation/token/cancel；空/未验证压力不产生曲线；scope变化清旧结果；last retry固定原request.now | T12 identity recovery真实controller合成测试；**SOURCE_OK**资格；无新HTTP采集，打开页不保证自然新增样本 |
| P07 TpmsTrend ambient/highway/parking可能因素与冷态检查 | `PS:366-420` → `PV:213-221` → TA:84-120 | ambient需两条同刻四轮完整且有温度；highway四轮升压+≤6h内≥90km/h drive；parking相邻完整receipt≥24h且单轮相对其余下跌≥0.2bar | TP+DS；`TO:21-27`当前verified writer不带outsideTempC；novel helper TH:138还置null | 证据不足明确INSUFFICIENT_EVIDENCE；分轮不同时间不合成完整行；missing环境配对合法不足；规则仅可能因素非诊断 | T12 analyzer人工complete fixtures存在；pressure曲线 **SOURCE_OK**；ambient自然联证 **SOURCE_DATA_MISSING**；parking仅长间隔并未证明期间真的停车、highway使用DS.speedMax而非raw单位证明，因果资格 **UNVERIFIED** |
| P08 TpmsTrend 来源/警告/自定义提醒边界 | `PS:170,419-420` → PV；采集 `DashboardViewModel.kt:65-98` / `TW:278-326` → TO/TH | warning true/false有独立collector收据，不从压力推Tesla警告；本页无target/low/high编辑控件 | pressure已验证收据TP；rule/profile有独立store | SYS-04旧profile正remote key/负local key错配不证明曲线错误；新 `settings/TpmsChangeSettingsCard.kt:22-30,42`按local ID保存 | T12与父08 specimen；**SOURCE_OK**新rule键；**BUG(shared 07 SYS-04)**旧profile，仅此处交叉引用，不新增计数 |

## 新增发现与最小非布局修复

### AN-01 — P1：Vampire 丢弃路由车辆身份，迟到响应缺scope栅栏

位置：`NAV:228,1020-1024` 已携带carId却不传入 `VS:41-46`；`VV:95-124` 每次launch读全局currentCarId，直接响应后赋allWindows/UiState；没有VehicleContextRepository、scopeChanges、请求generation或取消旧job。

触发一：从车辆A的More路由进入Vampire，但全局选择已变B，页面读取B数据而route仍A。触发二：A的direct standby请求在途，账号/origin/车辆切换，旧请求随后Success；`VV:129`非空direct立即返回，绕过UH的verified context；VM没有发布前身份检查，旧窗口继续显示。A/B请求倒序返回也可覆盖较新结果。源码路径确定，未运行生产/设备并发复现。

最小修法：把route.carId传到Screen/VM；捕获同页VehicleContext/HistoryReadScope并检查response发布时仍current；切scope取消/代次递增且清allWindows及UiState；fallback携带UH verifiedReadContext，窗口切换同样核验。沿用现有Range/Efficiency模式，不改布局，不取消identity隔离换离线体验。验证门：route与globalID不同、同numeric ID换account/origin、A→B→A、晚Success/晚Error、加载后切窗均不能显示旧身份数据。

### AN-02 — P2：缺可用SOC被主要SOC代填

位置：`BV:379`、`BS:1078,1164`。例如batteryLevel=60、usableBatteryLevel=null，当前主要SOC确有观察，`hasBatteryStatus=true`，页面同时显示“可用60%”；后者没有源字段。主要SOC真实0仍应可见，但也不能据此断言usable0。

最小修法：给BatteryStats保留nullable usableBatteryLevelObserved；仅该字段存在且有效才显示可用值，缺失用已有不可用表示。补主要SOC存在/usable缺、usable真0、主要SOC缺但usable存在的独立资格测试。`BatteryStatsEvidenceTest` 当前只测主要SOC/range存在性，不覆盖此错值。

### AN-03 — P2：趋势SOC与额定range可跨首尾端拼对

位置：`BV:446-452`。`(startBatteryLevel ?: endBatteryLevel)` 与 `(startRatedRangeKm ?: endRatedRangeKm)` 独立选择。startSOC=80、startRange缺、endSOC=70、endRange=350km时，当前生成350/0.8=437.5km；实际可配对end样本为350/0.7=500km。温度/样本数量门不识别错配，后续baseline和衰减会受影响。

最小修法：先找完整start对，否则完整end对，并用该端日期；都不完整则不产样本。补首尾交错缺失、完整start优先、完整end、双方缺、一端真0被资格拒绝的实际VM消费者测试。BT纯函数测试不能覆盖VM错误组装。

### AN-04 — P2：规范km的电池趋势只被附上mi标签

位置：`BV:451` 经 `DR:60-61` 已规范km；`BS:451,458` 却调用 `UF.formatDistance`。`UF:60-65`只附单位：500km趋势遇units.mi会显示500mi，正确为约310.7mi。与在线raw API range走raw formatter是两种数据边界。缓存原始单位缺失另属01 H02，不重复计数。

最小修法：仅对BatteryTrend规范化km值用现有 `formatDistanceKm`；raw API range保留同响应单位。补canonical500km+mi、km、null units对照以及趋势chart轴/数值一致性；不要再次转换raw status。

### AN-05 — P2：当前温度消费者丢掉原响应温标

位置：`REP:458-460`保留units；`TV:139-148`只取status并赋currentInside/Outside；`TS:159,165`固定°C。`RV:280`亦把raw status.outsideTemp当PR的currentTemperatureC。

触发：legacy preferred response外温68°F。TemperatureTrend显示68°C；Range把应为20°C的MILD归HOT。当前cloud C源不触发该例，不能因此消除兼容路径缺陷。历史温标receipt在DR没有保留，另按UNVERIFIED列出，不能借当前status猜旧历史温标。

最小修法：保留同响应units，F明确换算 `(F−32)×5/9` 至canonicalC再交C消费者；C保留，未知unit保持未可用或明确其数值/温标，不猜C。历史温标需随原receipt绑定后才能统一分组。补68F→20C、0C、负温、缺/未知unit与raw receipt未改写的消费者测试；现有RangeStatusUnits测试只证长度单位。

### AN-06 — P2：模型未达门槛时把真实候选覆盖清成0

位置：`PR:85-110` 选不中group/global时efficiency=null，同时sampleCount=0/distanceKm=0；`RS:314-315`据sampleCount==0显示“没有近期数据”。例如近90日9条有效300km行程，不满足全局10次，正确是覆盖不足，当前却称无近期数据，`range_personalized_insufficient`分支对此情况永远不可达。

最小修法：在source=UNAVAILABLE时仍返回实际候选的count/distance与不足门槛原因，range/efficiency/confidence继续受原门槛控制。不得放宽门槛发预测。补0条、9条/300km、10条/299km、group不足但global可用等消费者文案/覆盖断言。

### AN-07 — P2：温度insights由独立极值和固定文案产生未观测结论

位置：`TS:228,439-442,456-462,469-489`。两个极值各自来自 `TV:175-176`，可在不同drive，甚至没有一条同时有inside/outside的点，却相减并标solar gain。单独inside=50、另一单独outside=20即可显示+30°C，但没有配对温差。随后空调表现良好文案无条件输出，VM没有climate-on、设温、时间响应、能量或故障证据。

最小修法：温差仅对同一合格TemperaturePoint双侧配对计算，并明确它是行程均温差，不能据此归因日照；没有配对保持未知。空调性能改用已有证据不足文案，直到有明确性能合同；不改布局、不用预测/天气替代车辆历史。补不同时点双极值、仅单侧、负温、paired0差值及无空调证据时不出现“良好”断言。

### AN-08 — P2：自托管standby producer把已观察的0kWh丢成未知

位置：`HOME:573-590`。相邻有效power样本可使coverage>0，而所有power明确为0时energy=0；`if energy > 0`不赋EnergyKWh，但下一段会正确赋averageW=0。只要SOC下降窗口达到客户端条件及coverage≥80%，Vampire收到0W却energy=null，显示unknown/能量缺失提示而不是保留源实测积分0。SOC变化与零功率不必一致，不应借SOC强制把0改成正能量。

最小修法：按有效积分覆盖/有效配对数，而非energy>0决定是否赋可空energy；有覆盖真0→0，无覆盖→null；维持现有coverage门，不造未覆盖段能量。补zero功率密集样本、有SOC下降、100%coverage，及无power/大缺口对照。该项仅源码级producer条件证明，本次未运行Go/HTTP链。

### AN-09 — P2：本地fallback的“近期待机时段”取到最早10条

位置：`SH:18-24` 把行程按end/start时间升序排序并依次构造停车窗；`VV:178` 保持selected原顺序；`VS:272,278` 标题“近期待机时段”（`values-zh/strings.xml:483`），直接 `idlePeriods.take(10)`。HOME direct在`HOME:482`按older.end_date倒序，本地fallback却正序；同一窗口至少11条合格停车段时，本地路径显示最早10段、漏掉最新段，改变数据源还会倒转显示顺序。

最小修法：在形成UI idlePeriods时按解析后的窗口start/end Instant倒序，再由现有UI取10；不要用未规范化offset文本字典序排时间。daily列表已有单独日期倒序，不受此改动。补11段输入、乱序direct输入、不同offset同Instant的最近10段消费者断言，确认总量仍使用所有合格窗口。当前测试只测fallback构造/资格，没有最近列表消费者覆盖；此发现是源码级确定顺序，未运行UI。

## 测试证据分层

下表所有路径前缀为 `android/app/src/test/java/com/matelink/`。本次只读取源码，**没有运行Gradle、Go测试、UI或设备**；没有把存在@Test当通过。父审查08的11项实际执行（8FAIL、3control PASS）为本组导入证据，其中SYS-04与本组有关；它不是本组55位置的运行结果，也不证明新增AN-01…09已被运行复现。

| 编号 | 实际现存文件/断言位置 | 能证明/不能证明 |
|---|---|---|
| T1 | `ui/screens/battery/BatteryHealthAvailabilityTest.kt:12-42`；`BatteryRangeMetricsTest.kt:10-122` | 真实helper检查404/collecting/容量字段与range null/0/negative/nonfinite；不证真实容量源/出厂基线 |
| T2 | `ui/screens/battery/BatteryStatsEvidenceTest.kt:9-31`；`BatteryReadinessPresentationTest.kt:9-21` | observed主要SOC真0/range独立资格、高SOC提示；未覆盖usable缺失与VM端点配对 |
| T3 | `ui/screens/battery/BatteryScopedPublicationTest.kt:23`；`TaskBQualityContractTest.kt:29-121,166-190` | scoped loader合成延迟响应，另有读取源码contains的合同检查；不是挂载真实BatteryViewModel/HTTP/设备全链 |
| T4 | `domain/analytics/BatteryTrendTest.kt:11,41,61,79,97,115` | BT纯算法首/近期分离、10样本/30日、边界/finite、按日中位；不证BV组装同端，也不证mi UI |
| T5 | `ui/screens/efficiency/EfficiencyMileageIdentityConsumerTest.kt:92-233`；`domain/analytics/WeightedEfficiencyTest.kt:9-23`；`NetWeightedEfficiencyTest.kt:7-23` | 真实Efficiency VM+合成source验证Room先发、ABA/账号/origin/错误/缓存隔离；加权同子集0/负净能量；不是实车HTTP或自然历史完整性 |
| T6 | `ui/screens/efficiency/EfficiencySpeedUnitsConsumerTest.kt:23-58`；`EfficiencyBarScaleTest.kt:8-33`；`analytics/PercentileTest.kt` | 实际速度分组/月趋势/零线函数；测试imperial、缺unit、0/负能耗；PCT纯规则；未挂载图表Canvas |
| T7 | `ui/screens/range/RangeStatusUnitsConsumerTest.kt:30-85`；`RangeSpeedUnitsConsumerTest.kt:28-72`；`RangeParkedRequestIsolationTest.kt:264-344`；`RangeRoomFirstRegressionTest.kt:129-428`；`domain/analytics/RangeDeviationTest.kt` | 当前18个RangeRoomFirst @Test的源码已存在：cache先发、保留、冷错误、identity errors/ABA、optional增强timeout/异常、取消；这是修复后的源码，不重新登记旧Room-first故障；未由本执行者运行18项 |
| T8 | `domain/analytics/PersonalizedRangeTest.kt:12,46,72,98,130` | 温度/速度cohort、global fallback、缺容量/样本门、90日窗口；未检查未达门槛实际候选计数或F current consumer |
| T9 | `ui/screens/vampire/StandbyFallbackResolutionTest.kt:13,31,46`；`StandbyFallbackContractTest.kt:9,20,29`；`data/repository/StandbyResponseContractTest.kt` | resolution helper真实值断言；FallbackContract为源码字符串检查，不能替route carId/scope/concurrency VM测试；未覆盖HOME zero条件 |
| T10 | `domain/analytics/StandbyWindowAnalysisTest.kt:12,30,49`；`StandbyHistoryDerivationTest.kt:13,32` | 充电排除、SOC保留/低coverage拒能量、5窗20h、本地邻接推导不造power；未测真实direct采样/完整性、daily最近排序或因果置信度 |
| T11 | `ui/screens/temperature/TemperatureTrendTruthTest.kt:26-177` | 实际receipt mapper/observation/controller：负温/0、raw evidence/quality、时间窗、当前不变历史极值、离线、scope/ABA；不证insights或温标F/UI挂载 |
| T12 | `data/sync/TpmsObservationEvidenceTest.kt:26-101`；`domain/analytics/TpmsTrendAnalyzerTest.kt:16-168`；`ui/screens/tpms/TpmsTrendPresentationTest.kt:24-182`；`TpmsTrendIdentityRecoveryTest.kt:21-101`；`analytics/AnalysisHistoryRepositoryTest.kt:24-135` | 真实DTO/collector、每轮独立time、0/null、同刻merge、verified资格、分析规则、token与scope controller；人工四轮同刻带温度fixture不证明当前writer真的能自然产生ambient联证 |

### 当前源能做什么、还缺什么

- `TC:33-65` 当前字段表有Soc、RatedRange、EstBatteryRange、温度、VehicleSpeed/Location、四轮TPMS及独立warning；**没有EnergyRemaining**。采集配置“请求了温度/压力”只证明配置源码，不证明车端曾上报、保留了足够自然历史或同刻四轮/温度联证。
- `CLOUD:775-782` battery-health明确unsupported/null；缺current/max capacity、终身能量、cycle/剩余能量不能由客户端补成已观察数据。Range个性化可有真实效率/候选覆盖但没有容量续航数字，这类空态是源限制。
- Battery趋势是SOC规范化额定range趋势，受温度与coverage门控，不能宣称测得容量SOH。10次/30日只是算法门槛，置信度是启发式评分，不是provider质量认证。
- TemperatureTrend只消费保存行程的均温receipt，没有连续停车温度采样链，也没有天气预报。当前快照、历史均温、预报必须保持来源/时间语义分离。
- TPMS当前verified writer不绑定outsideTempC；异步单轮collector时间也不会被强拼完整四轮。因此可能因素长期insufficient不等于曲线消费bug。不能用poll时间补观察时间，也不能把旧未验证Room行晋升。
- Vampire本地邻接行程只能观察SOC边界和时长；缺驻车电量/功率/温度必须保持未知。direct standby的能量、coverage、climate只在VM，当前未证明断网冷重开能恢复这些字段。

## Review / 验证门

本页覆盖全部六个注册route，明确未显示的lifetime/cycles/温度min不冒充UI能力。9个新增发现都有当前源码触发位置、具体反例和最小非布局修法；未修改代码，没有宣称修复或运行通过。后续先验证AN-01身份边界，再按AN-02…09补实际消费者/producer回归；继续沿用已存在Range Room-first和Mileage保护，不能用旧handoff重新否定当前修复。

完成产品验收仍需授权环境中的自然采样、provider合同和设备重开证据。新增9项的当前置信层级均为**源码级**；当前8FAIL/3control PASS仅引用父08已运行的11项审查，不作为这里新增发现的运行结果。

本文件完成前实际执行的只读校验：矩阵55行、55个唯一位置ID（B11/E9/R10/V9/T8/P8）；新增发现标题9个（P1=1、P2=8）；六页Screen/VM及BT/PR/SH/HOME/TC共17个关键源码文件的SHA256均与父任务隔离快照`isolated-source.json`一致（0 mismatch、0 missing key）。这是文档/快照一致性校验，不是运行产品测试。
