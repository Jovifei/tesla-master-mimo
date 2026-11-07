# 05 统计、国家/地区、费用、里程、时间线

父任务审查；实际根`E:\project\_worktrees\matelink-release-data-audit-20261026`。第二波原统计子任务未真正启动，因此由父任务直接补做。覆盖6注册且有入口的路由：Stats、CountriesVisited、RegionsVisited、Cost、Mileage、Timeline，包含里程年/月/日层级。只读取冻结候选，没有修改产品或布局。

`S`=ui/screens；`R`=data/repository；`D`=data/local/dao；`A`=domain/analytics；均在`android/app/src/main/java/com/matelink/`下。SOURCE_OK是源码可追，不等于实机/提供方通过。下表一行可能列出使用同一证据合同的多个明确位置；不把行数当独立实车参数数量。

## 共用真实链路

Stats主路径`StatsViewModel.requestStats:391以后`→`StatsRepository.getStats:250`→`beginStatsRead:239`取得scope和verified local key，`computeStats:273–310`从一次Room事务读取摘要、详情聚合、手工金额，再对同一本地年份cohort计算。每次await/后台返回复核current，年度快照另见06。**这条主路径不应因旧文档被误报为未隔离。**

Countries/Regions是另一组旧DAO聚合路径（R/StatsRepository:712–795），没有复用主StatsRead事务和同一VM身份门。Cost使用AnalysisHistoryRepository.loadCached/load并保留字段资格；Mileage使用UnifiedHistoryRepository及本次已修复的VM原子投影；Timeline承接UHR已资格化的数据，不能仅因字段名像raw就判定旁路。最终反证校正见10-statistics-review.md。

## 字段矩阵

### Stats

