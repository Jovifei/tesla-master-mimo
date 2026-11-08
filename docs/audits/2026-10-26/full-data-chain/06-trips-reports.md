# 06 — Trips / CreateTrip / TripDetail / AnnualReport / AnnualReportPDF / Export

日期：2026-10-26。审计者：`/root/audit_driving`，继续使用原模型/推理配置。初始只读目录：`E:\project\tesla_master\app_mimo`；最终审计/文档目录：`E:\project\_worktrees\matelink-release-data-audit-20261026`。原目录06草稿与新目录初始06文件SHA256均为`5517FBA11D997948BB3F01E77999E66DC7CA1ACD96301F77C405ABC6279BF37A`，确认已复制完整后，仅在新目录收口。审计基线是父任务确认的原`cdfe0b26`加Owner dirty的稳定源码复制；不得用后来变化的共享Git元数据冒认原目录版本。本轮没有改产品文件，不认领并存的Range/Mileage修复；除本文外，仅按父任务指示在新目录03文档追加冻结快照复核。

本次覆盖 **6 个真实路由，104 行页面/输出字段矩阵**。未执行任何构建、测试、安装、设备或网络操作，未生成PDF/CSV/JSON，也未读取私人导出。工具通道曾停止返回；按父任务要求停止重试，父确认恢复后才继续读源码。因此“已读取源码/测试存在”与“运行成功/文件可用/实际像素正确”严格分开。

判定：`SOURCE_OK`=所述源码链/公式可确认；`BUG`=可定位确定分支及触发输入；`SOURCE_DATA_MISSING`=当前源/缓存/协议没有相应证明；`UNVERIFIED`=本轮无实际运行证据。下文所有运行级验证均 **UNVERIFIED**，不继承旧测试数量、版本或手机结果为本页通过。

已读父/本项目 AGENTS 与 lessons，图工具此前由父任务尝试且不可用；本轮按父任务指示进行限定文件搜索/行号读取，没有重复图调用。总计划、Hub 和最终冻结由父任务统一维护。

## 真实入口与路径索引

`android/app/src/main/java/com/matelink/ui/navigation/NavGraph.kt:881-924,959-985` 注册六路由：Trips → CreateTrip → 创建成功的 TripDetail；Trips/DriveDetail → TripDetail；AnnualReport → AnnualReportPDF；Settings → Export。创建与导出都连接实际写入代码，不是示例数据页；本次没有调用它们。

下文路径相对最终新项目根；短名对应唯一文件，`短名:行号` 均为本次读取的1-based位置。`isolation.json`确认954文件稳定复制，原审计开始后的产品变化仅涉及`ChargesScreen.kt`、`ParkedDetailScreen.kt`、`ParkedErrorPresentationTest.kt`，不涉及06的六路由源码；本文不根据共享`core.worktree`判断版本，也不修改该配置。

| 短名 | 源码路径 |
|---|---|
| TS / TV / TF | `android/app/src/main/java/com/matelink/ui/screens/trips/TripsScreen.kt` / `TripsViewModel.kt` / `TripsHistoryRefresher.kt` |
| CS / CV / AL | `android/app/src/main/java/com/matelink/ui/screens/trips/CreateTripScreen.kt` / `CreateTripViewModel.kt` / `AddLegSheet.kt` |
| TD / TM / TB / TC | `android/app/src/main/java/com/matelink/ui/screens/trips/TripDetailScreen.kt` / `TripDetailViewModel.kt` / `TripTimelineBuilder.kt` / `TripCache.kt` |
| TT / TW | `android/app/src/main/java/com/matelink/ui/components/TripTimeline.kt` / `TripWeatherSparklineCard.kt` |
| TR / TA / DT / MODEL | `android/app/src/main/java/com/matelink/domain/TripRepository.kt` / `TripAggregator.kt` / `TripDetector.kt` / `model/Trip.kt` |
| SD / RC | `android/app/src/main/java/com/matelink/data/local/dao/SavedTripDao.kt` / `android/app/src/main/java/com/matelink/data/local/entity/TripRouteCache.kt` |
| AS / AV / AM / AY | `android/app/src/main/java/com/matelink/ui/screens/reports/AnnualReportScreen.kt` / `AnnualReportViewModel.kt` / `AnnualReportMetrics.kt` / `AnnualReportYears.kt` |
| PS / PV | `android/app/src/main/java/com/matelink/ui/screens/reports/AnnualReportPDFScreen.kt` / `AnnualReportPDFViewModel.kt` |
| ES / EV / DE | `android/app/src/main/java/com/matelink/ui/screens/reports/ExportScreen.kt` / `ExportViewModel.kt` / `android/app/src/main/java/com/matelink/data/export/DataExporter.kt` |
| SR / DD / CD / AD | `android/app/src/main/java/com/matelink/data/repository/StatsRepository.kt` / `android/app/src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt` / `ChargeSummaryDao.kt` / `AggregateDao.kt` |
| HM / EB / WE / UF | `android/app/src/main/java/com/matelink/domain/analytics/HistorySummaryMapper.kt` / `DriveEnergyBinding.kt` / `WeightedEfficiency.kt` / `android/app/src/main/java/com/matelink/domain/model/UnitFormatter.kt` |
| WR | `android/app/src/main/java/com/matelink/data/repository/WeatherRepository.kt` |

## 共用来源与持久化结论

- **Trip 不保存统计快照。** `SavedTrip.kt:15-29` 仅存local car/name/source/时间；`SavedTripLeg` 保存类型/ID/位置，`SD:40-45` 事务插入trip与legs。`TR:476-492` 每次按legs从DriveSummary/ChargeSummary重建，缺失或被隔离的leg被mapNotNull去掉，当前实现没有“原成员缺失”覆盖计数。应区分“保存了引用”与“原始证据仍可完整恢复”。
- **能量算式与资格是两层。** `TA:41-47` 与 `WE:23-44` 的有限净能量总和、同集合能量/正距离配对加权、零/负值、overflow及记录/已知距离覆盖算式是合理的。但 `TR:46-48,303-329,476-492` 直接将Room实体传TA，未统一经nullable receipt资格投影；`TA:34-51,69-70` 读取primitive列。此根因与 [03-driving-parked.md](03-driving-parked.md) **D02（Room占位旁路）** 共用，本文记为 **H1扩展消费者，不重复增加根因数**。影响包括Trip总距离/时间/充电、SOC、legacy能量、候选行、timeline，以及Export的所有原始列和PDF record行。
- **年度数据链较完整。** `SR:239-265` 把remote car映射到已验证local car；`SR:298-325` 在一个Room事务捕获rows/overrides/aggregates；`SR:329-344` 用nullable receipt按质量和本地年份选择同cohort；`SR:150-153,275-295` 再绑定合格总量、配对效率和同币种有效费用。月图由相同冻结rows投影（SR:161-196），没有在PDF生成过程中重新取可变UI数据。
- **PDF VM有冻结及失效门。** `PV:47-50,70-100,140-149,183-207` 将year、car、scope/context、AnnualStatsSnapshot冻结；切年或scope取消旧工作，生成后再次核验才能发布路径。普通CSV/JSON的EV没有同等保护。仍需验证PDF输出文件身份与URI生命周期，见N16。
- **来源层级明确。** 上述内容是源码审计。没有自然行程、自然充停充复充、真实费用回执、实际Room文件重开、生成文件内容、PDF像素或外部分享接收方证据；不得将合成测试或API可连接等同于这些层级通过。

