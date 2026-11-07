# F3 行程、曲线与驻车数据修订（2026-10-27）

目录：`E:\project\_worktrees\matelink-release-data-audit-20261026`。build72为审计发现版本，目标build73修复候选。只改数据资格、计算、来源/缓存/时间绑定；不改布局、字体、颜色、字号、参数顺序或strings。父任务持有版本、NavGraph、唯一Gradle/Go runner、共享账本与提交。本组不运行构建/设备/网络，不执行Git操作。F6持有AmapMapView/RouteView，F5持有AggregateDao，不交叉写入。

**终态（父runner20后）：本组14类、40例、0失败/0error/0skip。F3逐份读取最新JUnit XML核对；源码/测试已冻结，等待全应用QA，只因真实失败才重开。** 这是合成源码级回归，不是设备/生产/provider验收。中段阶段性“待”状态由最终实现与验证节覆盖。

## 进度与原始范围

- [x] 阅读03-driving-parked冻结补充和FIX-PLAN F3。
- [x] 新增首批真实消费者回归，父runner实际RED后开始GREEN。
- [x] 完成全部D01-D10/D12-D17本组源码改法；D01/D05继承父已修，D11不恢复。
- [x] 父targeted GREEN，F3逐份核对40例XML并冻结Owner文件。
- [ ] 全应用QA由Root统一执行；不把本组targeted pass外推为全应用通过。

D01固定145兜底删除与D05只去相邻GPS重复由父已实施，baseline11已有GREEN，属于**继承证据**，不认作本组独立执行。D11当前驻车地图由外部actor移除，本组不恢复/不认领为修复。

| 根因 | 当前改法/状态 |
|---|---|
| D02 nullable驻车SOC | 已从同作用域两端toAnalysisDriveData nullable receipt取值，真实0保留；不读primitive占位SOC |
| D03 图表时间/缺值/降采样 | Shared新增TimedChartSeries与xFractions/sourceIndices/gaps；prepare和选点保留真实X与原索引，line/fill逐段断开；页面接入仍待完成。invalid timestamp夹点漏gap已加真实反例，下一RED后补 |
| D04 mph二次转换 | 待DriveDetail/Curves共用raw preferred速度投影，未知unit不画速度 |
| D06 远端parked资格 | 已先拒绝同numeric pair但不同已知窗口；同scope/source/window完整合同与持久化待继续 |
| D07 完整邻接 | 待读含quarantined chronology，展示仍隔离 |
| D08 地址 | 详情两侧独立unknown绑定，Curves仍待接同一pure projection；不以对侧地址填充 |
| D09 报告极值 | 已优先同detail合格报告speedMax/powerMax/powerMin，样本只作无报告fallback |
| D10 距离曲线 | 待真实dt积分；没有时间/速度证据不画线性补点 |
| D12 Room-first详情 | 待本地receipt先发布、有限远端刷新与失败保同scope缓存 |
| D13 数值资格 | Shared轴拒NaN/Inf，数值缺失断线；标题负duration门已补，信号级SOC/功率/速度有限范围待页面投影统一 |
| D14 样本均值 | 实际DriveDetailSpeedAverageLabel保留observed_route_samples文案；DriveData新增speedMetricsSource，asCachedDetail传递它及temperatureUnit；其它reconstruction仍检查中 |
| D15 边界SOC平局 | 最近最短距离候选SOC冲突为unknown，不任意拿第一条；相同候选值可保留 |
| D16 天气缺码 | 已准备真实OpenMeteo接口fixture→WeatherRepository回归，未改旧默认0行为，待RED |
| D17 驻车持久化 | 待以v23 history_detail_receipts新kind/header复用，同context/pair/source/exactwindow证明后bounded/digest版本追加，不增schema、不覆盖旧receipt |

本表描述早期阶段，以下“最终实现”覆盖待办状态；最终QA仍由父runner确认。

## 实际测试证据（父runner，本组只核对/导入）