| ID/可见位置 | 实际来源与公式/单位 | 保存、作用域、UI位置 | 判定与空态/证据 |
|---|---|---|---|
| ST01 全部/年度筛选 | YearFilter、getAvailableYears；同local日year cohort | StatsViewModel:391以后；StatsRepository:273–365；StatsScreen:263–274,1108以后 | SOURCE_OK主路径，旧请求filter/proof复核；不是SQL截年捷径 |
| ST02 无记录/收集中/不支持 | historyMetadata+hasData+coverage | StatsScreen:244–259；StatsViewModel requestStats | SOURCE_OK分状态；真实provider进度UNVERIFIED |
| ST03 记录来源数量 | summary.sourceRecordCount | StatsScreen:762–767；AnalysisSummary.buildAnalysisSummary | SOURCE_OK记录基数，不代表每字段有观测 |
| ST04 合格总距离 | coverage.qualifiedDistanceTotalKm；已知非负km求和 | R/StatsRepository:273–310；StatsScreen:718–721 | SOURCE_OK，用formatDistanceKm转换；缺样本不填0 |
| ST05 合格平均能耗 | 同drive合格净kWh/合格distance配对→Wh/km | A/WeightedEfficiency；StatsScreen:724、1356 | SOURCE_OK，真0/负回收可保留，estimated单列证据 |
| ST06 驾驶能量 | qualified netEnergy总量，有估算样本则ESTIMATED | AnalysisSummary:61以后；StatsScreen:736–737 | SOURCE_OK字段级证据，未知非0 |
| ST07 充入电池能量 | batteryInputKwh合格样本和，kWh/MWh | coverage及QuickStats；StatsScreen:742–743,1396 | SOURCE_OK；不能当电表AC输入 |
| ST08 已记录/手工总费用 | projectStatsChargeCosts同source/session/currency的有效金额 | R/StatsRepository:365以后；StatsScreen:754–755 | SOURCE_OK金额投影；货币符号重载边界S06待验证 |
| ST09 平均行程距离 | 合格距离和/距离样本数 | AnalysisSummary:101以后；StatsScreen:804–805 | SOURCE_OK不用总行程数稀释已知样本 |
| ST10 平均每驾驶日距离 | 合格距离和/相同已知distance本地日数 | AnalysisSummary；StatsScreen:881–882 | SOURCE_OK公式；需自然历史数据 |
| ST11 平均每次充入 | 合格batteryInput总量/对应样本数 | AnalysisSummary；StatsScreen:810–811 | SOURCE_OK，未知记录不当0样本 |
| ST12 费用/距离 | totalCost/规范km×100 | StatsScreen:1309–1314,1362–1363；AnalysisSummary.costPer100Km | S02(P2)说明问题：实际资源literal为“费用/距离”，未明确每100km；不是已证100mi误贴 |
| ST13 充电损耗百分比 | 同会话qualifiedAcEnergyBalance，(AC输入-电池输入)/AC输入×100 | AnalysisSummary:101以后；StatsScreen结论区 | SOURCE_OK，输入>0且>=电池能量；不借全部充电/驾驶比例 |
| ST14 充电/驾驶总能量比例 | 两组独立活动，无闭合能量平衡 | AnalysisSummary明确MetricState.Unavailable | SOURCE_OK拒绝无资格比值，不为填满而算 |
| ST15 总行程次数 | 选中cohort行数 | QuickStats.totalDrives；StatsScreen:1326 | SOURCE_OK记录计数；0时overview用no_records而非新观测 |
| ST16 驾驶天数 | cohort有资格日期的本地日去重 | QuickStats.totalDrivingDays；StatsScreen:1332 | SOURCE_OK；缺日期样本未知 |
| ST17 平均行程时长 | 合法时长样本平均 | QuickStats.avgDriveMinutes；StatsScreen结论区 | SOURCE_OK，时长不是结束时间占位 |
| ST18 最大速度 | 合格speedMaxKmh，formatSpeedKmh | QuickStats.maxSpeedKmh；StatsScreen:909、1498 | SOURCE_OK规范km/h→展示单位；上游速度点UNVERIFIED |
| ST19 充电次数/平均时长/每kWh价格 | 选中合格charges；known平均；同会话cost-energy对 | QuickStats；StatsScreen:1391–1411、900 | SOURCE_OK paired分母，不用全部能量稀释已知费用 |
| ST20 距离覆盖 | distanceSampleCount/driveRecordCount×100 | AnalysisCoverage；StatsScreen:938–940 | SOURCE_OK，分母0未知/收集中，不伪100% |
| ST21 驾驶能量覆盖 | driveEnergySampleCount/driveRecordCount | StatsScreen:944–946 | SOURCE_OK；估算/观察分开说明 |
| ST22 充入能量覆盖 | chargeEnergySampleCount/chargeRecordCount | StatsScreen:956–958 | SOURCE_OK |
| ST23 费用覆盖 | chargeCostSampleCount/chargeRecordCount | StatsScreen:962–964 | SOURCE_OK同币种资格 |
| ST24 AC输入覆盖 | chargeEnergyUsedSampleCount/chargeRecordCount | StatsScreen:974–976 | SOURCE_OK，缺AC测点保持未知 |
| ST25 损耗配对覆盖 | chargeLossSampleCount/chargeRecordCount | StatsScreen:980–982 | SOURCE_OK，不把DC充入当AC输入 |
| ST26 观测期起止/天数 | 合格日期min/max与local日范围 | StatsScreen:986–1000；AnalysisCoverage | SOURCE_OK，范围不证明持续完整采样 |
| ST27 推荐标题/值/证据与建议 | buildRecommendationEvidence→buildRecommendations，明确采样与heuristic | StatsRepository:365–415；StatsScreen:467–475,595–637 | SOURCE_OK来源可追；不是车辆诊断/实车结论，缺样本保持collecting |
| ST28 快速/深入完成度及日志 | SyncManager progress、scoped metadata、geocode progress | StatsViewModel:254以后；StatsScreen:257–270及同步区 | SOURCE_OK本机处理状态；不等于提供方全量完整 |
| ST29 最远行程/日期/跳详情 | qualified record的distance、driveId、startDate | StatsScreen:1495–1496；QuickStats.longestDrive | SOURCE_OK字段资格主路径；日期take(10)标签有原时区边界S04 |
| ST30 最高速度行程/日期 | qualified record maxSpeed，km/h规范格式 | StatsScreen:1498–1499 | SOURCE_OK；不能由均速估峰值 |
| ST31 最节能行程/能耗/日期 | 合格同记录效率，允许净回收 | StatsScreen:1501–1502 | SOURCE_OK主路径；不把list固定145当观察，参03-D01 |
| ST32 最长连续驾驶/最忙日/国家数 | local日连续/次数、已解析startCountryCode distinct | StatsScreen:1504–1511；Quick/DeepStats | SOURCE_OK计算；国家仅已解析起点近似，SOURCE_DATA_MISSING全足迹 |
| ST33 最大充电SOC增/驾驶SOC降/起止SOC | nullable qualified历史端点 | StatsScreen:1516–1520；QuickStats battery records | SOURCE_OK只在端点证据充分时出现；不拿当前SOC补历史 |
| ST34 最大单次充入/峰值功率/日期 | qualifiedChargeEnergy/详情峰值，kWh/kW | StatsScreen:1522–1526；StatsRepository:548–611 | SOURCE_OK，但功率峰依赖采样密度，不声称物理全时峰 |
| ST35 最贵充电/最贵每kWh/日期 | 有效手工/记录费用；cost/正battery energy | StatsScreen:1528–1536；StatsChargeCostProjection | SOURCE_OK记录对应金额；若只免费记录可无付费纪录卡 |
| ST36 最高海拔/最多爬升/热冷行程与充电 | nullable detail aggregates，max/min，海拔m→ft | StatsScreen:1544以后；StatsRepository:548–611 | SOURCE_DATA_MISSING详细点；源码路径可追，云配置未见海拔字段 |
| ST37 车外/车内最高最低温度四值 | detail nullable max/min | StatsScreen:1791–1837；DetailAggregateMapper | SOURCE_OK值源；历史温度的单位必须同receipt，跨单位缓存参H02 |
| ST38 AC/DC能量/占比/次数/未知模式数 | 主StatsChargeMode正确保留UNKNOWN；合格能量分别和 | StatsRepository:529–611；StatsScreen:1855–1975 | **BUG S03**：类型已知但能量全未知时empty.sum=0，仍画0kWh；未知mode计数本身正确 |
| ST39 summary证据/样本/未知说明 | MetricState OBSERVED/DERIVED/ESTIMATED/Collecting | StatsScreen:1045–1106 | SOURCE_OK明确层级，不能把source-ok视为实车通过 |

