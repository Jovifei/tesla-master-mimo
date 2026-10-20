# MateLink build61 数据真实性、统一计算、离线保存与逐页 UI 整阶段 PLAN

[C2C]
STATE: PLAN
TASK_ID: c2c_8f2d
ITERATION: 0
REVISION: visual-evidence-complete
VERDICT: CHANGES_REQUIRED
APPROVAL_GATE: Jovi 审核整包范围；此文不是实现完成、设备验收或生产授权。

## 1. 基线与证据边界

本方案承接同一任务，不新建 Chat/Codex/cloud task。产品审查基线为 Jovifei/tesla-master-mimo 的固定提交 `49ee6c7423d6d61c50b511eb50f5055316ed691a`，tree `18709c20623acdcc3744e258553af444a38ab573`，parent `59b88974d6e65be303640b3d1b17742b5da0ec11`。手机 build61 / 2.1.42 和 Asia/Shanghai 来自本地审查包；本轮没有重新读取 APK 或手机 Room 原始数据库。

app_mimo 连接器复核仍是 `codex/miniprogram-stage-20261013@92cbf11`，Owner dirty 保留；本轮定向 android HEAD diff 为空，但这不使旧工作树成为手机源码。本方案的产品判断来自 GitHub 固定提交，不来自 E 根旧源码。

已通读本地 `docs/RPT-2026-10-20-app-data-ui-audit-c2c-packet.md` 最新 70 行版本。16 张直接上传的 PNG 已逐张观察实际画面，弥补 read_image 只返回元数据的缺口。含精确地点的仪表盘、行程列表、充电列表、驻车、时间线等原图没有读取或发布；这些页面只采用报告中的实机描述和固定源码，不伪称完成视觉验收。

这个文档分支只新增 PLAN，不修改产品代码、不启动车辆动作、不运行生产迁移、不安装 App、不合并 main。后续产品候选必须有新固定 SHA 和真实同 SHA 验证，不能借用 build61 旧 CI。

### 1.1 必须纠正的原报告概括

- JourVolt `parkedHistoryBoundaryData` 不是把所有字段固定为 null：起止 SOC 从相邻行程边界读取；无 SOC 才是 null。固定为空的是停车能量、功率、温度和 linked_charge 等缺少停车窗口实测证据的字段。不能因此写一个“恢复 SOC”历史脚本。
- `AnnualReportScreen.kt:273-275` 的 `>0` 门槛作用于平均 Wh/km，不是该处总净 kWh；因此应修复合法零/负平均能耗被隐藏，而不是错误声称所有年度负净能量均被禁止。
- 26 与 27 截图时间分别为 11:11:22 和 11:11:56，样本数有 5→27、充电能量相关样本 64→61 的变化；不能把不同截图反算为同一数据库快照。349/429/506、967/919.6、2/9.2 的确需要解释，但不能据此认定重复、丢失或篡改。
- 图 23 的规则曲线与生产合成路径相符，但没有逐点 Room 对照，不能说截图每个点都是假。能证实的是生产代码允许合成点参与历史极值与结论。

## 2. 独立源码发现与优先级

下列文件路径均相对于 `android/app/src/main/java/com/matelink/`，明确写 deploy 或 .github 的除外；行号只对应固定 49ee6c7。