## 字段矩阵（104行）

### 1. Trips（TR01–TR12，12行）

| ID / 可见位置 | UI → VM/真实来源；公式/单位 | 保存、空态/覆盖、判定 |
|---|---|---|
| TR01 进入/返回刷新 | TS:98,103-113 → TV:66-83,120-141 → TF:22-44先refreshHistory再用localHistoryCarId读TR.getTrips | 有正确local映射fallback；没有Room先显，网络可挡首屏；本次运行UNVERIFIED。SOURCE_OK（映射）/BUG N02（scope失效） |
| TR02 自动发现规则及空态说明 | TS:153-198 → DT:31-35,43-126；≥2drive、≥1DC charge、总距≥300km；micro<1km先排除，drive→charge≤15m、charge→drive≤180m、drive→drive≤30m | TR.getTrips实际会autoPersist和删除“重复”saved trip，不是纯读取（N06）；负gap也满足上限，重叠合法性未检查。SOURCE_OK（实际规则），活动完整性UNVERIFIED |
| TR03 年份/自定义日期 | TS:259-271,584-640 → TV:91-108,145-155；按trip.startDate本地日期归属，全段不按窗裁切 | 筛选仅内存，未SavedState保存。仅availableYears>1显示整个筛选行；选空结果TS:153空态会隐藏返回筛选入口。BUG（筛选可达性），区间统计是“按开始日归属整旅途” |
| TR04 摘要旅途数 | TS:277-283,357-360 → TV:160；filtered.size | 已保存引用+新检测旅途，非drive总数；空trip状态不显示摘要。SOURCE_OK |
| TR05 摘要总距离 | TS:364-365 → TV:161 → TA:34；Σtrip距离，canonical km再UF.formatDistanceKm转换显示mi | 实际legacy/缺失primitive0及非法sum旁路H1；没有距离样本覆盖。BUG（H1扩展） |
| TR06 摘要行驶时间 | TS:374-375 → TV:162 → TA:35；Σdrive.durationMin | Int.sum可能overflow，未知分钟placeholder0没有分母覆盖；相同leg若存在于多个保存trip也重复计。BUG（H1/成员集合边界） |
| TR07 摘要已充电kWh | TS:381-382 → TV:163 → TA:48；Σcharge.energyAdded | Double非空，未知与真0无法区分，部分覆盖没说明；整数显示会舍掉小量，来源H1。BUG（H1扩展） |
| TR08 单旅途日期/名字/路线/距离 | TS:454-475,679-699；名字优先手动，否则首末drive城市；canonical km转换 | name保存在SavedTrip；日期在归属日格式化；未知地址primitive空字符串经extractCity；距离H1。SOURCE_OK（绑定/名称），BUG（H1数据资格） |
| TR09 指纹时间条及点选信息 | TS:437,481-485 → TB:24-70；drive/charge事件排序，≥5min间隙插parking；时长决定宽度，长停车/AC使用压缩权重 | 输入startDate先按字符串排，跨offset可能反序；未知类型被非DC→AC，N08/N10。SOURCE_OK（渲染真实成员），BUG（时间/分类） |
| TR10 总历时/充电停靠次数 | TS:492-506 → TA:36-40,48；首drive.start至末drive.end，charges.size停靠数 | 手动leg可有区间外charge但总历时/起终点仍由drive决定（N08）；count是真实存储成员数不证明完整自然会话。BUG N08 |
| TR11 总净能量和均耗/双覆盖 | TS:524-566 → TA:41-47,72-75/WE:23-44；Σ有限净kWh；配对ΣkWh/Σ正km×1000，再÷10 kWh/100km；能量coverage与效率coverage独立 | 文案区分known/complete；真0、负回收保留；记录覆盖=valid pairs/all drives，距离覆盖=paired distance/所有已知正距离，不含未知距离。算式SOURCE_OK；输入资格仍H1 |
| TR12 错误/旧缓存/迟到单位 | TV:111-117,125-138把historySyncWarning/units放state；TS:204-223未传或显示warning | identity不可用/远端失败可被空态“尚无旅途”替代；同remote carId换账号无scope订阅，旧trip/units不清（N02）；旧row返回后generation同样有效。BUG N02 |

### 2. CreateTrip（CR01–CR12，12行）

| ID / 可见位置 | UI → VM/来源；公式/单位 | 保存、空态/覆盖、判定 |
|---|---|---|
| CR01 路由/初始日选择 | CS:74-88,157-162 → CV:56-81；day→无offset start00:00/end23:59:59，再候选窗口±1day | started布尔禁止同VM换car重新init；直接remote carId当Room key；N01。日期anchor仅草稿内存，不等于最后旅途严格窗口 |
| CR02 手动名称 | CS:124-129 → CV:127-132；trim空为null，供TA预览与TR.createTrip | SavedTrip.name持久；不是手动输入里程/能量/费用。SOURCE_OK（绑定）；保存成功门N11 |
| CR03 预览名字/路线/日期 | CS:178-206 → CV:148-156 → TA首末drive；标题manual或city→city | 不要求>=2drive/DC/300km；至少一个能resolve的drive才有preview；只有charge不允许点保存。SOURCE_OK（手动条件） |
| CR04 预览距离 | CS:194-196使用UF.formatDistanceValue(preview.totalDistance,units) | preview.totalDistance是canonical km，UF:72-73只返回原值，mi偏好只换标签；160.9344km显示161mi，正确应100mi（N09）。BUG |
| CR05 预览总时长 | CS:198 → TA:36-40；首drive.start至末drive.end | charge在首drive前/末drive后仍入能量和列表却不入历时；跨offset字符串排序可改首末（N08）；输入缺失资格H1。BUG |
| CR06 添加/多选数量 | CS:165-172 → AL:63-100,123-141 → CV:84-118；eligible按未用leg ID与±1day查，draftSet去重 | selected保存在Compose内存；列表真实来自Room，非示例。调用未用scoped local key（N01）；选中后source/window是否更正未重新核准（N11） |
| CR07 候选drive：地址/时间/km/分钟 | AL:263-282 → TR:303-315；primitive DriveSummary | 日期格式化，km固定；未知/真0被H1混合；不依据receipt单字段资格。BUG（H1）；固定km与预览偏好并不一致（N09） |
| CR08 候选charge：地址/时间/ACDC/kWh/分钟 | AL:299-342 → TR:303-320；primitive energyAdded/duration；DC IDs由AD查 | 未拿到DC aggregate就标AC，缺失charge energy被0，N10/H1；不显示有效费用。BUG |
| CR09 已选drive/删除 | CS:283-316 → CV:121-124；单项地址、`%.1f km`、durationMin及LegRef删除 | draft保存在state，重建resolve真实Room；异步remove/pick没有generation序列，旧rebuild可能后发布覆盖新draft，运行UNVERIFIED；输入资格H1 |
| CR10 已选charge/删除 | CS:319-354；AC/DC、+energyAdded、分钟；CV删除chargeRef | 同CR08；不将缺类型当AC（N10），不把unknown当0（H1）。BUG |
| CR11 草稿范围/先后顺序 | CV:92-96,148-150 → TR:305-319,343,397；dates.min/max和sortBy原字符串，候选比较也原字符串 | 跨offset/不同精度日期不是同instant排序，可能显示倒序或遗漏；N08。SOURCE_OK（实际算法），BUG（时间语义） |
| CR12 保存→新详情 | CS:103-108,86-87 → CV:136-144 → TR:339-358 → SD:40-45 | 真实事务写SavedTrip+legs，但VM忽略返回null、无异常finally；TR只检查refs有drive类型，未保证该drive仍可resolve、未验证仍unused/同source/window。N01/N11。实际保存/冷重开UNVERIFIED |