### CountriesVisited

| ID/可见位置 | 实际来源与公式/单位 | 保存、作用域、UI位置 | 判定与空态/证据 |
|---|---|---|---|
| CT01 国家列表/加载/排序 | StatsRepo.getCountriesVisited→AggregateDao SQL→CountryRecord | CVM:54–104；CountriesVisitedScreen:130–209 | **BUG S01**无scope/请求代次/取消隔离；不是无Room数据 |
| CT02 国旗/国家名/跳地区 | ISO code→本地化名与emoji，传countryCode+carId+year | Screen:203–209,247–256；StatsRepo:833 | SOURCE_OK元数据；非法code与外部geocoder可用性UNVERIFIED |
| CT03 行程次数 | 已geocode起点的observed/derived行COUNT | AggregateDao:475–543；Screen:269–277 | SOURCE_OK有限基数，未geocode行不覆盖，非全足迹 |
| CT04 总距离 | SUM(drives_summary.distance)，字段canonical km | CountryRecord.totalDistanceKm；Screen:303 | **BUG S02/S03**英制误贴单位；原始非空列旁路nullable receipt |
| CT05 充入电量/MWh | 对应country charge rows SUM energyAdded | AggregateDao左连接；Screen:311–314 | **BUG S03**未知/未配对字段被SUM/COALESCE成0，未复用qualifiedBatteryInput |
| CT06 充电次数 | 对应已geocode的charges COUNT | Screen:325–326；CountryRecord.chargeCount | SOURCE_OK记录计数；没有charge rows的0不等于每次energy有观察 |
| CT07 首次访问日期 | MIN drive.startDate | CountryRecord.firstVisitDate；Screen country card | S04原字符串排序/日期截取；仅起点活动，非首次物理到访证明 |
| CT08 最近访问日期 | MAX drive.startDate | CountryRecord.lastVisitDate；Screen country card | 同上；日期解析显示可追，coverage不足须说明 |
| CT09 距离/能量/次数/字母/首次排序 | sortBy同一CountryRecord属性 | CountriesVisitedViewModel:97–110 | SOURCE_OK排序数学；S01旧集合可被迟到结果替换 |
| CT10 空列表/错误/单位 | Room/地理聚合，状态单位另取getCarStatus | CVM:59–80；Screen空态 | SOURCE_DATA_MISSING geocode/历史；S01错误仍保留旧列表、没有新身份清屏 |