| 编号 | 优先级与判断 | 固定源码依据 | 必须达到的行为 |
|---|---|---|---|
| D1 | P1 已证实：温度历史合成、时间和身份降级 | `ui/screens/temperature/TemperatureTrendViewModel.kt:80-226`；少于 4 点生成 7/15 点、缺值用 24/28°C、统计生成点、DAO 错误变空并清 error、身份解析失败回退 numeric carId、当前值用手机 now 作历史时间 | 删除生产合成路径；观测时间未知不能制造时间；身份解析失败不能读裸 ID；错误、空历史、稀疏历史分别表达 |
| D2 | P1 已证实：时间线时间、日期与排序口径错误；年度/月度还有字符串分组旁路 | `TimelineScreen.kt:297-304`、`TimelineViewModel.kt:115-163`；`AnnualReportMetrics.annualEffectiveCost` 使用 startsWith(year)；`CostViewModel.buildMonthlyCosts` 使用 take(7) | Instant 排序，指定展示 ZoneId 分组与格式化；本地日历边界转换成 UTC 半开区间；原始时间不重写 |
| D3 | P1 已证实：跨集合比值与覆盖数量不等于真实交集 | `domain/analytics/AnalysisSummary.kt:65-195` 用 minOf 两种样本数作效率/比值样本数，直接把已知充入能量与已知行驶能量相除 | 保留已知小计，明确 n/N 和真正配对样本；充入/行驶比不冒充效率，无可比较覆盖时不发布结论 |
| D4 | P1 已证实：费用分裂与非同单单价 | `CostViewModel.recalculate` 独立求 cost/energy 总和，`CostScreen.SummarySection` 再相除；`StatsRepository` quick stats 不读手工覆盖；`ChargeSummaryDao:117-170` 的 SUM/COALESCE；年度还用 effectiveCost/全部充入能量 | 同一 charge key、同一币种和能量口径配对；0 保留、unknown 不补 0；全页消费同一费用投影 |
| D5 | P1 已证实的条件缺陷：云模式可能被自托管配置门挡掉 | `SettingsDataStore.AppSettings.isConfigured` 只看 serverUrl 非空；`TpmsPressureWorker.doWork` 在该值为 false 时直接 success。`SettingsViewModel` 又允许合法云模式使用空自托管 URL | 云会话就绪不能依赖自托管 URL。分别验证“已入队”和“实际接收/准入/落盘”；是否为本手机根因仍待不含 URL/token 的布尔诊断 |
| D6 | P1 源码存在迟到发布/错误身份路径，设备串车未宣称发生 | Temperature、Cost、Efficiency、Range、Parked、Timeline 等加载缺少统一 latest-load 守护；`SentryHistoryViewModel` 切车后旧 Flow 未取消；Battery 首次状态发布缺少后段已有的代际检查 | 账号、来源、稳定车、筛选窗、请求代际任何变化均阻止旧结果显示/写回；先构造真实 VM RED，再最小修复 |
| D7 | P2 已证实：采样平均与边界/极值语义不足 | `DriveDetailMetrics.calculateDriveDetailStats`、`ChargeStatsCalculator.calculateStats`；充电温度 max/min 可回退为平均温度，SOC 取过滤后点列表 first/last | 采样均值与时间加权均值区分；无 extrema 证据时不把均值复制成 max/min；精确边界值与“首末观测”分开 |
| D8 | P2 已证实：充电编辑被能量门控、未知类型可能落入 AC 分组 | `ChargeDetailScreen:523-599` 的费用编辑在 batteryInputKwh?.let 内；`ChargesScreen:847-879` 单独编辑行；`CostViewModel.buildMonthlyCosts` 非 DC 全归 AC | 有合法记录身份即可编辑总价，不依赖能量；类型 AC/DC/UNKNOWN 三态；不能用 56kW 或未命中 DC 集合推断 AC/DC |
| D9 | P2 已证实：续航空态、待机“稳定”、年报零负值表达有问题 | `RangeViewModel.loadRangeData` 会覆盖 recalculate 的 metric reason；`StandbyWindowAnalysis` 仅下降 SOC 窗口入选且以数量/小时判 stable；`AnnualReportScreen` 对平均能耗 >0 才显示 | 区分已有历史与模型不合格；端点跨度不叫连续观测覆盖；零变化不能被当缺失；净能耗零/负合法 |
| D10 | P2 已证实：手机并非已有完整详情副本 | `ParkedDetailViewModel` 只请求远端；`ChargeDetailViewModel` 离线退回 summary 并设置 empty chargePoints；Room 当前 version=21 | 已取得详情持久化、离线可复现、缓存明确不完整状态；不以聚合数据伪造曲线 |
| D11 | 待运行时取证：readiness 全未知的具体原因 | `ReadOnlyReadinessDiagnostics.capture` 先捕获 cached vehicle；verifies 要 capabilityVersion、vehicleUid、来源验证；VM 后面 getCar/resolve 不自动更新此前捕获的 vehicle | 首次捕获为空、旧 capability、UID 不匹配、离线和权限失败分别诊断；不能用实时仪表盘或 archive 证明 Fleet 已接收 |

TPMS 调度调用图的 GitHub code search 返回 incomplete_results，未作为“没有其他调用点”的证据。已读到 BootReceiver 的周期安排及 Application 的 WorkManager 初始化；后续完整 checkout 必须枚举登录、设置、前台、重启和 worker 自续调用并做行为测试，不能只加一个 schedule 调用就结案。

## 3. 16 张截图逐张结论

视觉观察只针对直接上传的这 16 张图。截图中的数字是当时屏幕值，不等于已核实底层全历史。