父runner05：`AppReleaseDrivingRegressionTest` 4例/4FAIL，分别为report100被sample40覆盖、非exact等距SOC冲突、null海拔桥接、reported_sample mean文案丢失。该4项有实际RED，本组随后做最小实现。

父runner06为86例/31fail/0error/0skip，XML备份 `groups-red-green-06-xml`。F3子集：

| 类 | tests/fail/errors/skip | 结果用途 |
|---|---|---|
| F3ChartPreparationRegressionTest | 3/3/0/0 | NaN轴、Inf双轴、真实prepare降采样丢原索引，均实际RED |
| F3ParkedReceiptRegressionTest | 3/2/0/0 | nullable receipt被primitive0补、异window payload覆盖实际RED；明确receipt0控制通过 |
| AppReleaseDrivingRegressionTest | 4/4/0/0 | 上述4项旧错行为再次确认 |

新等待RED：`TimedChartEvidenceRegressionTest` 3例（null timestamp夹点必须gap、全有效乱序排序不误断、missing/conflict/长gap/真0负值控制）；`WeatherConditionEvidenceRegressionTest` 2例（真实接口fixture缺码不造晴天、真code0保晴且unknown不造多云）。未运行的新控制不记pass。

后续父runner07/08仅编译未成功到JUnit，不计测试结果；其中prepareChartData新增可选参使旧trailing lambda不再绑定第三参，父只把测试两处写成显式convertValue，断言不变。F3补回原3参overload兼容旧调用，6参时间元数据版本参数显式提供。

父runner09实际99例/9失败/0error/0skip，XML备份 `groups-red-green-09-xml`：F3既有Driving4、Parked3、ChartPrep3全部GREEN；新Timed3/1FAIL（invalid timestamp gap），Weather2/2FAIL实际RED。其后F3修复invalid timestamp在原序列两点之间必须断线、有效乱序本身不误断；天气code改nullable/UNKNOWN且真0仍CLEAR，原天气icon/label位置只绑定unknown值。等待下一GREEN。

父runner10已启动，F3新增待RED：DriveCurvePhysicalEvidenceTest 3例（其中距离比例算法以同义helper保留，前2预计失败）；DriveDetailRoomFirstNetworkTest 1例（真实VM+Room重开+HTTP interceptor挂起身份发现，先显缓存）；DriveRoomFullSequenceRegressionTest 1例（真实DrivesVM的Room首读保留隔离中间行以阻断邻接）；ParkedReceiptPublicationRegressionTest 1例（真实VM新增read/save端口仅同义no-op，验证未调用保存/重放）。此时后三项生产旧行为未改，尚未运行成功不记RED或GREEN。

后续实际执行（均父runner，本组不运行Gradle）：

- 10停于编译集成：Root重复chargerPower及WeatherCondition.UNKNOWN其它consumer分支；不计JUnit结果。
- 11实际114例/14失败/0error/0skip：CurvePhysical3/2FAIL、RoomFullSequence1/1FAIL、ParkedReceiptPublication1/1FAIL，均是真实消费者RED；旧Driving4/Chart/Parked及新增Timed/Weather条件全部GREEN。RoomFirstNetwork当时方法返回Boolean导致InvalidTestClassError，**不算产品RED**；已只改成runBlocking<Unit>后单跑。
- 12实际RoomFirstNetwork 1/1FAIL/0error/0skip：网络仍挂起时期望cold Room记录64却为null，真实RED。
- 14实际130例/13失败/0error/0skip：RoomFirst、CurvePhysical、RouteSegments、Weather/Chart等GREEN；Sequence和尚未接的Receipt仍FAIL。Sequence继续修复当前scope首发会清除Room缓存的问题，并令测试先等待真实Room publication（units旁路也会触发/cars，不能把进入该请求当Room已经发布）。
- 15仅产品编译成功，测试编译停于Root Cost字段拼写修正，不计JUnit。
- 16实际159例/14失败/0error/0skip：F3 Store4、RoomFirst、Sequence、Receipt、Route、Curve、Chart、旧Driving全部GREEN；TripWeatherObservedTime3/3真实RED（00:10被插成01:00、无观察时刻补造、错drive/window仍取天气）。
- 18停于Root tariff类型编译修正，不计JUnit；19实际178例/17失败/0error/0skip：WeatherTimezone4/3FAIL（Shanghai跨UTC日/hour、请求未显式GMT/C；UTC控制通过），ParkedWireCoverage2/1FAIL（缺键被当0，显式0/非法范围控制通过），Driving6中新增2FAIL（缺时刻/窗口外统计，海拔失序/长gap）。其它F3分组保持GREEN。