### RegionsVisited

| ID/可见位置 | 实际来源与公式/单位 | 保存、作用域、UI位置 | 判定与空态/证据 |
|---|---|---|---|
| RG01 国家头部/区域列表 | 依次读countries、regions、charge locations、drive locations | RegionsVM:114–168；Screen:197–212 | **BUG S01**4次读取可跨scope/Room修订，未捕获统一请求 |
| RG02 国家头部次数/日期/距离/充电量/次数 | CountryRecord同CT03–08 | Screen:320–403 | 同S02/S03/S04；字段逐一沿用国家证据，非重新实车通过 |
| RG03 区域名/国别 | Drive aggregate.startRegionName/startCountryCode | StatsRepo:728–738,845；Screen:787–798 | SOURCE_OK已解析起点元数据；跨境途中未单独观察地区不能补 |
| RG04 区域行程次数 | SQL分组COUNT observed/derived | AggregateDao:545–612；Screen:798–807 | SOURCE_OK计数，保留缺地理样本说明 |
| RG05 区域距离 | SQL SUM canonical distance | Screen:823 | **BUG S02/S03**错贴mi/字段未知丢失 |
| RG06 区域充入与次数 | charge aggregate region左连接SUM/COUNT | Screen:830–844 | **BUG S03**能量资格与0/null；次数源可追 |
| RG07 首次/最近访问 | SQL MIN/MAX startDate字符串 | 区域卡formatter与排序 | S04，offset跨日、混格式不以字典序证明时间 |
| RG08 地图充电/行程切换 | mapViewMode，本地选项 | RegionsVM:184–185；Screen:204 | SOURCE_OK交互，本身不是新数据 |
| RG09 地图年份/可用年份 | date.take(4)→Int，内存过滤 | RegionsVM:71–98,140–143,200；Screen:431–444 | **BUG S04**未按local Instant年，与主Stats不一致 |
| RG10 地图AC/DC/全部 | ChargeLocation.isDcCharge布尔 | RegionsVM:82–86,188；StatsRepo:855–863 | 参02-CHG-P1-01，unknown被false当AC |
| RG11 充电marker坐标/地址 | AggregateDao.getChargeLocationsForCountry；date只用于过滤，能量/ID虽在DTO但未显示 | DAO:808–837；Screen:474–478地图分支 | **BUG S03**混合有效/隔离记录时可画quarantined点；仅隔离国家会先被空态挡住 |
| RG12 行程marker坐标/地址 | aggregate.startLat/Lon；距离/ID虽在DTO但当前map不渲染 | DAO:841–870；StatsRepo:866；Screen:474–478 | **BUG S03**同混合状态；不把未渲染DTO列计可见字段 |
| RG13 国家边界/可见地图范围 | GeocodingRepository公开boundary cache+当前markers | RegionsVM:160–161；Screen地图 | SOURCE_OK公共几何源；S01迟到boundary可套旧country，SDK/真机UNVERIFIED |
| RG14 排序/空态/错误 | 原regions按first/name/count/distance/energy/charges | RegionsVM:174–220；Screen:189–197 | S01、S04；未知源应区分未解析而非真实无到访 |