| 截图 | 应保留的优点 | 最影响理解的问题 | 来源/计算/覆盖与具体调整 |
|---|---|---|---|
| 08-drive-detail-curves | 速度图、真实停走波动和全屏入口清晰 | 速度曲线把速度/功率指标拆开；三项指标落成 2+1，大留白；重复详细曲线按钮 | 速度来自详情样本/汇总；距离÷精确时长与采样均速不是同一个量。先连续排列紧凑指标，再集中曲线；正常字体下三项同排，大字体响应式，不挤单位 |
| 09-drive-detail-lower | -55kW 回收、0.53kWh 以及 100%·568s 证据均有显示 | 功率卡过高；来源和覆盖用接近主指标的粗大文字；1% 易与能量混淆 | 改为 SOC 74%→73%（下降1个百分点）；能量保留 0.53kWh 并标功率积分估算；来源/覆盖移到卡底一行证据说明；回收负号不丢 |
| 17-charge-detail-lower | SOC 上升与功率下降曲线都保留，56/0/41.9kW 中真实零没有被抹去 | “费用/估算费用”和“/kWh”语义弱；41.9未说明采样均值；时间显示“07:03 下午”不顺 | 已充电不能判插枪未充；总价≈¥27.38，来源“按设置费率估算”；单价写“每充入电池kWh费用”，不是桩端电价；时间统一19:03，或完整本地12小时制 |
| 21-tpms-trend | 明确实时值不等于合格历史，未画假曲线 | 空态不能区分没启动、没合格字段、没落盘或读错命名空间；下方原因卡重复“证据不足” | 增加最近一次任务状态、最近合格逐轮观测、已保存数量及有限原因；数据不足只保留一处解释。保持来源/逐轮时间/bar严格准入 |
| 23-temperature-trend | 内外温度颜色和图例直观 | 规律7点与极值被当历史；+3.7°C被称“暴晒升温指数”，还给空调因果解释 | 当前25.4/24.0与历史分区；只画有证据的真实时间点/行程均温序列，二者不混成连续采样。温差只能叫同时间配对温差，不能据此认定暴晒或空调性能 |
| 26-stats-overview | 有观测/估算、样本数，保留统计层级 | 2881km与仅5样本能耗相邻，容易误以为2kWh覆盖全程；地理解析进度抢首屏 | 同 snapshot 发布值与覆盖；“已知净能量小计/能耗配对里程/原始记录数”分别标。地理进度缩成非阻塞次级状态，不拖统计首屏 |
| 27-stats-lower | 驾驶天数、平均时长等使用模式可读 | 10045%以大号关键结论出现；27不是两集合可比较性的证明 | 本阶段主结论不发布该比值；保留充入/行驶小计及各自n/N，可展开查看不可比较原因；不钳成100%、不换公式凑合理值 |
| 31-battery-health-loaded | 明示不支持健康数据，仍展示有值的SOC/续航 | “当前充电”可能只是当前SOC；412.2km看似测得的最大续航；EPA/WLTP标签没有字段标准证据 | 284.4/0.69≈412.2是可解释的满电外推候选，标“按当前SOC估算满电额定续航”；不能当SOH。额定/理想相等本身不证明错误；无标准证据不用EPA/WLTP标签 |
| 33-mileage-loaded | 年柱图、已知净电耗9.15和配对27/506均保留 | 覆盖说明侵入其他指标；单位kWh/100km被拆；10209km“平均/年”容易当实际完整年均 | 覆盖单独占卡底整行，数值/单位/说明分层；年度列表同样改。年化公式当前为已观测里程÷已观测天数×365，标“按已观测时段折算”，不足整年不叫实际年均 |
| 35-long-trips-loaded | 空态有明显创建入口 | 标题“行程/暂无行程”会与已有原始行程混淆；说明像要求重新开车采集 | 标题“组合旅程”，空态“尚未创建组合旅程”；说明“从已有行程和充电记录组合”，按钮“创建组合旅程”；不制造车辆事件 |
| 37-efficiency-loaded | 主值和有效27段突出，图表与时段筛选都存在 | 27段旁边放全量2880.8km；单点图留白大；177 Wh/km与长空态在三列中断行；“越低越好”过度简化 | 主/月/速度组统一能量-里程配对加权；分别显示配对里程/全量里程。单点仍画真实点并标仅1个统计月份，缩小图高；空态用短占位和统一原因；不把下坡净回收排名当驾驶质量 |
| 39-cost-loaded | 已有64次/919.6kWh时不伪造账单总价 | 四个巨大“暂无可分析记录”掩盖已有记录；未解释历史页估算费与本页账单费的区别 | 主卡“已记录费用（含手工）—；价格覆盖0/64”，估算费用独立次级行；有价格无能量仍能计总价，单价只用同单配对；不为填满页面伪造账单 |
| 41-range-loaded | 清楚说明近90天模型不是Tesla官方预测 | 下方“尚未开始采集历史数据”否认已有历史；模型范围和页面范围容易混淆 | 分开“额定续航偏差样本”和“个性化能耗模型样本”；显示缺少端点续航/合格能耗/容量等实际原因；不把有历史但0合格转成未采集 |
| 44-standby-loaded | SOC证据与kWh/平均功率未知分开 | “低置信度”与“稳定结论”冲突；累计72%和157.3h覆盖容易夸大证据 | 显示“所选窗口SOC下降累计72个百分点”；157.3h称有效端点窗口跨度，非连续功率覆盖。零变化窗口保留；能量证据不足就不声称稳定能耗/归因 |
| 48-annual-loaded | 摘要与月柱图有层级，未用假账单填费用 | 9.2kWh没覆盖说明，容易认为覆盖2881km；177Wh/km与其他页单位不同；零/负均值存在代码隐藏门 | 同一年度snapshot、同一资格投影及费用解析；已知净能量小计+n/N；主单位统一，辅助可显示Wh/km；月度unknown断档，真实0显示0，负值不被截掉 |
| 49-data-readiness | 每个步骤独立，不用一个总PASS冒充全部通过 | 三张巨大重复unknown卡占满首屏，没有告诉用户缺哪种证据 | 首屏紧凑六步+首个有效阻塞原因；展开才给来源/时间/证据详情。能力不支持、未返回、过期、身份不匹配、权限不足分开；诊断不得顺带启动配置写操作 |