19后本组已将最后RED改为下列实现，等待最终统一GREEN，不把未运行的最终源码标为pass。

## 最终实现与数据资格

- **D02/D06/D17**：nullable SOC只来自匹配的两端raw/presentation receipt。远端body必须匹配当前context/origin、请求pair、精确Instant窗口与明确source映射；`car_id/source_instance_id`存在时冲突必拒，缺实例是unverified。Actor只在已验证本地邻接proof存在时写入；服务器session不与本地另一source列表混合。
- `ParkedHistoryReceiptStore`使用既有v23表的kind=parked/headerJson，字段与全局schema不变。key含稳定namespace、两端ID/source/instance/window和两份本地原始receipt摘要；最多100 sessions/256KiB header，受共同512MiB默认总预算。typed header采用UTF-8长度+SHA-256 framing校验，追加版本不覆盖/驱逐旧证据，缺缓存/错误source/修改本地receipt均不能借旧body。损坏最新header尝试最多5个旧complete候选；写失败保旧，source/generation回调取消则事务不产生新header。
- body没提供实例时receipt.sourceInstanceVerified=false，providerTotalVerified始终false（收到完整HTTP DTO不证明完整观测总数）。已知同源映射仅SELF_HOSTED的teslamate_archive↔database_latest、CLOUD的telemetry_mqtt↔fleet_telemetry；其它仅精确source。无法匹配的source不发布/不持久。Raw typed header保留缺值，没伪造实例/车辆/总数。
- `ParkedDetailData`的sampleCount/coverageSeconds/coverageRatio改nullable，缺键保持unknown；本地边界fallback显式计算0仍传0。现有formatter只接有限合法范围，真实source显式0保留；纯local边界无连续遥测不授予provider coverage。
- **D03/D04/D09/D13/D14/D15**：两页全部信号共用`projectDriveCurves`，sort/window/duplicate conflict/finite/Float可表达/SOC范围资格相同。缺值不压缩到别人的时刻，原始日期缺失造成断线，有效乱序本身不误断；downsample保留originalIndices/xFractions，hover与Fullscreen也使用同一时间位置，line/fill不桥gap。
- Stats样本也读同一投影的原Double/Int值，海拔相邻差按实际时间顺序、null/冲突/long gap断开，Long累加超Int不饱和造数。合格reported extrema优先；fallback或provider明确样本统计用现有label位置标“样本最高/最低/均值”，已知speedSampleCount可附计数，不把样本mean当时间均值。速度用原receipt preferred单位一次；温度用同receipt canonicalC后转显示偏好一次。negative/real0与unknown分别处理。
- **D07/D12**：完整all-quality Room chronology进入邻接，显示仍过滤quarantined；缓存发布前绑定cached scope/context proof，当前scope的首个Flow发射不能误清cache。详情先用已验证cached mapping/Room summary+point receipt发布，才发HTTP发现/刷新，identity与detail各有20s上限。失败保同scope缓存，scope/UID不再current则清数据；新请求与optional metadata/天气晚回包不能覆盖新身份。无数据错误分支复用现有MetricStatusPanel/retry，正常布局不动。
- **D08/D10**：两侧地址分别unknown；反查坐标只能用同边界完整pair或60s内同窗唯一最近GPS，不能把scalar latitude与另一观察longitude拼成地点。距离曲线为真实speed×dt梯形积分，首个观察锚0；缺速/未知时刻/断档后不假定里程为0，也不线性补齐或按报告总里程比例分配。仅显示已观察连续前缀估计，顶部报告总里程仍独立保留。
- **Route/D05/N04**：`observedDriveRouteSegments`可供Root Trip复用，验证GPS、真实clock和窗口，同刻冲突/invalid GPS/unknown clock/long gap断线，保A→B→A，只去相邻重复；DD仅传数据segments，没改shared map、颜色/高度/布局。
- **D16**：缺天气码为UNKNOWN而非晴天；Trip天气只消费匹配driveId/window内的真实observedAt，缺时刻不插值，错slot不借下一个drive。请求显式GMT/C，日+小时按真实Instant→UTC匹配，显示仍是用户当地时刻。`TripWeatherPoint.clockContractVersion`默认0；只有新真实GPS+UTC查询成功返回有限温度时写1。Root复核Trip没有持久化天气codec，仅fresh fetch，已在Root投影保留version=1门；未因旧缓存推测发明新schema或改旧JSON。