### Cost

| ID/可见位置 | 实际来源与公式/单位 | 保存、作用域、UI位置 | 判定与空态/证据 |
|---|---|---|---|
| CO01 默认/自定义窗口 | AnalysisWindow按本地date从同charges投影选取 | CostVM:304–338；CostScreen selector | SOURCE_OK，同scope本地窗口，不改UI标签暗扩窗 |
| CO02 已记录/手动总额 | effective source/currency/session合格known prices和 | CostVM:329–368；ChargeCostTotals:12–41；CostScreen:173以后 | SOURCE_OK，manual0保留；不是包含所有估算的账本 |
| CO03 平均每次已知费用 | knownTotal/known price count | calculateCostTotals.averageKnownCost；SummarySection | SOURCE_OK，分母为价格样本，不是全部次数 |
| CO04 缺价默认单价估算额 | 仅price缺失且batteryInput有效的records：rate×energy和 | ChargeCostTotals:25–28；Screen cost_default_rate_estimate | SOURCE_OK明确估算与覆盖，不是实际支付；峰平谷未接SYS-01 |
| CO05 充入电池能量 | qualified batteryInput有限>=0和 | CostVM totals；SummarySection energy card | SOURCE_OK，不能叫电表AC输入 |
| CO06 有效每电池kWh价格 | 同session price和positive battery energy配对再sum/sum | ChargeCostTotals:22–36；Screen cost_paired_battery_unit | SOURCE_OK，真0金额可得0，缺分母未知 |
| CO07 费用/能量/配对/估算覆盖数 | 各字段有效record基数 | CostVM:365–370；SummarySection footnotes | SOURCE_OK显式覆盖，不能把补价算观察 |
| CO08 当前币种/来源说明 | 一次settings.currency+scope+override snapshot | CostVM:200–238；CostScreen currency assumption | SOURCE_OK不兑换旧金额；陌生币种不参与合计 |
| CO09 月费用AC/DC/未知堆叠 | buildMonthlyCosts本地YearMonth+effective known prices | CostVM:72–94；CostScreen:130–159 | SOURCE_OK未知类型独立，未用processed非DC强推AC；月label跨年可读性需实机 |
| CO10 地点费用排行/次数 | buildLocationCosts同known prices按address分组sum/count | CostVM:96以后；Screen LocationCard | SOURCE_OK；缺地址用未知组，不造地名 |
| CO11 缓存banner/加载/失败 | AnalysisRepo.loadCached→load；cachedPublished，same-scope失败保留 | CostVM:171–279；Screen:cache/header | SOURCE_OK，已有CostRoomFirstViewModelTest真实Room消费者；本轮未重跑 |
| CO12 无配对说明/暂无值 | cost/energy各coverage判定，独立字段保留 | CostScreen:SummarySection | SOURCE_OK，已知单项不因缺另一项全抹掉；不强填0 |

### Mileage（含年/月/日）