### 3.1 无上传原图的页面

仪表盘：保留实时SOC、温度、胎压；增加逐字段时间/来源和陈旧状态，不把取屏时间当观测时间。行程历史：保留月柱图及一行四指标“时长/距离/最高速度/平均能耗”；修复 showShortDrivesCharges 真正参与显示谓词，估算标识跟随能耗而不是贴SOC暗示SOC也是估计。充电历史：保留四指标“时长/充入量/总价/SOC”，费用右侧内联编辑见第5节。驻车：保留已知边界、来源和关联充电入口；缺失环境/能量字段合并成一条原因，不用八个大字空格撑屏。时间线：修UTC、跨日分组、同Instant排序，并用统一费用投影。

软件历史：固定源码消费者为 `ui/screens/updates/SoftwareVersionsViewModel.kt`，读 getUpdates 而非当前版本。应确认响应能力、事件时间、完成状态；当前版本不等于完整升级历史。其 LocalDateTime 解析、默认零间隔和按列表位置判断当前版本需纳入时间/缺值测试。哨兵历史：消费者为 `ui/screens/sentry/SentryHistoryViewModel.kt`，读本地日志和状态；“本机无已记录警报”不能写成车辆从未报警。检查旧车 Flow 取消、作用域、首次快照不制造事件；不从现在的状态补造过去事件。

## 4. 统一指标合同

不要新增大而全框架。复用 `MetricState`、`AnalysisCoverage`、`EffectiveChargeCostResolver`、`LatestHistoryLoad` 和现有 raw receipt/qualified projection；新增一个小型、可测试的统计快照/投影入口即可。持久化原值、规范化值、展示值必须区分。

每次分析携带 scope（账号、origin、稳定车辆、来源命名空间）、window、zone、snapshot/revision。每个指标携带 value?、单位、来源/估算标志、有效数/候选数、缺值原因和参与集合。n/N来自真实资格，不以min(n1,n2)代替交集。缺值类别至少区分 empty、unsupported、insufficient、identity_unavailable、permission_required、load_failed、cached_stale；错误不改写为“无记录”。

| 指标 | 公式与资格 |
|---|---|
| 已知净行驶能量 | 合格有限净能量之和；真实0和负值保留。有能量但距离未知仍贡献能量小计，不贡献效率配对 |
| 距离加权能耗 | 对同记录合格有限E及D>0集合S，Wh/km = 1000×ΣE/ΣD；kWh/100km = 100×ΣE/ΣD = Wh/km÷10。主卡、月、速度组、年报一致 |
| 全程均速 | 同单位距离÷精确起止秒×3600，先使用真实时间精度，不以UI四舍五入的9分钟反算 |
| 采样统计 | 原简单平均只能叫采样均值。时间均值使用相同物理量、按时间排序并处理同刻冲突和缺口的有效区间积分÷有效秒数；覆盖不足不能称全程均值。不可跨缺口插值补满 |
| 充电能量/效率 | 电池充入、桩端/AC输入、净行驶能量各自独立。充电效率仅同会话/同窗/同测量边界的合格AC能量平衡；未知充电类型不归AC；正功率不自行证明DC |
| 费用总计/单价 | 手工总价优先；显式免费资格、上游价格、默认费率估算依既有规则解析并保留source。单价集合P要求同一charge key有有效C和同口径E>0、同币种，ΣC(P)/ΣE(P)。估算与账单分栏，C=0有效 |
| 温度与胎压 | 使用真实逐字段observed_at、time_basis、source、原单位及规范化单位。手机轮询时间只可叫read_at；历史行程均温与逐点温度不可混成同一种瞬时历史。温度0/负数合法；null不是0 |
| 驻车/SOC | SOC差是百分点，不能直接当kWh；有容量的估算也必须明确假设和质量，不替代真实能量。输入旧API符号约定在适配处转换，不静默改旧wire合同 |
| 满电续航外推 | 有合法非零SOC及当前额定续航时按range/SOC×100估算，标来源和时间；与新车实测、健康度、EPA/WLTP标准分别表达 |

“充入/行驶能量比”本阶段从主结论撤下，保留两个真实小计与原因。未来即使完整覆盖，也只是选定期间两种总量之比，不是车辆效率，还受期初期末电池存量和非行驶耗电影响。禁止钳值、补容量或用全量距离稀释局部能耗。

## 5. 同一整阶段的实施工作包

### A. 先封真实性、时间和作用域缺陷（P1）

修改 `TemperatureTrendViewModel/Screen.kt` 删除生产合成和伪因果路径；当前温度独立于历史，只有真正带时间的证据进入历史。删除 requireLocalHistoryCarId失败回退裸ID的路径。

修改 `TimelineViewModel/Screen.kt`，复用/扩展 `util` 下已有时间解析工具。同步修 `AnnualReportMetrics.kt`、`CostViewModel.kt`、Stats年度查询边界和 SoftwareVersions 的字符串日期旁路。存储原UTC，按显示时区生成日期/年月，范围使用[start,end)。