### 3. TripDetail（TD01–TD22，22行）

| ID / 可见位置 | UI → VM/来源；公式/单位 | 保存、空态/覆盖、判定 |
|---|---|---|
| TD01 详情身份/首显/冷重开 | TD:121,193-206 → TM:277-346；cachedContext→scoped localCar；TC按startDate传内存trip，使用cost projection检查所有成员carId；否则readOnly saved重建 | TM:231-258主动scope失效清trip/route/weather，较列表完善；TR.getSavedTripsReadOnly不自动写/删。SOURCE_OK；实际Room重开UNVERIFIED |
| TD02 标题/改名 | TD:143-149,266-271 → TM:488-516 → TR.renameTrip/getTripName | user name优先，空回路线名；编辑前后检查local context。SOURCE_OK（当前入口） |
| TD03 地图日期/总距离贴片 | TD:305-310,1143-1155 → TA.start/end/totalDistance；formatTripDateRange，UF.formatDistanceKm | canonical单位换算正确；区间由drive边界决定，N08；缺失placeholder H1。SOURCE_OK（格式）；BUG（来源边界） |
| TD04 每drive真实GPS/折线 | TM:674-715,767-778 → provider.getDriveDetail → raw positions lat/lon → simplifier → TD:1117-1140 flatten全部段 → AmapRouteView | 未验响应drive ID/source/window，也未读03的verified detail receipt；cache只ID集合，N04；段失败压缩槽位N05，flatten又跨缺段连线。BUG |
| TD05 地图起终标记 | TM:717-747用所有成功段first/last；TD:1139-1140用trip文字label | 首末drive失败时把中间/剩余段坐标标为全trip起终；对真实完整路线不能背书（N05）。默认WGS→GCJ沿03同renderer链，不新增该根因 |
| TD06 地图充电站点击/预算/失败 | TM:172-188最多12drive、并发2，超限cachemiss延后；TM:731-737准备charge markers；TD:1090-1106明确预算文案 | TD:1136-1141只传start/end标题及line，没有传charge marker/回调；pendingChargeNav未被地图赋值，站点地图导航实际不可达。SOURCE_DATA_MISSING（当前连接）；SDK/Key/source失败反馈沿03 |
| TD07 timeline起终城市/时刻 | TD:332-343 → TT:198-206,389-480；真实trip字段，country flags可跳国家页 | 图条是所选成员重建，不证明自然活动完整；按原字符串事件排序和charge外窗N08；city提取无位置证据不能当GPS。SOURCE_OK（绑定） |
| TD08 timeline行驶/充电/总时间 | TD:317-319,340-342 → TT:234-261；Σdrive分钟、Σcharge分钟、首末drive elapsed | overlapping sessions不union，Int求和无overflow门，unknown→0来自H1；条宽对停车/AC刻意压缩不是真时间比例。BUG（H1/窗口口径） |
| TD09 timeline单段点选 | TB:24-70 → TT:326-362；drive km/min、charge kWh/min、停车gap≥5min；序号与成员数 | 未知ACDC默认AC（N10）；gap只从所选集合产生，未看隐藏/隔离中间活动，不能把该parking当03已证自然驻车。BUG（分类/证明缺口） |
| TD10 国家/地域旗标 | TM:591-642：AggregateDao同localCar的drive边界+charge位置，geocodeCache经纬度*100取整查country；去重中间国TM:652-669 | 只保有cache命中的codes后再取first/last，起终点无cache时中间国可能被当起终国；country/route缓存key同N04不含证据revision。SOURCE_DATA_MISSING（端点国资格），实际地域UNVERIFIED |
| TD11 费用总额/覆盖 | TD:346-355,578-590 → TM:130-155；同car+同session手动override/free/provider按所选currencyCode resolve；known finite costs求和 | 未知不补0；allPriced及priced/energy/paired计数；无任何cost时隐藏card。SOURCE_OK（有效费用投影），UI/提供方UNVERIFIED |
| TD12 费用环图/分类 | TD:506-525,569-575；仅全部priced、energy已资格、type有证明才环图；cost>0切片 | 真免费0保留在总数/覆盖，但不出零角切片；全部免费走0.00文字。SOURCE_OK；与未修的energy-flow未知AC不同 |
| TD13 每100距离费用 | TD:595-607：allPriced且totalDistance>0才totalCost/totalDistance*100；unit来自偏好 | 分母是canonical km，mi仅变标签，无mile换算；费用/100mi错误（N09）。距离缺失/不全H1也可稀释/膨胀。BUG |
| TD14 每kWh费用 | TD:598,611-615 → TM:145-155；仅同一session有限cost/正qualified battery kWh配对Σcost/ΣkWh | 真0cost合法；energy缺失不进入分母；无正能量pair时null。SOURCE_OK；T3 |
| TD15 展开逐次充电费用 | TD:621-665；每站address、同资格kWh或—、分钟、有效cost+同currency；点击chargeId | 费用已经projection，不直接用TA.totalChargeCost；分钟仍raw H1；不会用估算总费用按比例分摊伪造站费。SOURCE_OK（cost） |
| TD16 电池充电总能量/DCAC分量 | TD:724-727,783,987-1045；按dcChargeIds拆分，非DC全进AC，Σraw energyAdded | 无资格/coverage、unknown primitive0；真0用—隐藏；无aggregate已知DC/unknown也都作AC。BUG H1/N10 |
| TD17 DCAC平均功率 | TD:728-731,1023,1045；Σ组kWh*60/Σ组分钟，duration≤0直接0 | 未确保能量和时长来自同可用集合/真实物理输入点，unknown duration可造0kW；N10/H1。BUG |
| TD18 已耗净kWh/效率及覆盖 | TD:732-735,1061-1069,769-770 → TA/TS CombinedTripEfficiencySummary | 能量真0负值保留，known/total、paired效率、双coverage与列表相同；算式SOURCE_OK，输入资格H1；负回收仍使用outflow配色语义待像素验收 |
| TD19 leg数/展开drive卡 | TD:399-421,1197-1215,1247-1263；drive/charge成员计数，地址、canonical km转显示mi、分钟，driveId导航 | 缺失SavedTripLeg被mapNotNull后不显示原缺项；可能完整标签以幸存成员为分母。BUG（H1/成员覆盖），跳转绑定SOURCE_OK |
| TD20 展开charge卡 | TD:1312,1320-1335；AC/DC、地址、raw+energyAdded、duration、chargeId导航 | 不能证明type/energy则当前仍AC/0，N10/H1。BUG；逐次费用卡比此旁路严格，需统一 |
| TD21 添加/合并/删除/返回重算 | TD:237-272,469-473 → TM:415-537,554-576；先checkedLocalCarId再write，回流重读TR | 操作入口实际连接持久化；未在本轮执行；parent同scope逻辑总体SOURCE_OK，事务内换scope与被删除leg补偿仍UNVERIFIED；merge候选原range/km见MergeTripSheet.kt:122-141 |
| TD22 旅途天气温度曲线 | TD:476-479 → TM:757-761 → WR:370-404,413-442 → TW:63-76,119-135,178-180；每drive点按端点时间线性插时，3..12样本，摄氏按偏好转F | 点已丢timestamp/driveId；partial segment套错drive时间（N05），并非车辆真实温度采样曲线；TW按index等距，缺口问题引用03-D03；缺weathercode晴天引用03-D16。不重复计根因 |