| ID/可见位置 | 实际来源与公式/单位 | 保存、作用域、UI位置 | 判定与空态/证据 |
|---|---|---|---|
| MI01 历年总距离 | TimedDrive.observedDistanceKm合格和 | MileageVM computeYearAggregation；Screen:276,1002 | SOURCE_OK，formatDistanceKm；不拿unknown当观测0显示 |
| MI02 平均每年距离/首次记录/说明天数 | earliest local drive date到now的年化距离 | VM year aggregation；Screen:970以后dialog | SOURCE_OK所定义年化，不是“有数据年份平均”；未来日期/极稀疏自然覆盖UNVERIFIED |
| MI03 行程次数 | 当前允许记录数 | VM YearAggregation；Screen:1024附近 | SOURCE_OK计数与逐字段sampleCount分开 |
| MI04 已知净能量 | 合格netEnergy和，真0/负回收保留 | VM observedEnergyKwh；Screen:1049 | SOURCE_OK，energySampleCount0为— |
| MI05 平均能耗/覆盖说明 | buildMileageEvidence配对能量/距离→Wh/km，formatTelemetryEfficiency | Screen:938,1034；A/MileageEvidence | SOURCE_OK不以全里程稀释；明确样本均值 |
| MI06 费用总计/币种 | scoped local key/source/session/currency→manual/record projection和 | VM publish、projectMileageChargeCost:后半段；Screen:1043 | SOURCE_OK，读取失败费用未知但里程仍出；不借旧金额 |
| MI07 年柱图数值/tooltip | getYearlyChartData只发已观察distance年份 | VM charts；Screen:339–348 | SOURCE_OKkm规范转展示，未知不画假零 |
| MI08 年卡年号/距离/次数/能耗/费用 | 同YearAggregation对象 | Screen:359–438 | SOURCE_OK同投影；不存在数据年不凭空造记录 |
| MI09 月层合计/平均每月/次数/能耗/费用 | computeMonthAggregation按local YearMonth；avgMonthly以有记录月份计 | VM aggregateByMonth；Screen:499–524,601–691 | SOURCE_OK分母定义应读既有info；旧选择/旧投影返回被拒绝 |
| MI10 月柱图/数值 | getMonthlyChartData已知distance月份 | Screen:581–590 | SOURCE_OK有限值；不是缺月=实际0 |
| MI11 月卡及日层头部 | 同MonthlyMileage/Evidence；日期按local月 | Screen:719–938 | SOURCE_OK，距离均值分母为已知distance样本数 |
| MI12 日柱图/tooltip | 选择month内有真实distance的daily slot | VM getDailyChartData；Screen:1241–1250 | SOURCE_OK只发有观测日；不存在日不伪造 |
| MI13 日卡/日汇总/平均每次/次数/能量/费用 | DailyMileage同样按合格单项，配对效率 | Screen:1261–1366,1499–1598 | SOURCE_OK；UI不把price null打印0 |
| MI14 单日行程地点/距离/SOC/详情入口 | 当前daily.drives、nullable receipt | Screen:1640以后 | SOURCE_OK基础值，来源共享03数据合同；未采SOC不可补当前值 |
| MI15 offline缓存/STALE banner/刷新 | UnifiedRepo.loadCached/read proof→网络；same identity守护 | 当前VM两发布、Screen缓存提示 | SOURCE_OK当前修复；父上一阶段88聚焦/1356为继承，08不是这组的新测试 |
| MI16 切年/月/日与迟到聚合 | selectionGeneration+projectionVersion+current proof；整快照year/month/day同次发布 | 当前VM ensureSelectionContext、publish、aggregateByMonth/Day | SOURCE_OK已读源码与33新增回归来源；不重新登记旧缺口 |

### Timeline