在 Temperature、Cost、Efficiency、Range、Mileage、Timeline、Parked、Annual、Battery、SoftwareVersions、Sentry 的真实加载点复用 `LatestHistoryLoad`/请求代际与 scope 验证。先捕获作用域、取消前次任务、在每次挂起返回和发布/写回前确认仍有效；CancellationException重抛。切账号/车清展示投影但保留数据库，不展示上一辆车的缓存。旧请求的finally不能结束新请求loading。

验收：真实VM迟到响应RED→GREEN；时间跨日跨年与不同偏移同Instant一致；0/1/3点温度不会变成7/15点。

### B. 一个统计快照和统一资格投影（P1）

修改 `StatsRepository.kt`、`UnifiedHistoryRepository.kt`、`domain/analytics/AnalysisHistoryRepository.kt`、`AnalysisSummary.kt`、`AnalysisCoverage.kt`、`WeightedEfficiency.kt`、`MileageEvidence.kt` 及相关 DAO。不能只改formatter。

值、覆盖和已知/估算数量来自同一作用域、同一筛选窗、同一数据库快照；现有 getStats 串行分别计算quick/deep/coverage应避免把不同写入时刻拼成一张卡。网络请求不持有Room事务；事务只读摘要/资格/必要索引，不能加载全车原始路线。记录原始数、可分析数、当前列表数和未结束/短程/隔离/缺单位等排除原因，不能强迫所有页面计数相等。

同一冻结快照在本地生成ID差集，导出脱敏行：opaque record key、source分类、质量、边界/字段可用布尔、各消费者是否入选、排除代码；不上传VIN、账号、坐标、地点、token或真实轨迹。把349/429/506及967/919.6差异逐项解释，含同步进度；不得靠删除数据对齐。

`DrivesViewModel.applyFiltersAndUpdateState` 真正使用短行程设置；筛选只作用于列表/明确的统计范围，不改变原始保存和驻车邻接判断。`EfficiencyViewModel` 月度和速度组与主卡使用同一配对加权，不再算术平均。`AnnualReportScreen` 和图表保留零/负值；未知月不是零柱。

### C. 费用与充电合同贯通（P1/P2）

复用 `EffectiveChargeCostResolver.kt`，修改 `ChargeCostOverrideStore.kt`、`ChargeSummaryDao.kt`、`ChargesViewModel/Screen.kt`、`ChargeDetailViewModel/Screen.kt`、`CostViewModel/Screen.kt`、`StatsRepository.kt`、`MileageViewModel.kt`、`TimelineViewModel.kt`、`AnnualReportViewModel/Metrics/Screen.kt`；组合旅程费用消费者同样复核。

手工总价保存后，详情、列表、费用、统计、里程、时间线、年度和组合旅程按同一key重新投影，不能把手工值写进原API receipt。持久化币种/来源/修改版本；旧手工值币种证据不明时保留但不猜币种或跨币种求和。有效账单/手工小计和估算小计分别展示。

充电总价编辑不以batteryInputKwh是否存在为条件。AC/DC/UNKNOWN三态贯通列表、费用分组、聚合；`isFastCharger=false`而缺证据不能自动变成AC。修 `ChargeStatsCalculator` 的伪极值和点序/SOC边界；时间加权统计不替换原充电能量物理量。

不能按时长、能量unknown/0或少数零功率点删除“插枪未充”；原记录保留。只有完整、同窗、无冲突的明确证据才可给“仅连接/未观察到充入”分类；本次24.02kWh、56→97%的样本不在此类。

### D. 真实观测、详情持久化和可恢复同步（P1/P2）

修改 `TpmsPressureWorker.kt`、`TpmsObservationEvidence.kt`、`TpmsHistoryRepository.kt`、`MateLinkApplication.kt`/现有启动协调入口、`BootReceiver.kt` 和模式就绪判断。不要把AppSettings.isConfigured简单改为恒true；按云会话/自托管各自授权来源判断。使用唯一、可幂等协调的周期工作，登录/恢复配置/前台/重启能够恢复，注销阻止旧scope网络调用。周期与立即检查使用不会互相替代或堆积的唯一工作名；permission拒绝不禁止合格历史保存，也不假装通知已送达。

前台状态响应与后台接收复用同一观测准入与去重。Fleet和TeslaMate来源保持独立；缺少真实逐字段时间/单位/来源时不放宽校验。重放同一collector时间不增点、不重新报警，逐轮新时间分别落盘，legacy-unverified保留且不进入趋势/告警。仅已有用户授权的提醒规则继续有效，不擅自启用新提醒。

新增建议文件：`data/local/entity/TemperatureObservation.kt`、对应DAO、`data/repository/TemperatureHistoryRepository.kt`；`HistoryDetailReceipt`/DAO与 `ParkedDetailRepository`。这些是拟新增，不冒称现有文件。接入现有DI、真实ViewModel消费者及同步代码，不交付孤立helper。详情存不可变receipt、完整度/游标、原单位、精确window和独立合格投影；离线优先读取合法已取得内容，再有界刷新；失败显示旧缓存和原因，不清曲线。

