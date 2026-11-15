# F4 分析数据修订（2026-10-27）

本组在 `E:\project\_worktrees\matelink-release-data-audit-20261026` 工作。发现基准为 build72 审计；目标为 build73 修复候选，不宣称已交付/设备/生产通过。F4 不运行 Gradle/Go，不改 NavGraph、版本或共享账本，不提交；父任务统一测试与提交。

## 当前阶段

- [x] 实际启动回报，读取本地 AGENTS、04-analysis、FIX-PLAN、10-statistics-review 与相关 lessons。
- [x] 准备首批真实调用路径回归：模型候选覆盖、待机窗口排序、Battery VM 已有投影、真实 postgresStore.Standby 的 0/null 积分路径。
- [x] 父执行 `groups-red-03`，本组首批7例/5失败/0error/0skip；F4读取真实JUnit XML核对，见下表。
- [x] F1移交BatteryViewModel后完成本组最小数据修复；F1缓存函数保持原样。
- [x] 父runner06及09复跑确认本组GREEN；执行证据与边界见下。

## 计划改法与边界

| 发现 | 最小数据修法 | 保留的未知/边界 |
|---|---|---|
| AN-01 Vampire身份/迟到发布 | route.carId传Screen/VM；统一已验证history proof；scope变化清投影，generation/currentness阻止晚结果 | direct网络/历史fallback都须同proof；不借全局currentCarId |
| AN-02 usable SOC | nullable usable保持独立，不回填主要SOC；当前UI原值绑定显示未知 | 显式0是已观察0 |
| AN-03 trend SOC/range | 从同一个start或end端点选合格pair | 不把startSOC与endRange拼对 |
| AN-04 canonical km | 仅趋势规范km值使用formatDistanceKm | raw status preferred单位不二次转换 |
| AN-05 温标 | 保留当前status同响应unit；明确F转C，C原值 | unknown温标保持unknown，不能借当前单位证明历史 |
| AN-06 模型coverage | 已采用model的sampleCount/distance仍为0；另外保留候选count/distance并供不足说明 | 不放松样本门，不生成efficiency/range/confidence假值 |
| AN-07 温差与空调结论 | 只用同个历史点inside/outside pair算均温差；无性能证据不给“良好” | 历史行程均温差不证明日照归因或空调性能 |
| AN-08 standby真实0 | 有有效power配对coverage才发布energy（包括0） | missing/gap无覆盖保持null；不补未覆盖能量 |
| AN-09 近期窗口 | 原始/local fallback及最终UI消费前按真实start Instant降序 | 最近10不是输入列表头部默认升序 |

## 测试交接（未运行）

- Android `com.matelink.domain.analytics.F4AnalysisDataRegressionTest`：真实estimatePersonalizedRange保留已采用model0语义，并要求独立候选3次/60km；真实buildLocalStandbyWindows的recent10排序。
- Android `com.matelink.ui.screens.battery.BatteryActualProjectionRegressionTest`：调用真实VM所使用的 `computeBatteryStats(state)` / `DriveData.toBatteryTrendSample()` 纯projection；missing usable、explicit usable0、同end配对和disjoint拒绝。最终正常测试不含Unsafe/反射/DI副作用；同义抽取时仍保留旧错误行为等待RED。
- Android `com.matelink.ui.screens.range.RangeTemperatureConsumerRegressionTest`：Moshi status→当前rangeStatusInputs，68°F→20°C、C真0/负温、缺失/未知温标控制。
- Android `com.matelink.ui.screens.temperature.TemperaturePairEvidenceRegressionTest`：真实TemperatureTrendRefreshController；独立极值不产生配对差值、同观测正/0/负温差保留。
- Go `./cmd/adapter -run TestF4StandbyObservedZeroIsKnownOnlyWithCoveredPowerIntervals`（目录 `deploy/teslamate-home-docker/adapter`）：database/sql合成driver让真实postgresStore.Standby执行SQL行消费与功率积分；full coverage真0、missing power、>900s gap、NaN/Infinity控制。非finite不得因为nullable字段Valid就被算成完整coverage。

## 实际证据（父runner执行，本组只核对结果）

2026-10-27，父任务报告联合 `groups-red-03` 为49例/36失败/0error/0skip；其中本组对应的 `android/app/build/test-results/testDebugUnitTest/TEST-*.xml` 已由F4读取核实：

| 类 | tests/fail/errors/skip | RED原因 |
|---|---|---|
| F4AnalysisDataRegressionTest | 2/2/0/0 | 候选3次收到null；最新待机起点应10-12却为10-01 |
| RangeTemperatureConsumerRegressionTest | 3/2/0/0 | F68期望C20收到未提供；C真0同样未提供；未知单位控制通过 |
| TemperaturePairEvidenceRegressionTest | 2/1/0/0 | 同观测期望5°C差值未提供；独立极值不产生paired字段控制通过 |

最初BatteryActualProjectionRegressionTest使用sun.misc.Unsafe，因Android编译类路径无法解析导致compile error，**不算产品RED**。父已将错误版本移到pending-tests；F1移交后F4把实际VM原义纯projection抽出，正常测试已无Unsafe/反射，不降低断言。