### 4. AnnualReport（AR01–AR16，16行）

当前可达实现是 `AS:104` 调用 `HonestAnnualSummarySection`。文件中 LegacyHonestAnnualSummarySection及注释保留的AnnualSummarySection不是当前界面，不能把其中records/旧正值门当已显示字段。

| ID / 可见位置 | UI → VM/来源；公式/单位 | 保存、空态/覆盖、判定 |
|---|---|---|
| AR01 年份/路由标题 | AS:46-53,79-82 → AV:99-125,140-159；AY:4-8确保当前/前一年+真实历史 | VM切年清旧stats/months再load；年份不会靠固定2025伪造。SOURCE_OK；跳PDF仍传初始route.year（N03） |
| AR02 scope/Room/刷新状态 | AV:87-95,103-138,162-186 → SR:239-265；reportLoader核对scope/epoch/year，Stats snapshot单Room cohort | 页面有STALE banner AS:85-89，但freshness来自**另一次**后置AnalysisHistory.load；无standby结果也可能默认FRESH。不是当前snapshot来源时间证明；真实离线/首显UNVERIFIED |
| AR03 行程/充电次数 | AS:259-260,278-279 → SR:329-344,489-498；同scoped车、合格质量、以start本地年归属，跨年整session入开始年 | 空时no_records；不是所有历史raw行。SOURCE_OK；没有“年份中的有效自然采集天数”分母 |
| AR04 总距离 | AS:241-255 → SR:150-153/analysisCoverage；有限非负distanceKm求和，至少一份distance样本才显示km | 已知0可显；未知全体不显示fake0；partial仍标总距离无partial计数，N12。SOURCE_OK（计算），BUG（覆盖呈现） |
| AR05 总净能量 | AS:242-267 → SR:152,492；Σqualified有限净kWh，距离缺失不影响能量资格 | 真0/负回收保留；partial显示全量标题，N12。SOURCE_OK（值） |
| AR06 平均能耗 | AS:271-272 → SR:153/WE；同合格energy+positive distance集合加权Wh/km | canonical Wh/km固定，不受mile偏好。零/负值在年度实际页可显示；无pair unknown；partial分母/估算来源未显示N12。SOURCE_OK（算式） |
| AR07 充电能量 | AS:243-246,283-284 → SR:286/coverage；合格batteryInput≥0有限求和，至少一份energy样本 | unknown全体no_records，不借AC grid输入充电量；partial不解释N12。SOURCE_OK |
| AR08 有效充电费用 | AS:290-291 → AV:56-60 → SR:119-143,287；manual/free/reported/estimated共用有效费用与所选currency | knownTotal非全未知才数值；不同币种不混；显示symbol未附完整code，跨币种metadata由snapshot保存code。SOURCE_OK（资格）；partial费用标题N12 |
| AR09 每kWh费用 | AS:295-296 → SR:288/calculateCostTotals；相同charge cost+positive qualified batteryInput配对 | no正pair未知；不拿全charged总量稀释。SOURCE_OK；没有pairedCount说明N12 |
| AR10 待机kWh | AS:300-303 → AV:171-174 → AM:23-63；算法要求explicit有效容量、相邻不重叠chargeSOC下降且无drive重叠 | 实际AV未传容量，默认null使AM:30直接返回null；当前必走coverage insufficient。SOURCE_DATA_MISSING（容量/物理活动证明），不得补默认电池容量伪修 |
| AR11 月距离图 | AS:498-506 → SR:180-195；按本地年月，Σ已知finite非负km，每month有distanceSampleCount | null不画，真0画小点；UI仅渲染存在记录的month，Jan/Dec被等距邻接，N13；partialcount被丢N12 |
| AR12 月次数折线 | AS:510-516 → SR:193；合格driveCount/月 | 只existing月份无12月槽，缺月被桥接N13；折线是计数非车辆采样时间线。SOURCE_OK（counts） |
| AR13 月充电能量柱图 | AS:520-529 → SR:161-178；Σ同月qualified batteryInput | null不画、0点；月energySampleCount未显示；同N12/N13。SOURCE_OK（值） |
| AR14 平均时长/驾驶日/每天次数 | AS:651-660 → SR:444-448,495-496；已知非负duration样本mean；有合格drive的distinct本地date数；总driveCount/days | duration缺失不当0；平均样本覆盖未解释；days不是自然记录已全采日期数。SOURCE_OK |
| AR15 能耗评级/最高速 | AS:664-681 → SR:493-494；均耗>0按150/180/220Wh/km阈值；≤0显示真实均耗不评级；最高规范化kmh | 无source不得填max0；真实0max可显；阈值是产品规则非提供方测量，不能外推节能驾驶结论。SOURCE_OK |
| AR16 最繁忙日/单日最多距离/生成PDF | AS:685-691；SR:512选记录数最多，SR:446-448,513选已知距sum最大；AS:123 → NavGraph:965-966 | 全日distance都unknown时empty.sum=0可生出“最多距离0km”日，需同AR04保持未知；PDF跨页year错N03。BUG（N03/缺失日总量） |