修改 `StatsDatabase.kt` 与 `di/DatabaseModule.kt`，从已读v21提出增量v22候选（实施前确认没有并行schema升级）；保持旧表/旧行/旧receipt，禁止destructive fallback。先保存已获取数据，再做明确范围的有界同步；既有历史全量补取与旧7113点TPMS回填不是默认授权。

服务器只提出和测试候选：现有接收层记录真实时间/单位/来源，提供按scope/时间/游标分页观测及已存在历史详情。`deploy/jourvolt-dev-mock/parked_history_bounds.go` 的边界兼容路径保留；没有停车窗口证据就继续返回null，不能挪用当前温度或相邻行程均温。新增能力需隔离PG、旧客户端兼容、拒绝越权和取消/资源测试；生产DDL、桥升级、订阅变化均另有门。

### E. 紧凑布局与可解释空态（P2）

修改前述16屏对应Screen，以及 `DrivesScreen.kt`、`ChargesScreen.kt`、`ParkedDetailScreen.kt`、`TripsScreen.kt` 和字符串资源。保留现有蓝绿冷灰体系、月柱图、曲线和真实零/负能耗，不做换皮或删除内容规避问题。

行程详情顺序：概要→速度/功率紧凑指标组→SOC/净能量→一行来源/覆盖→集中曲线区。正常宽度/字体三项同排，约18–20sp数字、12–14sp单位/次级说明作为候选设计值，不把PNG像素反推成dp。大字体按测量结果响应式，避免来源文字被当第四个大指标。保留一个明显“详细曲线”入口和每张图的全屏操作，不在每卡重复入口。

充电列表：在现有费用cell内用 `Row(至少48dp高且整格可点击)`，左侧价格/来源，右侧16–18dp编辑图标；触控目标是整格至少48×48dp，不是强塞一个48dp宽图标挤掉数字。移除独占一行的左下编辑按钮，避免点击同时触发父卡导航。保留一行四指标，不把整个列表改成2×2。极大字号不能靠缩到不可读或截掉数字解决；可为该指标带提供可访问的横向滚动后备，默认设备尺寸不得依赖它。

驻车空字段：已知边界/时长保留，环境/能量缺值合并为“此驻车窗口未保存温度/功率样本”，展开可看逐字段原因。已知SOC=0照常显示，不把0样本覆盖误当无停车记录。

统一empty组件：数值槽短占位“—”，理由使用正文/次级行；有64条充电但0条价格时不能四次重复大字“暂无可分析记录”。单点图保留点和真实时间，缩减留白；不生成假折线。数值、单位、覆盖分层，kWh/100km单位内部不拆行。

数据状态：六步紧凑展示+首个真实阻塞，区分返回类别和来源；捕获缓存身份为空而后续验证成功时，应受scope守护地重取证据，而非永久沿用空session。检查 `DataReadinessViewModel.load` 中自动configure路径：诊断浏览不触发配置写入；既有明确授权的配对/恢复流程由独立入口承接，不能把查看诊断当新同意。

### F. 全链路回归、固定源码与终审（整包收口）

A–E不是若干可独立宣布交付的小片段；整包源代码接线、回归和截图完成后一次回传。现有CI在 `.github/workflows/history-memory-review.yml`，Android入口 `tools/energy-stage/android-ci.sh`。该脚本只在隔离GitHub Actions运行，含临时runner环境转换，禁止在Owner机器直接套用。

授权实施端在确认分配branch/base后推进；如需新owned产品分支，使用现有workflow覆盖的codex/apk-*命名或在候选中明确修正触发范围，不能“提交成功但测试job全部skip”。保留完整Go/PG/race/TLS/Web及Android检查，不以旧PR绿色替代新fixedSHA。新增Room/Compose instrumented tests必须在隔离模拟器实际执行；仅assemble不能证明这些测试通过。

本地验证端接收GitHub固定SHA，独立测试/构建/签名，安装另过本次人工门；不向Owner dirty工作树逐文件派发业务修补。复审仍在同一c2c_8f2d，不创建另一任务。

## 6. 手机/云端/冷归档保存矩阵

“手机可用性主副本”不是证据权威，也不等于已经拿到云端全部历史。报告提到的分层ADR是提案，本轮未读取其原文，不把它视为已实施系统。