| ID/可见位置 | 实际来源与公式/单位 | 保存、作用域、UI位置 | 判定与空态/证据 |
|---|---|---|---|
| TL01 来源与加载 | settings.currentCarId→UHR.load→verifiedReadContext | TimelineVM:112–159；Screen:40–93 | SOURCE_OK身份/代次主要门；P2网络前置未loadCached，弱网等已有Room |
| TL02 日期分组/时刻/顺序 | parseIsoInstant→system zone；按Instant降序、type/id稳定tie | VM:180–211；Screen:117–120,183 | SOURCE_OK，不再把UTC原串当本地小时 |
| TL03 行程起/终地址 | nullable DTO start/end；缺单侧只显示已知侧 | VM:212–229；Screen:203–208 | SOURCE_OK，没有把一个地址复制到两端 |
| TL04 行程距离 | DriveData.distanceKm规范km | VM:223；Screen:217–222 | SOURCE_OK，UI明确km；TimelineUnitsConsumerTest源码存在 |
| TL05 行程时长 | DTO durationStr和durationMin均装入event | VM:224–225；Screen:224只消费str | **BUG S05(P2)**：有合法分钟但str缺时，已知时长被隐藏 |
| TL06 行程结束SOC/详情ID | nullable endBatteryLevel和driveId | VM:226–228；Screen:231–237,click | SOURCE_OK来源；数值范围与历史证据应沿03准入，不能借当前SOC |
| TL07 充电地点/记录ID/跳转 | charge.address、chargeId | VM:232–251；Screen:251–255,click | SOURCE_OK，不造地址 |
| TL08 充入能量 | UHR→toAnalysisChargeData/withQualifiedEnergy已把chargeEnergyAdded替换为batteryInputKwh | UHR:329、HistorySummaryMapper:174、ChargeEnergyBinding:36–38；VM:243；Screen:262–265 | SOURCE_OK生产链已资格化，撤回仅凭字段名判断的S05能量P1 |
| TL09 充电费用/币种 | projectEffectiveChargeCost+bound override | VM:145–150,244–247；Screen:269–273 | SOURCE_OK有效费用；无qualified值不输出数字 |
| TL10 充电时长 | durationStr/Min保存，UI仅Str | VM:248–249；Screen:276 | 同S05(P2)，有min缺str不展示 |
| TL11 无记录/缓存告警/异常 | history sync warning本地化；events空与loading分支 | VM:153–175；Screen:69–79 | SOURCE_OK不伪造事件；当前没有freshness独立banner与Room先出，P2体验缺口 |

## 确认的问题与最小非布局修法

### S01 [P1，条件性] Countries/Regions缺请求与身份栅栏

CountriesVisitedViewModel:54–80、RegionsVisitedViewModel:114–168各自直接launch，不取消旧generation，不监听scope或在每次await/子units/boundary完成后复核identity；异常时copy只改error/loading，旧countries/regions仍在。StatsRepository:712–795这些旧helper只在每次调用开始resolve local ID，没有主StatsRead的前后验证和同次Room事务。因此在页面仍存活且切账号/来源、同数字车ID重绑定、或重复load时，旧集合/单位/公开边界仍可覆盖新状态。这里不声称每次正常返回导航都会复用旧VM，也未操作实际账号。

最小修法：复用StatsRead的captured scope/local car/generation，国家/地区/地图取得一个geo snapshot，await返回复核，换scope清投影；保留现有筛选/布局。

### S02 [P2] 规范单位与原API展示单位混用

国家/地区DTO属性明确`totalDistanceKm`（StatsRepository:833–853，CarStats.kt Country/RegionRecord），DAO求的是summary规范distance。CountriesVisitedScreen:303与RegionsVisitedScreen:382/823却用不转换的`UnitFormatter.formatDistance`。当值160.9344km且单位mi，会贴约161mi，而非100mi。

StatsScreen:1309–1314确实计算cost/规范km×100，但实际资源values/strings.xml:1246、values-zh:1237是literal“Cost/Distance”“费用/距离”，没有占位符；传入mi参数不造成100mi标签。因此撤回本页原“100mi误贴”的断言，保留P2分母说明不明确。国家/地区距离formatter错误仍成立；06-N09的其他消费者按其自身真实标签另验，不能把本项反证扩展到其他页面。

### S03 [P2，发布前应修] 地理SQL与深入能量小计丢失字段资格

AggregateDao:475–612国家/地区SQL直接SUM非空`distance/energyAdded`，不消费nullable receipt/energy_contract；即使过滤了记录级observed/derived，也不能证明该行每个字段都有值。现代writer已按资格写入energy，但未知在UHR:601/835及Sync:407仍写primitive0，因此这里主要是丢失可用性，不是声称现代路径必泄漏拒绝的正raw能量。Map查询:808–869缺质量过滤，可在同国家仍有有效记录的混合状态下画出quarantined点；只有隔离记录时country/region空态会先挡住地图。StatsRepository:855–863还把`isFastCharger=false`当地图AC（共用02类型根因）。