### 5. AnnualReportPDF（PF01–PF18，18行；同时审预览与文件）

| ID / 可见位置或输出 | UI/生成器 → VM/数据；公式/单位 | 空态、持久化、判定 |
|---|---|---|
| PF01 年份芯片/标题 | PS:44-45,77,100-109 → PV:102-172；同snapshot年份 | 候选仅Stats真实年份；无数据指定年仍可加载空snapshot；VM generation保护完整。SOURCE_OK（页内）；Annual入口N03 |
| PF02 预览描述/vehicle summary | PS:120-126；year模板文案 | 描述不是实际PDF像素预览；文件标题PS:316-317是通用vehicle summary，未嵌具体车身份。SOURCE_OK（实际文案），不是已验证真实车辆标识 |
| PF03 预览距离/行程数 | PS:133-146 → frozen stats.quickStats，distance sample>0门，km | 空计数直接0；distance全unknown显示no_records；部分合计没说明N12。SOURCE_OK（值） |
| PF04 预览净能量/平均能耗 | PS:147-158；kWh/Whkm | 净能量有限0负保留；效率额外`>0`使合格0/负均耗消失，而文件PS:337接受finite（N14）。BUG |
| PF05 预览充电次数/能量 | PS:151,160-162；count和qualified batteryInput total | chargeEnergy样本>0门、真0可见；partial无coverage N12。SOURCE_OK（绑定） |
| PF06 生成/分享/错误 | PS:169-190,49-70 → PV:183-207冻结scope/year/snapshot、IO调用generatePdf，再校验发布path | 真生成路径已连接PdfDocument/FileProvider/ACTION_SEND；本轮未运行/未点share。分享错误仅log，不提供用户失败反馈；SOURCE_OK（可达源码），实际可用UNVERIFIED |
| PF07 文件标题/页尾 | PS:313-318,433-436；year、通用vehicle summary、generated_by | 可翻页新Page，日期/车/source/版本不随文件输出；后续身份审查不能凭文件名认定车。SOURCE_DATA_MISSING（file-level provenance） |
| PF08 文件总距离/次数 | PS:304-305,322-328；同冻结qs；km | noDistance sample为no_records；count0是0。与预览同snapshot，运行文件验证UNVERIFIED；partial缺覆盖N12 |
| PF09 文件净能量/平均能耗 | PS:306-307,329-342；qualified kWh与加权Wh/km | 0/负均耗finite可输出，correct；partial未注明N12；Wh/km固定不误用英里单位。SOURCE_OK |
| PF10 文件充电次数/能量/费用 | PS:308-311,344-356；batteryInput kWh；knownTotal cost+currencySymbol；有cost sample且finite才打印cost行 | 真免费0可输出；全unknown费用行省略。手动/报告同币种pipeline保留；partial未说明N12。SOURCE_OK（计算） |
| PF11 最长drive纪录 | PS:361-365 → SR:426-428,502按qualified distanceKm选row，却输出原DriveSummary.distance | 选手与显示值可能分离；校正receipt100km但raw列10会输出10，H1扩展，需输出投影值/证据。BUG（H1） |
| PF12 最高速drive纪录 | PS:367-368 → SR:503按qualified speedKmh选row，输出原speedMax+startDate | 同H1；record date是原字符串不带独立观测时间，物理max source合同沿03。BUG（H1扩展） |
| PF13 最节能drive纪录 | PS:370-372 → SR:504选择positiveWh/km且distanceKm>5，回传row.efficiency | 合格0/负drive被产品纪录条件排除，显示值又绕投影H1；`N/A`仅缺raw efficiency。SOURCE_OK（实际选择规则），BUG（H1值绑定） |
| PF14 月距离图 | PS:382-387：12个nullable槽，month1..12填qualified totalDistance | 比年度UI完整保留12月轴；null不画/0点；coverage sample count未写N12。SOURCE_OK |
| PF15 月净能量图 | PS:390-394：12槽qualified totalEnergy；PS:268-283允许负柱及0点 | 保留回收负值；不将unknown月份当0；月极大Double→Float溢出仍未独立finite gate，运行UNVERIFIED。SOURCE_OK（正常域） |
| PF16 月充电能量图 | PS:398-405：12槽qualified totalEnergy batteryInput | 无整个monthly数据时PS:408-409明确no_monthly；仅有unknownmetric时coverage insufficient。SOURCE_OK |
| PF17 平均drive分钟/驾驶天/最高速/效率评级 | PS:415-429 → 与AR14/AR15同quickStats | 文件没有年度页的busiest/most-distance-day，也没有地域图/完整充电表；不将源码其他统计字段冒充PDF已输出。SOURCE_OK（实际内容） |
| PF18 PDF布局/页数/保存文件 | PS:200-203 A4 595×842，PS:234-263按y空间翻页；PS:440-450写cacheDir固定`matelink_annual_report_YEAR.pdf` | 本轮没有生成文件，PDF实际页数未知、字体/行宽/图表像素均UNVERIFIED；同year覆盖跨车/跨账号文件身份风险N16。SOURCE_OK（生成调用存在）/BUG（文件身份） |

### 6. Export（EX01–EX24，24行；含全部CSV/JSON列）

Export 的显示并不只在Screen：CSV/JSON文件每个字段也属于展示契约。以下仅查看生成源码，没有读取既有导出。