父随后执行 `groups-red-green-05`（XML备份 `groups-red-green-05-xml`）：实际77例/17失败/0error/0skip。F4读取两份真实JUnit XML确认：BatteryActualProjectionRegressionTest为4例/3失败（usable缺失却为80、endSOC被start80覆盖、disjoint pair仍有样本），explicit usable0控制通过；VampireIdentityConsumerTest为4例/4失败（route7请求99、旧car1覆盖新car2、scope未清除、失败后filter复活旧值）。该RED是在**只作同义纯projection/PageSource接缝、仍保留旧行为**时运行，属于实际消费者缺陷复现。

同轮父报告并由测试XML提供：TemperatureRoomHistoryConsumerTest 4/0、RangeTemperatureConsumerRegressionTest 3/0、TemperaturePairEvidenceRegressionTest 3/0、F4AnalysisDataRegressionTest 3/0、BatteryTrendFormatterRegressionTest 1/0。这里fail=0仅指该轮这些已修子项；不是后续Battery/Vampire GREEN结果。

Go父runner随后找到现有Go1.24 toolchain：`f4-standby-go-red-02.log` 实际5子例/3失败（zero、NaN、Inf），missing/gap控制通过。之前本机默认Go1.22导致module version错误，仅为环境失败，**不算RED**。F4已按有效finite配对覆盖发布能量（含真实0），非finite积分不计coverage；未修改parked producer。父随后执行 `f4-standby-go-green-01.log`，5子例全部GREEN（2026-10-27父消息导入，执行者为父，F4未运行Go）。

GREEN首批变更：模型新增独立candidate字段但已采用model coverage保留原0语义；Range不足说明读取candidate，已采用model details仍不混入候选；当前温度读取本响应温标转canonicalC；Temperature state以同点inside/outside计算paired差值，原Text只替换取值/证据说明；Battery趋势两处规范km数字用formatDistanceKm；本地standby输出按真实start倒序。没有改颜色、字体、字号、尺寸、布局或参数顺序。

父补DriveData/DriveDetail的同响应temperatureUnit metadata与outsideTempC/insideTempC后，F4历史Range与Temperature消费者只读C getter。TemperatureRoomHistoryConsumerTest追加F→C的真实Room写入/重开和explicit空单位拒绝两例。BatteryTrendFormatterRegressionTest调用实际Screen纯formatter验证500km→310.7mi、km和真0；AN-04已有同轮GREEN控制，未谎称单独RED。

## 最终数据改法与冻结交接

- Battery：实际VM调用 `computeBatteryStats(state)`；usable SOC为nullable且校验0..100，绝不借main SOC。主SOC的observed资格独立保留。历史trend使用 `qualifiedBatteryTrendSample` 在同start/end边界选择SOC/range/日期，并使用receipt的outsideTempC；没有同端合格pair就不造样本。原usable Text格仅绑定已知值或父新增 `usable_percent_unavailable`，两处趋势数字改规范km formatter。
- Vampire：实际VM使用最小PageSource和已有LatestHistoryLoad；route carId是唯一车辆入参，删除全局selectedCar依赖。direct请求前resolve已验证history proof；每次await后、最终非挂起发布前检查generation/current scope。history fallback还必须匹配同context/scope。scope变化撤销窗口，失败/重载先清allWindows，筛选无法复活旧车/旧账号内容。最终idlePeriods再按真实Instant降序，直接/本地源都遵守recent10口径。没有新建通用controller框架。
- Temperature：current同响应温标规范化；historical同receipt C getter；同一合格point的inside−outside产生 `maxPairedDifferenceC`（保留正/0/负值），不能由两个独立极值合成。原Text格改为同程均温差说明，空调表现没有证据时为coverage insufficient，未作日照/性能结论。
- Range：规范当前温度后分组，历史分组只用outsideTempC；candidate count/distance与已采用模型coverage分开，样本门和预测数值门不放松。Screen只改不足说明的取值绑定，没有显示假模型置信度。
- standby：有效finite双端采样积分和coverage门保留观察0；null/断档/NaN/Inf不生成0。Go5个子例已由父GREEN。

最终正常测试增加两项Battery边界（同end日期/°F、unknown温标，越界usable）与两项Vampire边界（同car账号ABA、foreign fallback context），需父最终GREEN；这些新增控制尚未单独运行，不冒称已通过。

后续实际结果：父runner06报告BatteryActualProjectionRegressionTest 6/0、VampireIdentityConsumerTest 6/0，全部0error/0skip；首批其余本组类与Go5子例仍GREEN。父runner09再次明确F4全部GREEN。以上为2026-10-27父runner真实执行报告导入，F4自身没有运行Gradle/Go。前段“待最终GREEN”只描述当时阶段，现由此结果覆盖；实车/真实provider/安装/像素/生产仍未验。

产品写入共12文件：VampireViewModel.kt、VampireScreen.kt、BatteryViewModel.kt（compute/trend部分）、BatteryScreen.kt（纯数值绑定）、BatteryTrend.kt、PersonalizedRange.kt、StandbyHistoryDerivation.kt、TemperatureTrendViewModel.kt、TemperatureTrendScreen.kt（原Text取值）、RangeViewModel.kt、RangeScreen.kt（不足说明取值）、`deploy/teslamate-home-docker/adapter/cmd/adapter/main.go`（仅standby段）。NavGraph、温标DTO metadata、资源、版本、共享账本与提交由父持有。本组不触碰真实账号、数据、设备、网络、部署或Git。

合成行为回归不是设备/provider/生产采样证据。本组没有运行Gradle、Go或接触用户数据。