| 实体 | 手机保留 | 服务器/私有冷归档 | 风险与恢复 |
|---|---|---|---|
| 行程/充电摘要 | 已取得原始receipt、nullable边界、资格投影、来源/单位/修订；按scope独立 | 可恢复原始会话/分页索引 | 不完整会话不能因Room旧非空列限制而永久丢失；新增receipt容器保留，不伪造结束边界 |
| 详情曲线 | 已下载完整点或明确标记的分页块；receipt hash、point count、complete/cursor；展示降采样是可重建派生物 | 有界热索引、完整冷归档 | 原始点和降采样不可混淆；事务完成后才标complete；断电/磁盘满不发布半包为完整 |
| 温度/TPMS | 逐字段/逐轮value、observed_at、received_at、time_basis、unit、source；按车去重 | 真实源观测及恢复游标 | 不造时间、不复写legacy资格；7/30天是查询窗，不自动变成删历史授权 |
| 驻车窗口 | scope+两侧会话key+精确窗+来源修订+边界/温度/能量各自证据 | 有证据的停车观测或边界合同 | 邻接关系变化/中间有行程要使旧投影失效，但原receipt保留；不跨窗嫁接温度 |
| 手工费用 | 金额、币种证据、charge稳定key、修改版本、原始值独立 | 后续多端同步候选 | 旧键歧义保留待确认；冲突不能被晚到API覆盖，不能静默猜币种 |
| 组合旅程/历史档案 | 用户创建关系、原始archive和来源隔离 | 可恢复用户数据/私有备份 | 不因原始行程过滤或ID修正丢用户组合关系；archive不能证明Fleet首事件 |
| 通知/采集游标 | durable pending/handled、首次导入水位、权限状态、去重/迟到ID证据 | 必要事件receipt | 权限拒绝不消费pending；重启不重发旧导入；自然收到仍需人证 |
| 软件/哨兵 | 有证据的时间事件和本地检测日志，区分检测时间与车辆事件时间 | 有能力时的历史事件 | 当前版本/当前状态不冒充全历史；未知覆盖时“未记录”不同于“0次” |

新增详情预取的候选默认预算：并发2、摘要每批不超过200、观测页不超过1000点、详情增量软预算256MiB；若现有API上限更小则遵守较小值。预算是待隔离压测的设计，不是现有能力或生产授权。超过软预算停止额外预取并提示覆盖，保留已有历史、手工价和队列；不能删唯一副本来维持“完整副本”口号。若磁盘无法写入，诚实显示未保存与可重试状态。

冷归档需要manifest/hash、加密/访问范围、原始计数和抽样恢复验证。建立可验证的冷副本及明确删除授权前，不自动清理原始记录。完整历史与有限手机空间的矛盾必须通过分层及显式覆盖状态解决，不能默默退化为只保摘要。

## 7. RED→GREEN 与负例矩阵

新增测试名称为建议，不声称已经存在或运行。优先测试真实VM/repository/Room/Compose调用链，辅助纯函数测试不能替代消费者回归。

| 测试组 | RED输入/故障注入 | GREEN验收 |
|---|---|---|
| TemperatureEvidence | 0/1/3真实点、DAO失败、状态失败、timestamp缺失、负温/0°C、缺单位、A车迟到 | 无合成点/假极值；保留真实稀疏点；错误与空态不同；身份失败不回退裸ID；当前时间不冒充observed_at |
| ZoneAndWindow | 2026-10-20T14:30Z与22:30+08同Instant；18:30Z跨上海次日；跨月跨年；DST重复/缺失小时 | 显示/分组/过滤一致，排序按Instant，存储原串不改；年度StartsWith旁路移除 |
| AtomicSnapshot | 在quick stats和coverage之间插入新的详情资格/费用更新 | 一次发布的值/样本数/覆盖属于同一revision；下一revision整包更新，不拼接 |
| IdSetReconciliation | 短程、未知距离/单位、未结束、隔离、同ID不同source、分页重复/晚ID | 原始/可分析/可见数差异均有原因，不能删除历史凑数；短程设置实际有效 |
| WeightedMetrics | 1km/1kWh+99km/9kWh；-0.5kWh/5km；2kWh但距离缺失；E与D在不同ID | 前例100Wh/km而非约545.45；负例-100Wh/km保留；能量小计可知但配对效率未知；n是交集而非min |
| ChargeCostPairing | A有¥20但E未知，B有10kWh但价格未知；C有¥0和5kWh；不同币种；手工12.34/清除 | A+B单价未知而非¥2/kWh；C单价0；手工变更所有消费者和重启后相同；账单与估算不混 |
| ChargeEvidence | 类型未知但功率56；电池充入已知而AC输入未知；只有均温；无序/同刻冲突点；中途零功率 | 不猜DC/AC、效率保持未知；不把均温复制为最高最低；SOC首末观测不冒充精确端点；不删除会话 |
| ParkedEvidence | 相邻边界SOC0/缺失；中间短程/隔离/开口行程；关联充电未知；温度仅在窗外 | 合法0保留；不安全邻接拒绝；窗外不借值；本地已有同窗缓存离线可读并标陈旧 |
| CloudWorkerReadiness | 云会话有效+self-host URL空；自托管未配置；重复前台/重启；立即任务并发周期任务 | 云模式不被serverUrl门跳过；未授权不发请求；唯一工作不无限堆积；不修改网络配置 |
| TpmsAdmissionAndRecovery | 同一2.8bar receipt重复/重启；逐轮不同时间；缺单位/来源；legacy碰撞；未来时间 | 不重复增点/报警、不覆盖旧证据；自然新时间才新增；legacy保留但不进入趋势；真实0值按合同处理 |
| NotificationSafety | 首次历史导入、权限拒绝后恢复、晚到稳定ID、切账号、重启恢复 | 旧导入不弹自然完成通知；pending不丢、去重；不跨用户投递；只在隔离fixture触发模拟 |
| LatestLoad | A→B切车、同numeric ID换账号/来源、7→30天快速切换、取消后远端仍返回、旧Flow再次emit | 所有列入VM发布和保存均拒绝旧代际；新loading不被旧finally关闭；取消不变成错误或空历史 |
| ReadinessCategories | cached vehicle初始为空但后续验证成功；旧capability、UID不符、permission_required、archive-only | 精确原因/独立步骤；不从archive/实时值推Fleet PASS；查看诊断零configure/wake/import调用 |
| RoomRestartMigration | v21数据库含历史/手工价/legacyTPMS/队列；迁移中断、事务中断、弱null覆盖、同ID换窗 | 新候选schema可恢复；旧行/原receipt守恒；重开值/资格/费用/曲线一致；不destructive重建 |
| LayoutAndAccessibility | 16对应页面；320/360/411dp；字体1.0/1.3/2.0；长数字/中文英文/深浅色；离线/错误/稀疏 | 无重叠、数值单位完整、48dp触控、编辑不触发导航、列表四指标与月柱图保留，所有新增说明可读 |
| ResourcesAndCompatibility | 大历史/大详情、慢分页、429、断网、取消、磁盘满；旧API/旧客户端/隔离PG不同schema | 有界分页/并发/写入，恢复幂等，旧receipt不丢；缺功能返回unknown/unsupported；实际报告耗时、内存、DB体积，不编造性能PASS |