| ID / 位置或字段 | UI → VM/生成器；真实值/单位 | 空态、资格/持久化、判定 |
|---|---|---|
| EX01 格式CSV/JSON | ES:65-73 → EV:65-67 → DE:17-20,55-57 | 实际选择extension/MIME和writer，不是样例按钮；未运行。SOURCE_OK |
| EX02 数据类型drive/charge/both | ES:90-99 → EV:69-72,124-138 → DE:86-96,111-140 | BOTH CSV包含两张不同header的表，用空行分隔；不是一个统一8列关系表，接收者需按Type/section解析。SOURCE_OK（实际格式） |
| EX03 年份/AllTime | ES:114-128只列前5真实year → EV:74-84,121-122 | 仅年或alltime，没有任意日起止；年份来自SR scoped cohort，行数据却直接raw remote carId Room（N01）；无offset边界字符串比较N15 |
| EX04 预览drive/charge count | ES:145-153 → EV:79-108 → DD:197-219/CD:127-149 | count只observed/derived；AllTime实际data getter允许所有非quarantine（N15），两者行数不一致；每次选择launch不cancel旧count（N15） |
| EX05 导出按钮/加载/错误 | ES:159-171,189-194 → EV:116-155；counts>0且!isExporting使button可用 | state.format/dataType/year先捕获，carId是可变实例字段；没有source/context校验或before/aftergeneration；N01。实际IO异常显示原message，运行UNVERIFIED |
| EX06 shareUri/MIME/接收方 | ES:175-185 → DE:31-35；FileProvider URI/ACTION_SEND，read grant | share用**当前uiState.format**，可与正在生成的旧format不一致（N15）；scope换账号仍能发布旧URI（N01）。本次未分享 |
| EX07 文件路径/名称/保留 | DE:49-64,152-157：cacheDir，秒级timestamp，导出前清>1h的matelink_export文件 | 非下载操作；没有写入用户Download。本轮未执行。秒级并发/相邻按钮可复用同URI并覆盖（N16）；不含车/source/windowmanifest |
| EX08 CSV drive `Type` | DE:87-89固定DRIVE；JSON用drives数组区分 | 类型固定是record kind，不是source。SOURCE_OK |
| EX09 CSV drive `Start Date` | DE:89直接d.startDate，csvEscape | 原Room字符串，可能offset/无offset混合，不转换/不附timezone说明。资格/窗口未验（N01/N15），H1 |
| EX10 CSV drive `End Date` | DE:89直接d.endDate，csvEscape | Room非空字段；原missing可能空字符串而非明确null。H1 |
| EX11 CSV `Distance (km)` | DE:89直接d.distance，无nullable receipt/finite门 | canonical列声明km，正常数据无需英里偏好；缺失placeholder0变真实0、raw错值沿H1；invalid NaN/∞会写文本。BUG（H1） |
| EX12 CSV `Energy (kWh)` | DE:89直接d.energyConsumed，经csvEscape | null空cell，负数被通用公式防护加单引号（DE:73-74），变文本型而非可计算净kWh；未附method/quality/coverage。H1及typed-output缺口 |
| EX13 CSV `Efficiency (Wh/km)` | DE:89直接d.efficiency，经csvEscape | 没用qualified netEnergy/paired距离重算；null空、负数加apostrophe，同EX12；不输出依据/覆盖。BUG（H1扩展） |
| EX14 CSV `Max Speed (km/h)` | DE:89直接d.speedMax，经csvEscape | 非空primitive0无法表示unknown；不会按preferredstatus改单位，列是canonical kmh；H1 |
| EX15 CSV drive `Duration (min)` | DE:89直接d.durationMin | 原非空Int，missing/true0混合；invalid负值未排除；没有duration-str或sample覆盖。BUG（H1） |
| EX16 CSV charge `Energy Added (kWh)` | DE:94-96直接c.energyAdded，Type=CHARGE | 未走qualified batteryInputKwh，不能保证物理输入点；unknown0、不同质量行、旧未证energy都会导出。BUG（H1） |
| EX17 CSV charge `Cost` | DE:96直接c.cost，csvEscape | 没用effective cost/manual/free/currency projection，没currency列，跨币种数字混表（N07）；null空，0保留。BUG |
| EX18 CSV charge `Duration (min)` | DE:96直接c.durationMin，csvEscape | 原primitive，未知0；inrange与alltime集合不一致N15；H1 |
| EX19 CSV `Start Battery %` | DE:96直接c.startBatteryLevel | 未重建nullable receipt，unknown写0%，也不拒绝-1/101；H1，不是当前SOC替历史 |
| EX20 CSV `End Battery %` | DE:96直接c.endBatteryLevel | 与EX19相同；无边界observedAt，不可据此声称充电完整观测。BUG（H1） |
| EX21 JSON `exportDate` | DE:109：设备默认timezone格式yyyy-MM-dd'T'HH:mm:ss，无offset | 描述文件生成时刻，不是观测时刻；无car/window manifest，不能仅靠该字段重建来源。SOURCE_DATA_MISSING（timezone/provenance） |
| EX22 JSON drive全部键 | DE:115-121：`startDate,endDate,distanceKm,energyKwh,efficiencyWhKm,maxSpeedKmh,durationMin` | 与EX09-15相同raw列；nullable energy/efficiency用JSONObject.put(key,null)会删除键而非显式JSON null（end/max显式NULL分支对非空列不可达）；nonfinite数值会抛错而CSV写文本。H1/两格式null语义不一致 |
| EX23 JSON charge全部键 | DE:131-137：`startDate,endDate,energyAddedKwh,cost,durationMin,startBatteryPercent,endBatteryPercent` | cost显式JSONObject.NULL正确；energy/SOC依旧raw非空H1，cost无币种/override N07；不含chargeId/receipt/window proof |
| EX24 转义/排序/源字段缺口 | DE:67-76,164-191：CSV引号/逗号/换行escape，JSON递归key排序且保数组顺序 | 无VIN/照片/credentials字段，这是源码结论；输出也无record ID、source、method、quality、currency、sample coverage。不能作可追溯完整备份或跨页面验收receipt，SOURCE_DATA_MISSING；CSV numeric与string应分类型处理 |

## 新增确定缺陷与最小非布局修法

不重复计数：上面的 **H1** 引用03-D02，同一“绕过nullable receipt/原始占位当观测”的根因，在06的Trip/Export/PDF新增实际消费者。03-D03图表时间轴和03-D16缺天气码也只交叉引用。以下N01–N16为本波独立根因；本轮没有可确认P0。

### P1（N01–N07，7项）

**N01 — CreateTrip/Export 将remote carId直接当scoped Room key，并缺失迟到scope门。** `CreateTripViewModel.kt:56-69,96,142,148`、`ExportViewModel.kt:47-50,79-108,124-148`。触发remote=1但当前已验证localHistoryCarId=-101时，候选和导出读car1；如果旧legacy car1存在，可读/保存旧命名空间数据；没有时年度列表有历史却导出0。同数字carId换账号或导出期间init换车，旧协程仍发布计数/URI，创建仍可把旧草稿写入旧命名空间。最小修法：在这两个实际VM消费端捕获当前HistoryReadScope+VehicleContext，以context.localHistoryCarId读/写；进入、选择、save/export及发布前后均同generation/context检查，scope变化清草稿/旧URI/旧counts。沿用当前页面和参数顺序，不借最近一次裸Int映射补证明。

**N02 — Trips列表没有scope失效清空，网络先行的旧读取仍可发布。** `TripsViewModel.kt:53-70,111-141` 只按数字carId/generation挡重入，没有historyScopeChanges订阅；`TripsHistoryRefresher.kt:22-44` 取得local id后读trip/aggregate不再验证原proof，`TripsScreen.kt:204-223`也不显示historySyncWarning。触发同remoteId跨账号/来源变化，旧内容可一直保留，或scope变更后旧refresh结果重新进入state；units请求更无car/generation检查。最小修法：与TripDetail/AnnualReport相同建立scope观察及可取消loader，读Room前/后复核；本地先显同scope缓存，网络失败保留该scope缓存并使用现有提示槽，不将身份失败包装为无旅途。