天气协议来源：父2026-10-27只读核验[Open-Meteo官方历史天气API](https://open-meteo.com/en/docs/historical-weather-api)，导入默认GMT/C及显式参数合同；本组没有联网/运行真实天气请求。合成UTC/Shanghai跨日控件证明客户端时钟计算，不代表真实provider自然天气验收。

## 自审与兼容

- 正常页面参数、排列、颜色、字号与尺寸没有重排；仅数据取值/限定label及原空白错误态绑定。没有恢复已被别的actor移除的驻车地图。
- 正常旧fixture补足其声称“observed”的日期/SOC/地址原receipt，数值断言不变；不能继续用primitive代替缺失receipt。RequestIsolation的纯race fixture明确由fake port宣告其immutable payload已被源层admit；生产默认/真实Room源仍严格验窗，协议本身由VM+Store真实Room回归覆盖。
- 最终仍未验：真实provider自然数据、生产部署、设备/像素、GPS/温度传感器采样完整性、历史高度datum与天气模型准确度。它们不由源码单测或完整HTTP receipt授予。

## 最终验证与交接

父runner19真实RED后完成GMT/C、nullable metadata与时窗统计GREEN；父runner20实际182例/4失败/0error/0skip，其中F3全部通过，其余失败属于Root新Current边界/F5测试literal。F3执行**只读XML核对命令**，14份最新 `android/app/build/test-results/testDebugUnitTest/TEST-*.xml` 合计 **40 tests / 0 failures / 0 errors / 0 skipped**，并确认关键天气XML更新时间晚于最后Weather源码写入。F3没有另跑Gradle/Go或冒认父runner执行。

| 本组类 | tests / failures / errors / skipped |
|---|---|
| AppReleaseDrivingRegressionTest | 6 / 0 / 0 / 0 |
| F3ParkedReceiptRegressionTest | 3 / 0 / 0 / 0 |
| F3ChartPreparationRegressionTest | 3 / 0 / 0 / 0 |
| TimedChartEvidenceRegressionTest | 3 / 0 / 0 / 0 |
| WeatherConditionEvidenceRegressionTest | 2 / 0 / 0 / 0 |
| DriveCurvePhysicalEvidenceTest | 3 / 0 / 0 / 0 |
| DriveDetailRoomFirstNetworkTest | 1 / 0 / 0 / 0 |
| DriveRoomFullSequenceRegressionTest | 1 / 0 / 0 / 0 |
| ParkedReceiptPublicationRegressionTest | 1 / 0 / 0 / 0 |
| ParkedHistoryReceiptRoomTest | 4 / 0 / 0 / 0 |
| DriveRouteSegmentsTest | 4 / 0 / 0 / 0 |
| TripWeatherObservedTimeTest | 3 / 0 / 0 / 0 |
| WeatherTimezoneEvidenceTest | 4 / 0 / 0 / 0 |
| ParkedWireCoverageEvidenceTest | 2 / 0 / 0 / 0 |

Root对旧SavedTrip天气反证复核：当前TripDetailVM没有持久天气读取，仅每次清空+fresh fetch；没有发明weather codec或新增schema。Root发布时只接受clockContractVersion=1，默认0不升级成已验证；其它旧内容不重写。

Owner文件已冻结，Root进行全量QA、最终差异审核、版本记录与提交。只有明确失败case才重开本组产品，不再追加研究。

## 全量Debug22后的限定复修

父全量Debug22实际1555例/17失败/0error/0skip。只接父指定的5个测试文件及相关Owner代码，未扩查：

| 失败 | 真实根因与最小处理 |
|---|---|
| HistorySourceRecordWireAliasTest.actualOfflineDriveVm… | **真实生产投影回归**：`summary.copy(positions)`抹掉完整point header的alias拼写/丰富字段。现在以已通过source/window/digest验证的header为原子base，保其原alias pair、unit、丰富字段；同单位才承接当前summary非空motion/report值，current qualified energyContract/scalar始终优先，旧header不能复活已撤销能量。实际VM测试增加header地址/时长/reported maxima/68F→20C断言。 |
| ParkedDetailLocalFallbackTest 3例 | **fixture问题**：手写raw JSON有额外闭括号，且此前primitive独有字段不能作为receipt证据。改为真实DriveData→toLocalSummary生产映射，明确distance/SOC/地址；数值断言保留，未放宽生产source/instance/window门。 |
| RangeSpeedUnitsConsumerTest / RangeStatusUnitsConsumerTest各1 | **温标fixture不足，不是模型门回归**：历史response只声明长度，raw20温度没有同响应温标，被正确映射unknown，5条不能通过分组门、global又未达10条。fixture补明确C，保GROUPED原断言；增加unknown-temp-unit仍保5候选但adopted0/UNAVAILABLE控制，模型门未降低。 |
| DriveListEnergyConsumerTest同SOC145 | **过期错误契约**：无能量证据且整数SOC不变应unknown；不再断言0.116kWh/145Whkm。其他真实0、净负能量、合格SOC容量估计控制保留。 |

23 focused已由父启动，包含该5文件及Alias/Energy revocation完整保护；此时只完成实现与fixture修正，**未收到23结果前不记通过**。Owner source/tests继续冻结，只有真实failure再重开。

随后F3只读核对23的新XML（更新时间晚于本轮DVM源码写入）：HistorySourceRecordWireAliasTest 10/0、ParkedDetailLocalFallbackTest 5/0、RangeSpeedUnitsConsumerTest 6/0、RangeStatusUnitsConsumerTest 6/0、DriveListEnergyConsumerTest 6/0，合计 **33 tests / 0 failures / 0 errors / 0 skipped**。这只确认五个目标类的实际XML，不外推23整个任务或下一轮全量结果；父统一记录整体退出码与全量QA。源码保持冻结。

## Shared图表数据API

`TimedChartSample(timestampMs:Long?, value:Double?, sourceIndex:Int)` → `projectTimedChartSeries(samples, windowStartMs?, windowEndMs?, maxGapMs=60000)` → `.values/.timestampsMs/.xFractions/.sourceIndices/.gaps/.validCount`。同刻冲突值是缺口，真0和负功率保留；同一source/window/domain下绘图。值缺失通过NaN输入标记表达，prepare只把有限点送绘图，并保留缺口及原索引。Downsample后选点/tooltip使用真实fraction，Fullscreen复用完全同一数据和时间回调。

单轴追加可选参数：`xFractions, sourceIndices, gaps`。双轴追加左右各自的 `leftXFractions/rightXFractions`, `leftSourceIndices/rightSourceIndices`, `leftGaps/rightGaps`，两个series必须共用时间domain。双轴选中时只取同fraction已观测值，不借另一时刻数值拼为同一观测。父负责CurrentChargeScreen绑定，本组不编辑该页。

所有测试均为合成输入，不是真车、真实provider、部署或可见像素验收。
