# 数据链路修复授权清单（尚未实施）

对应本轮Android上线审查；工作根为E盘独立`matelink-release-data-audit-20261026`。本文件是可审阅的修复范围，**不表示已获新增产品修改/发布授权**。已确认的错值、漏读和字段绑定优先；不以填默认数消除未知。

## 不变约束

- 保持页面结构、组件顺序、参数位置、尺寸、字体、间距、颜色。Screen仅允许修正取值/formatter/路由数据参数，必要的估计/单位文案使用原有位置，不新增或删除参数格。
- 不覆盖原目录或其他任务；不修改共享core.worktree。新增修复在隔离树，先用本轮954文件作before基线，再按精确文件审diff。
- 每一批先补真实消费者/Room/wire正反例，实际看到失败后最小修复；保留真实0、负净回收、raw receipt、用户历史/配置，旧无scope数据不猜归属。
- 产品源码授权不自动包含提交、推送、手机安装、生产配置/DDL/历史修复、Tesla授权和车辆操作。那些阶段仍分别有证据及权限门。

## F1 登录、车辆选择与快照单位

主文件：`data/repository/SettingsRepository.kt`、`ui/screens/auth/TeslaLoginViewModel.kt`、`ui/navigation/StartDestinationViewModel.kt`、`NavGraph.kt`、`ui/screens/dashboard/DashboardViewModel.kt`、`data/local/VehicleStatusStore.kt`；数据绑定涉及DashboardScreen时只动数字/状态，不改布局。

- 在当前已验证车单内恢复无效selection，并在导航前写回；保持服务端/客户端ownership fail-closed，不能简单拿任何缓存ID。
- 登录后失效重定向不依赖初始startDestination；退出比较身份事务/代次，不能因同账号token轮换放弃清理，也不能清掉ABA后的新会话。
- 缓存原数值与同一响应的units/source/observedAt；旧无单位缓存保留但不贴猜测单位；HUD缺值不当0，freshness来源词表和休眠状态独立。
- 验证：首车ID非1、换账号旧ID不存在、token rotation/ABA、非公制缓存重开、合法零与缺速/电流。

## F2 充电类型、费用、当前会话与历史合并

主文件：`domain/analytics/ChargeCostProjection.kt`、`data/sync/DetailAggregateMapper.kt`、`data/local/dao/AggregateDao.kt`、`ui/screens/charges/ChargesViewModel.kt`、`ChargeDetailViewModel.kt`、`CurrentChargeViewModel.kt`、`CurrentChargeScreen.kt`、`data/repository/UnifiedHistoryRepository.kt`、`deploy/jourvolt-dev-mock/telemetry_http.go`。

- 已知单次账单/有效手工金额优先，车辆级免费标志不能覆盖任意DC或历史会话；免费资格需会话与权益证明，不足时未知/明确估算。
- 三态AC/DC/UNKNOWN贯通聚合、筛选、图、地图与旅途；processed且非DC不等于AC。复用已有字段可证明的模式，不为UNKNOWN伪造相位。
- 历史合并在读取旧字段前先校验source instance/记录/精确窗口；保存真正收到的raw receipt，避免预合并令后续window guard失效。
- 当前会话保留真实start、起点SOC和既有曲线；快照只增强当前字段。累计量以会话起点→观察时刻的合格counter delta为准，不把客户端now或最新observedAt当会话起点。
- 验证：本轮12→0及UNKNOWN→AC负例转绿、跨窗复用ID、离线费用口径、当前charge状态/曲线及迟到换身份。

## F3 行程、曲线、路线和驻车端点

主文件：`ui/screens/drives/DrivesViewModel.kt`、`DriveDetailScreen.kt`、`DriveCurvesScreen.kt`、`DriveDetailMetrics.kt`、`ParkedDetailViewModel.kt`、相关路线/曲线formatter。

- 无能量采样不能以默认145Wh/km冒充本行程实际能耗；若允许理论参考，须有明确模型来源/估计标识，且不并入观察统计。合格SOC容量估计与真实0/负回收分别验。
- raw preferred速度和canonical km/h分开；曲线使用timestamp/value配对并保留断档，不把压缩值数组套全部时间轴；A→B→A保留回到A，地址不互填。
- 驻车读nullable receipt而非Room primitive SOC占位；server数据校验pair、scope/source/window，隔离中间行程不能从完整邻接序列消失。
- 当前冻结树已无驻车地图，本批不恢复或删除布局；历史D11不作当前修复。驻车网络详情持久化属单独P2，若实施须增量保存/迁移测试，不删旧数据。
- 验证：本轮往返与无采样负例、mph一次转换、缺点/重复/断点、nullable SOC与明确0、跨源/隔离邻接。