**N03 — 年度页所选年份没有传给PDF。** `AnnualReportScreen.kt:79-82,123` 更改的是VM.year，但callback无year；`NavGraph.kt:965-966` 固定 `Screen.AnnualReportPDF(route.carId,route.year)`。触发初始路由2026，在年度页改2025再点PDF，打开的仍是2026。最小修法：callback传当前uiState.year，导航构造该实际year；以两年明显不同fixture验证标题、预览、冻结snapshot和最终PDF同年。无需调换任何UI位置。

**N04 — Trip地图走另一套未验source/window的详情与旧cache。** `TripDetailViewModel.kt:767-778` 对2xx只取positions，不核driveId、source/instance、window，不使用03已验证detail receipts；`:782-783` cache key仅localCar+sorted driveIds，`:678-682`任意cache命中即用，`TripRouteCache.kt:20-25`只有segmentIndex/bytes/createdAt。触发同driveID来源、窗口或轨迹更正后，旧线永久复用；错误drive detail也可写入当前trip。最小修法：每drive用已验证的HistoryDetailIdentity/receipt读取和同proof下载，cache key带精确source/window及receipt revision/hash；保持driveId关联并对损坏bytes及缺段降级。地图renderer/WGS规则仍沿03，不另造点。

**N05 — 失败路段被压缩后，天气位置套错drive时间窗，地图起终也冒名。** `fetchBoundedTripRoute:TripDetailViewModel.kt:181-188` filterNotNull及`:778`过滤empty；`:717-747`取剩余allPoints首末当全trip起终。`WeatherRepository.kt:370-382` 却按原driveIdx取routeSegments[driveIdx]并线性分配该drive时间。触发三drive A/B/C，A下载失败，B坐标被拿去查询A的历史小时，C坐标套B小时，地图起点仍标trip.startAddress。最小修法：segment存driveId/verified window，缺段保留显式槽位；weather按ID合并并用真实点时间，仅对有证明区间取样；不把中间首点标全trip起点。此是成员关联丢失，区别于03的同曲线mapNotNull索引根因。

**N06 — 仅打开Trips即可按drive集合删除含不同charge/用户信息的SavedTrip。** `TripRepository.kt:45-51` 调用cleanup；`:78-89`按fingerprint分组后删drop(1)，fingerprint`:100-109`只含排序driveIds。触发两个已存用户trip共享drives但charge legs、name、source或成员顺序不同，会被当重复，在无用户删除操作时失去一个保存实体。最小修法：浏览读取不删除Owner saved rows；限定历史可验证重复AUTO来源的修复并比较全部成员/名字/证据，另有迁移/恢复策略后执行。真实用户数据未在本轮读取或触发。

**N07 — CSV/JSON费用输出绕过有效费用和币种合同。** `DataExporter.kt:96,134`直接c.cost；`ExportViewModel.kt:132-145`没读manual overrides/currency，没有任何currency列/JSON键。触发同charge已有手动0或更正费用、raw旧cost仍12，详情/年度0而导出12；混合CNY/USD receipts仅一列裸数，不能求总也不能追单位。最小修法：以冻结同scope的EffectiveChargeCost projection导出，加入currencyCode、source/quality/method及费用状态，unknown留null/空、free保留0，拒绝无币种混加；原receipt独立保留不可改写。布局无需变动。

### P2（N08–N16，9项）

**N08 — 旅途时间范围与成员不一致，原字符串排序也不能证明先后。** `TripAggregator.kt:36-40,65-68`只用首/末drive，`TripRepository.kt:343,375-397`、`CreateTripViewModel.kt:92-94,149-150`和`TripTimelineBuilder.kt:24-27`按原字符串min/max/sort。触发手动加末drive结束后1小时充电，charge计入总量/时长却处于显示trip范围之外；不同offset字符串可使真实晚事件排在早事件前。检测器 `TripDetector.kt:72-95` 的gap仅设上限，负gap也被合并。最小修法：用parseIsoInstant建立统一事件序列和有界有效窗；明确手动trip的范围覆盖所有选中leg，或在保存时拒绝区间外leg；负/重叠窗口不能凭上限判断自然连续。

**N09 — canonical km只换英里标签：创建预览与每100mi费用均错。** `CreateTripScreen.kt:194-196`调用不转换的 `UnitFormatter.kt:72-73`；`TripDetailScreen.kt:595-607`以canonical km计算却附偏好mi。触发160.9344km预览显示161mi；费用100/160.9344*100约62.14被标/100mi，真实应100/100mi。最小修法：创建用formatDistanceKm/distanceKmDisplayValue；费用分母先转换成同显示距离或固定标/100km，保持现位置及金额资格。

**N10 — 未知充电类型当AC，未知时长还能产0kW。** `TripDetailScreen.kt:724-731,1312`、`TripTimelineBuilder.kt:65`、`AddLegSheet.kt:128,342`、`CreateTripScreen.kt:321,341`全以“不在DC ID集合”推出AC；`AggregateDao.kt:389-390`的集合只表示已存在fast aggregate，空集可能纯属未同步。触发已知10kWh、type未知/aggregate未到，即可显示AC10kWh；duration0占位时rate分支返回0kW。最小修法：共用ac/dc/unknown三态qualified type和能量/时长配对投影；unknown不参与AC分量或平均，真0与缺时长分开；用现有未知占位不改变布局。

**N11 — 创建保存忽略实际结果且失败无恢复。** `CreateTripViewModel.kt:136-144`忽略createTrip返回nullable ID，未catch/finally；`TripRepository.kt:339-358`只验refs包含drive类型，解析不到仍可插空引用trip。触发预览后leg被隔离/移走、返回null或Room异常，可能导航到不存在trip，或isSaving一直true。最小修法：同事务复核当前scope、存在的合法drive和未被其他trip占用的全部refs；返回明确Success(id,startDate)/failure，VM只在Success导航，finally清loading，失败保留草稿并显示现有错误区域。

**N12 — 年度/PDF只要一份有效样本就把部分合计呈现为全量。** `AnnualReportScreen.kt:241-246,255-296`与`AnnualReportPDFScreen.kt:304-311,322-356`只检查sampleCount>0，未输出coverage、known/partial、paired denominators；monthly对象已有sampleCount但图不消费。触发10段行程仅1段有distance/energy，页面和PDF仍写“总距离/总能量”无覆盖。最小修法：保持已知值，沿现有说明文本/标签输出known subtotal和X/Y覆盖；效率/单位费输出各自paired覆盖，不能因缺其他指标抹已知能量，也不能给未知补0。年度最远日 `StatsRepository.kt:446-448,513` 对全unknown距离empty.sum=0，需同样保持该日unknown而非伪造最多距离0。

**N13 — 年度月图把稀疏月份挤成连续相邻月份，PDF却保留12槽。** `StatsRepository.kt:170-178,188-196`仅生成有记录的月份，`AnnualReportScreen.kt:502-515,525-526,597-603`按index均距画Jan/Dec两点并连线。触发只有1月与12月记录，两点看似邻月，十个月空白消失；同snapshot PDF `:384-404`正确padding12。最小修法：在共享annual projection使用12个显式month slot，unknown与明确无记录0分开；各指标保留自己的sample coverage，用现有图区域画，不变页面顺序。