设备只读对账在同车、同来源、同窗、同同步revision完成后再采集；自然数据未到不强行制造。必须重新输出同页面截图，追加充电总价右侧内联编辑、未知能量仍能编辑、离线详情、单点温度/TPMS、true0/负能耗、快速切车前后画面。

## 8. CI、安装与回滚门

源码/隔离CI：固定候选执行现有Debug+Release单元测试、lint、assemble/R8；读取JUnit真实fail/error/skip与进程退出码，不能只看包装器exit0。新增Room/Compose实际模拟器测试另有job。后端变更执行真实Go、vet、build、隔离PG、race，以及现有TLS/Web/兼容回归；不接生产数据库或真实provider。需要保留Android时间测试的显式zone注入，不只依赖runner默认上海时区。

本地独立验证：取完整GitHub SHA/tree/parent和已验证源码，重建并核验签名/R8/构建号；检查Owner dirty前后不变。旧生产API与本次候选源码并不等同，不部署候选去“验证”。

手机安装：本轮不安装、不卸载、不清数据。后续需要Jovi确认本阶段安装门，并已有可审阅备份后，才进行同签保数据升级；原安装时间、登录、历史、费用和队列需前后核对。真实登录、Tesla同意、通知权限、自然TPMS/行程/充电、多用户验收与代码/模拟器分开记录。

回滚：分支源码可以revert但不盲合main；Room升级后旧APK未必能打开高版本数据库，不能把“装回旧APK”写成已验证回滚。候选应准备兼容新schema的回滚构建，或已验证的APK+数据库/偏好/密钥关联恢复方案；新增未同步手工数据先保全。恢复/覆盖原设备数据仍需明确批准。服务器仅隔离候选DDL/回滚演练，生产DDL、桥升级、订阅改动和历史回填保持独立门；旧两次SOC修复不重复，旧六SOC和7113点TPMS不自动回填。

## 9. 完成交付与同任务回传模板

整阶段源代码完成条件：D1–D10已接线修复或用实际证据否定，并对D11提供分类而非一概unknown；16图布局逐项对照；全部消费者同snapshot口径解释；详情/观测/手工价重启保持；真实同SHA测试无未解释失败。生产自然数据/人类门可OPEN，但必须有明确缺哪项证据，不能被SOURCE_APPROVED吞掉。

同一任务EXECUTED回传：

```
[C2C]
STATE: EXECUTED
TASK_ID: c2c_8f2d
ITERATION: <next>
BASE_SHA: 49ee6c7423d6d61c50b511eb50f5055316ed691a
BRANCH: <assigned-owned-branch>
HEAD_SHA: <full sha>
TREE_SHA: <full tree>
PARENT_SHA: <full parent>
PLAN_COMPARISON: D1-D11 / A-F / 16 screenshots
CI: <same-SHA run IDs, job IDs, exact results, artifacts>
LOCAL: <independent tests/build/signature; NOT_RUN where absent>
DEVICE: <installed SHA/version or NOT_INSTALLED; screenshots and observations>
PERSISTENCE: <migration/reopen/rollback evidence>
HUMAN_NATURAL_GATES: <explicit open gates>
OWNER_DIRTY: <preserved evidence>
```

配套文档应包括本PLAN逐项完成对照、固定SHA源码与CI回执、隐私安全的ID差集原因统计、字段/单位/来源/时间/资格合同、Room迁移与恢复矩阵、16图前后对比以及未通过的人工/自然数据门。GitHub为源码与证据交接桥，原私有地点图不上传。后续审查将独立读取diff、可读execution_output、实际CI及新截图，不依据执行方“全通过”一句话宣布验收。