## F4 我的分析与本地采样消费者

主文件：`ui/screens/vampire/VampireViewModel.kt`及路由数据绑定、`ui/screens/battery/BatteryViewModel.kt`、`domain/analytics/BatteryTrend.kt`、`ui/screens/temperature/TemperatureTrendViewModel.kt`、`ui/screens/settings/SettingsViewModel.kt`、`data/local/SettingsDataStore.kt`、`data/sync/TpmsPressureWorker.kt`、`domain/analytics/StandbyHistoryDerivation.kt`。

- Vampire采用路由与已验证身份的同一车辆，换scope清投影、晚请求拒绝。
- 可用SOC缺失不代填主要SOC；趋势SOC/range必须同端点；规范km用正确formatter，当前温度保留原温标。
- 温差用同刻内外配对样本，不用独立极值拼差；没有空调运行证据不作“表现良好”结论。模型样本/候选覆盖和建模阶段清楚区分。
- 旧TPMS目标/上下限按verified local key保存读取；旧未归属profile保留，用户重新保存后绑定，不自动猜迁移。新变化规则已正确映射，不重写正确路径。
- standby真实0不丢失，近期列表先降序再截取；不从缺能量推0。

## F5 统计、地理和时间线的实际剩余问题

主文件：`data/repository/StatsRepository.kt`、`data/local/dao/AggregateDao.kt`、`ui/screens/stats/CountriesVisitedViewModel.kt`、`RegionsVisitedViewModel.kt`、对应两个Screen的formatter、`ui/screens/timeline/TimelineScreen.kt`。

- Geo复用captured scope/request generation及同窗口cohort；字段可用性不因primitive0消失；地图和头部用同quality集合，坐标有限/范围验证；本地年份不做raw ISO字典比较。
- canonical国家/地区km转换后再显示mi；费用/距离分母文案明确，不能修一个并不存在的“100mi误贴”。
- 深入分类型能量带sampleCount/nullable，未知不当0。保留主Stats已正确的Room事务、UNKNOWN模式仲裁和配对费用分母。
- Timeline能量生产链已资格化，原P1已撤回，不改这条正确链；只补合法durationMin的现有位置formatter回退和必要加载体验。
- 验证：7项SQL条件转绿并补真实Room/VM消费者；sort/日期边界/英制/同数字ID scope负例。

## F6 长途、报告、导出与保存记录保护

主文件：`ui/screens/trips/CreateTripViewModel.kt`、`TripsViewModel.kt`、`TripDetailViewModel.kt`、`domain/TripRepository.kt`、`ui/screens/reports/ExportViewModel.kt`、`data/export/DataExporter.kt`、`ui/navigation/NavGraph.kt`年度到PDF的路由参数。

- CreateTrip/Export先解析verified local key与冻结请求，不能用remote正ID查scoped Room；保存/导出成功后再导航/分享，失败保留草稿并结束loading。
- 浏览不删除不同信息的用户旅途；beta重复清理仅限可证明完全相同的自动记录，实际历史迁移需备份与独立授权。N06为已有同drive重复状态才触发，不声称所有浏览都会删正常旅途。
- 路段保留driveId/source/window/revision与失败槽位；天气用同drive/time配对，未采样段不拉直线冒充实际路线。
- 年度所选year传到PDF；预览与文件用同snapshot。导出需明确raw与effective字段合同、nullable/币种/覆盖，文件路径每次独立且已分享URI不可被后续内容覆盖。
- 验证：不同charge/名称但同drive记录不丢、历史月份/年份选择、空/隔离leg、失败路段/天气、同年不同车并发输出。

## F7 采集与展示边界

- Cloud空serverUrl不应被自托管配置门挡住已授权数据处理；未知sentry_mode不当off清session。
- 不靠更多Fleet轮询填空：消费既有合格遥测/缓存，并核验提供方是否真的提供告警脉冲。App本机观察事件不冒充完整车机历史。
- WhereWasI/Vehicle3d未开放实现留backlog，不为本次发布擅开入口或改布局。
- 上游还留有真实详情可以按接口补取保存；从未采集的旧速度/温度/能量/路线不能重建成真实曲线。缺字段应有具体原因，不能统一填0或当前值。

## 完成门

每批实际RED→GREEN、正确0/null/负值和scope/window负例、真实Room重开；最终同一冻结源码的双变体/lint/R8/API35及真实UI同记录对账。只有源码复审、候选包身份/签名、实机/提供方门分别有证据，才能讨论发布。修复授权不等于上线验收。