主Stats的模式仲裁529–544已正确保留UNKNOWN，不能误报它也一律“非DC=AC”；但是buildDeepStats.energy:578–583对无合格能量的集合.sum=0，AcDcRatioCard:1855–1878仅看processed数量就显示0kWh。已知类型但缺能量的会话不能由空和变成实测0。

修法：Geo用同一qualified projection和cohort、拒绝quarantine及非法坐标，分类型保留sample count/nullable值；真实0保留，缺证据用现有未知值，不改布局。

### S04 [P2] 地理页面年份与首次/最近排序未用同一本地Instant合同

StatsRepository:717–718/733–734/761–762/788–789用无offset年份字符串让Room直接比较原ISO字符串；RegionsVM:75–77/97/140–143再用date.take(4)。例如UTC 12月31日晚在上海已是1月1日，会与主Stats/年度页的local year不同。首访/末访SQL MIN/MAX和sortBy raw date同样不能证明跨offset的先后。复用LocalDayBoundaries+parseIsoInstant/localHistoryDate和同窗口geo snapshot，避免两套年份算法。

### S05 [P2] Timeline有值时长隐藏；撤回能量旁路判断

独立复核确认UHR:329生产数据经HistorySummaryMapper:174和ChargeEnergyBinding:36–38，已把chargeEnergyAdded覆盖为合格batteryInputKwh；该消费者源码链成立，原P1撤回。当前直接API充电页是否旁路必须按02自己的生产链判断，不能互相套用。

两类event虽存durationMin，Screen:224/276仅渲染durationStr；HistorySummaryMapper:64/83等fallback保留分钟但不补字符串，withSafeHistoryDisplay/withQualifiedEnergy也不构造时长，因此这里“分钟有值却隐藏”仍成立。修法用现有formatter回退合法分钟，缺值不补0。

### S06 [P2，需场景验证] 主Stats金额快照未携带币种标签

StatsRepository:275每次计算读取current currency，CarStats无currencyCode字段；StatsViewModel:175–179只首次读currency symbol，activate又沿用previous symbol。若同VM在设置/来源变化后重新计算新的币种金额，可存在金额与标签修订不一致；本轮未跑该导航/设置场景，不升级为已发生的财务错误。最小修法让价格快照包含所选币种，金额与符号同次发布；不借后来的设置重贴旧金额。

## 已核对的正常路径、源缺口与测试层级

1. 主Stats采用同scope Room事务、字段coverage、同会话cost-energy配对和local年；不能据地理旧helper的问题一并否定主统计。主summary的观察/推导/估算/收集中状态分开。
2. Cost明确把“已记录/手动费用”和“缺价记录按默认费率估算”分栏，paired价格分母使用同记录正能量；不把两个分栏差异自动当Bug。时间窗口和失败保留缓存已实现。分时配置未接入另记07-SYS01。
3. Mileage的最新Room-first、费用读取降级、原子层级投影和异步选择保护已读并确认；本轮没有再把旧版缺口当新问题，也没有因为只改VM要求改布局。
4. 国家/地区只基于已解析行程起点及关联充电位置，不能证明完整经过的国家；没有详情/海拔/温度点、没有AC侧能量、没有价格/币种/真实新事件时，无法为填满每格制造数据。
5. 已读测试源码：StatsReadScopeAndYearTest、StatsMonthlyDriveAggregationTest、StatsMonthlyChargeAggregationTest、StatsChargeCostProjectionTest、StatsWindowPolicyTest、CostRoomFirstViewModelTest、CostBreakdownTest、MileageRoomFirstRegressionTest、MileageRoomPersistenceTest、MileageAggregationConsumerTest、MileageChargeCostConsumerTest、MileageEvidenceTest、TimelineTimezoneConsumerTest、TimelineUnitsConsumerTest。存在覆盖不等于本轮执行；Geo scope/单位/raw energy旁路仍缺针对真实消费者的负例。

本组没有新增运行测试、构建、设备/提供方调用。父任务08有另外11项实际审查条件（8fail/3pass），不能把它当本组所有字段通过。本矩阵源码层审查完成，实车与同候选完整验证仍开放。