**N14 — PDF页面预览丢掉合格零/负均耗，最终文件却保留。** `AnnualReportPDFScreen.kt:153-158`用了`>0.0`门，实际PDF`:337-338`用finite门。触发真零净能量/负回收配对效率，预览no_records，而生成文件是0/-Whkm；年度实际页也保留finite。最小修法：预览、年度、PDF共用同一qualified metric presentation，真0/负回收与unknown各自保持，不能使用旧正值条件。

**N15 — 普通导出计数、记录集合、日期窗及分享类型没有同一个冻结请求。** `ExportViewModel.kt:79-108` launch旧count无取消，`:83-84,121-122`无offset年份边界直接Room字符串比较；count DAO仅observed/derived，而alltime getter `DriveSummaryDao.kt:179`、`ChargeSummaryDao.kt:109`是所有非quarantine。`ExportScreen.kt:179`还用当前format构造分享mime。触发incomplete行：preview count10但alltime file11；跨本地年offset边界错归年；rapid setYear旧count覆盖新label；生成CSV期间切JSON最终CSV URI被application/json分享。最小修法：冻结token/scope/car/window/dataType/format，以同一qualified cohort生成count与文件，并把URI+实际MIME一起交付；年份按同一local Instant合同选，不以raw字典序当时间。

**N16 — 输出文件名不绑定生成身份，旧URI可指向后一次内容。** `AnnualReportPDFScreen.kt:440-445`固定同year路径并delete+覆写；`DataExporter.kt:51-53`只有秒级时间。触发同年不同车辆/账号或并发生成后，已授予读权限的旧FileProvider URI对应路径被新内容覆写，旧分享引用不再是当初冻结报告；同秒CSV/JSON同extension也可能碰撞。最小修法：每次生成独立随机ID路径，绑定内部scope/car/year/revision manifest并原子写完成后发布；不复用已经授予URI的目标文件；过期清理需按独立输出生命周期，不能依赖文件名唯一性假设。无需对页面重排。

## 已有测试源码与实际证据

这里只读以下源码；**没有运行或继承“通过”结论**。

| 编号 | 测试入口 | 能证明的设计意图 / 尚未证明 |
|---|---|---|
| T1 | `android/app/src/test/java/com/matelink/domain/TripAggregatorEnergyTest.kt:11-214` | 有allunknown、真0、负回收、加权非平均、overflow、能量独立于距离、记录/已知距离覆盖断言；fixture直接创建DriveSummary，不证明真实TR rehydrate/null/provenance消费者 |
| T2 | `android/app/src/test/java/com/matelink/ui/screens/trips/TripsHistoryRefresherTest.kt:13,47,76` | 验证refresh后用scoped local id、失败读旧scope、没有映射不借remote Int；不覆盖TV实际scope订阅/旧单位结果，也不涵盖Create/Export直读 |
| T3 | `android/app/src/test/java/com/matelink/ui/screens/trips/TripDetailCostConsumerTest.kt:52-91` | manual0、同session能量分母、partial、异车缓存与移动receipt拒绝的断言存在；不证明raw能量flow/leg/CSV用了同一个projection，也未跑真实UI |
| T4 | `android/app/src/test/java/com/matelink/ui/screens/trips/TripScopeInvalidationTest.kt:25,66` | 同remoteId换scope清详情、unavailable恢复的helper断言；不代表Trips/CreateTrip/Export的实际VM具备这个consumer门 |
| T5 | `android/app/src/test/java/com/matelink/ui/screens/trips/TripRouteRequestBudgetTest.kt:14-54` | >12不发请求、12段保持成功结果顺序、并发≤2、scope取消；未验证一个中间drive失败后的ID槽位/天气、receipt身份或旧route cache失效 |
| T6 | `android/app/src/test/java/com/matelink/ui/screens/trips/TripDetailReadOnlyFallbackTest.kt:55,69` | readOnly fallback不自动持久化/删duplicate owner trip；真实Trips调用另一个getTrips仍会cleanup（N06），测试不覆盖该删除入口 |
| T7 | `android/app/src/test/java/com/matelink/reports/AnnualReportYearsTest.kt`；`AnnualReportMetricsTest.kt:14,24,41,62` | 年份候选、显式容量/offset/相邻窗/无drive的standby算式测试；实际AV不给容量，不能据此认定年度standby已打通 |
| T8 | 本轮在`android/app/src/test`与`androidTest`搜索 `AnnualReportPDFViewModel / AnnualReportViewModel / DataExporter / ExportViewModel / CreateTripViewModel / TripsViewModel` 未找到直接引用匹配 | 仅是该关键词检索结果，不是全库无测试断言。没有本轮六路由真实Compose、Room冷重开、PDF生成/渲染、CSV/JSON逐字段及share接收方运行证据 |

## 实施前应保留的验收门

- 六路由各自固定同源码、同scope车辆及同日期窗；合成完整/partial/未知/真0/负能量/跨单位/跨币种fixture应与真实provider证据分层，不读取或外发私人导出。
- 创建须证明草稿→成功事务→重开trip成员/名字/来源不变，失败不导航、不丢草稿；列表读取不触发Owner数据删除。
- 年度页切年→PDF页→冻结snapshot→文件标题/每月值必须同年；PDF实际页数、长字段、字体、图表空/零/负值、source/currency manifest及可见像素需要生成后验证，本文不能提供页数结论。
- 普通导出预览counts与最终CSV/JSON逐条ID/同cohort吻合，明确null与0/数值负号和currency；分享URI/MIME必须属于该次冻结请求，账号切换/并发不能重用旧输出路径或发布旧URI。
- Trip地图要核每drive正确receipt/window、部分段不能假装完整首末，weather保留ID/真实观察时间并与车辆温度来源分开；旧cache重开、点更正、source/window变化需要失效回归。

## 收口

总审优先级校正：09独立反证后，N03保留为P2路由错误（PDF先显示可修改年份的预览，另点生成），N07保留为P2输出合同/可用性差异（不能未经产品合同就宣称导出是有效费用账本或混币种求和）。原上文P1分组为该组初审分级，最终发布清单采用此修正。N06是已有同drive指纹但信息不同的保存旅途才触发的条件性数据保留风险，未触及用户真实库。

已完成 **6路由、104行矩阵** 的源码审计，新增确定根因 **P1 7项、P2 9项**；H1及03的图表/天气共用根因不重复计数。未修改产品、未运行测试/生成导出/访问私人记录。实际PDF页数与可用文件数量均未知；这里的“6页”仅指6个App路由，不是生成PDF的页数。修复与验收均尚未执行，父任务应集中复核后在当前授权范围继续，保持现有布局和参数排列。
