# 03 — Android 行程、行程详情、曲线、驻车详情与实际地图消费者

审计日期：2026-10-26。审计者：`/root/audit_driving`。工作目录：`E:\project\tesla_master\app_mimo`。基线由父任务确认：`cdfe0b26` 加 Owner 现有 dirty；本文是本次读取到的工作区源码审计，不能外推为该 commit 的纯净树、当前安装包、真实车辆或线上提供方已通过。

范围严格限定为只读源码与测试源码，唯一写入是本文。未修改源码、测试、布局、资源、构建配置；未构建、运行测试、使用设备、访问网络或读取用户私有记录。父任务已尝试图工具且审批不可用，按其指示使用限定范围 `rg` 和带行号源码读取；未重试图工具。已阅读父/本项目 AGENTS 和 `tasks/lessons.md`。项目 Hub 和总计划由父任务统一记录。

判定：`SOURCE_OK` 仅表示所述绑定/公式在源码上成立；`BUG` 有确定源码分支和可构造触发条件；`SOURCE_DATA_MISSING` 表示当前来源/协议/持久化没有该证明；`UNVERIFIED` 表示本轮没有运行消费者、Room、UI、provider 或实机验证。以下各行的运行证据均为 **UNVERIFIED**；存在的测试仅为“已读测试源码”，不声称通过。历史“1356/variant pass”不属于本轮页面或提供方验收。

## 检查完成项

- [x] 行程筛选、摘要、四类柱图、行程卡、驻车卡逐字段追踪。
- [x] 详情所有实际可见指标、唯一内联速度图、天气表、地图和跳转追踪。
- [x] 独立曲线页 KPI、五类曲线、时间/单位/缺口/非法值核对。
- [x] 驻车边界、多个充电会话、SOC pp/h、kWh/h、覆盖、温度、地图、空态核对。
- [x] DTO、同响应长度单位、能量资格、Room summary/evidence、detail receipt/chunk、冷重开和迟到结果源码核对。
- [x] 给出确定缺陷、最小不改布局修法与待验证边界；不以源码结论代替安装包验收。

## 路径索引

下文短名均是以下工作区相对路径；行号均为本次读取时的 1-based 源码行号。

| 短名 | 完整源码路径（相对 `E:\project\tesla_master\app_mimo`） |
|---|---|
| DS / DV | `android/app/src/main/java/com/matelink/ui/screens/drives/DrivesScreen.kt` / `DrivesViewModel.kt` |
| DD / DM / DX | `android/app/src/main/java/com/matelink/ui/screens/drives/DriveDetailScreen.kt` / `DriveDetailViewModel.kt` / `DriveDetailMetrics.kt` |
| DC | `android/app/src/main/java/com/matelink/ui/screens/drives/DriveCurvesScreen.kt` |
| PS / PV / PB | `android/app/src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt` / `ParkedDetailViewModel.kt` / `ParkedChargeBreakdown.kt` |
| DTO / DU / AD | `android/app/src/main/java/com/matelink/data/api/models/DriveModels.kt` / `DriveDistanceUnits.kt` / `AdapterModels.kt` |
| API / REP | `android/app/src/main/java/com/matelink/data/api/TeslaMateApi.kt` / `android/app/src/main/java/com/matelink/data/repository/TeslamateRepository.kt` |
| SUM / DAO | `android/app/src/main/java/com/matelink/data/local/entity/DriveSummary.kt` / `android/app/src/main/java/com/matelink/data/local/dao/DriveSummaryDao.kt` |
| HM / EB / ER | `android/app/src/main/java/com/matelink/domain/analytics/HistorySummaryMapper.kt` / `DriveEnergyBinding.kt` / `DriveEnergyResolver.kt` |
| HP / HR / HC / HD | `android/app/src/main/java/com/matelink/data/repository/HistoryDetailPersistence.kt` / `HistoryDetailRepository.kt` / `HistoryDetailCodec.kt` / `android/app/src/main/java/com/matelink/data/local/dao/HistoryDetailDao.kt` |
| AR / AN / AG / AT | `android/app/src/main/java/com/matelink/ui/components/AmapRouteView.kt` / `android/app/src/main/java/com/matelink/ui/screens/map/AmapMapView.kt` / `android/app/src/main/java/com/matelink/ui/components/AmapMapGate.kt` / `android/app/src/main/java/com/matelink/data/repository/AmapCoordinateTransformer.kt` |
| LC / CD | `android/app/src/main/java/com/matelink/ui/components/OptimizedLineChart.kt` / `ChartDrawUtils.kt` |
| WC / WR / WA | `android/app/src/main/java/com/matelink/ui/screens/drives/WeatherAlongTheWayCard.kt` / `android/app/src/main/java/com/matelink/data/repository/WeatherRepository.kt` / `android/app/src/main/java/com/matelink/data/api/OpenMeteoApi.kt` |
| UF | `android/app/src/main/java/com/matelink/domain/model/UnitFormatter.kt` |
| CLOUD / CM / CP | `deploy/jourvolt-dev-mock/telemetry_service.go` / `history_sample_metrics.go` / `parked_history_bounds.go` |
| HOME | `deploy/teslamate-home-docker/adapter/cmd/adapter/main.go` |

## 公共获取、资格与持久化链

1. 列表：`API:98` 附近的 drives GET → `DriveModels.kt:7-49` → `DU:26-30` 将**同响应** `units.unit_of_length` 放入本地 `lengthUnit`。缺键使用 DTO 默认 km；显式 null/未知变为空字符串并拒绝长度分析（DU:22-30）。`DV:512-530` 先读经 cached history context 验证的 Room；`DV:553-554` 再经 UnifiedHistoryRepository 拉取并合并，`DV:580-585,616-648` 核对账号/来源/车辆上下文并拦截迟到 Room/地址结果。`DV:674-720` 统一应用日期、距离、短行程和质量资格。
2. 原始证据与展示分开：`SUM:17,46-47,66` 的主键是 `(carId,driveId)`，SOC 列是非空 Int，但 `apiEvidence` 保留可空 wire 字段。`HM:54-72,108-134` 从 receipt 重建可空值并验证本地 detail projection 的车、来源和窗口；不要直接把 primitive 列当观测。`EB:226-232` 在未知情况下确实把长度/速度/SOC 列填 0，因此所有消费者必须走 receipt projection。
3. 详情：`API:108-112` → `REP:621-646` → `DU:29-30` → `DM:113-173`。远端成功仅在 summary 已存在且 identity 匹配后由 `HP:75-95` 在 Room 事务里合并 summary 和点；无 summary 不接受“凭详情建立历史身份”。`HR:36-71,85-91` identity 含 local history car、稳定身份/连接来源/server fingerprint、kind/record、source/instance/source record、精确 start/end instant。
4. 点持久化：`HC:51-80,87-139` 保留 DTO 的原始点顺序、null、真零、负功率、重复时间；分块序号/首点索引/点数/字节数/SHA 校验。`HD:24-29` 与 `HR:234-267` 有界寻找最近五份完整候选，损坏回执不被当成空轨迹成功。**保持原始点顺序是归档正确性，不等于图表可直接按序号画成真实时间轴。**
5. 完整性：`HR:94-102,168-197` 区分 response complete / provider total verified；`HP:92` 当前没有传入提供方总点数，正常只能证明响应已存，不能证明整条自然行程点已全采。`DM:152-161,191-197` 发布对应 warning。DTO 没有消费 detail 分页或总点数合同，不能把 HTTP 2xx 及缓存 receipt 当 provider 全覆盖。
6. 能量：`DTO:63-75,160-163` 与 `EB:17-40` 使显式 unknown/不支持合同保持未知，不借旧 scalar 兜底；有限负净能量和真零保留。`ER:77-95,123-146` 处理 API 和有覆盖功率积分，`DX:40-53` 再把低于 85% 的 power estimate 排除出显示。效率统一为合格净 kWh / 正的规范化 km × 1000 Wh/km，再除 10 显示 kWh/100km；不因缺距离抹掉独立已知能量。

## 页面—参数矩阵

每行“持久化”指其实际所用持久来源，而非拟议能力。测试列中的 T 编号见末尾测试证据表。

### DrivesScreen

| 位置/参数 | UI → VM/来源；公式、单位 | 持久化、null/空/资格 | 判定 / 测试源码证据 |
|---|---|---|---|
| 日期按钮、定制起止、向前加载 | DS:268-280,507-570 → DV:395-451,468-471；近7天为今天至前6天；自定义闭日历区间按本地 zone 起点含/末日后一天不含（DV:95-99） | SavedStateHandle 保存筛选；DV:512-530 每窗口重新读本地，更多扩展7天并切 CUSTOM；DV:684,692,702 给列表/摘要/图同窗过滤 | SOURCE_OK；提供方跨午夜/时区/自然历史完整性 UNVERIFIED，T1 |
| 距离筛选 | DS:572-598 → DV:87-91；按规范化 km `[0,10)`, `[10,100)`, `[100,+∞)` | 未知距离仅 ALL 保留；长度同响应 mi 转 km 后筛，不借后来 status | SOURCE_OK；英里文案阈值取近似6/60而实际6.214/62.137，边界文案精度问题，T1 |
| 摘要总次数 | DS:635-641 → DV:700-706,873-885；`eligibleRoutes.size` | 与当前短行程/日期/距离筛选一致；observed/derived 才入统计；空时 UI 显示 unavailable，而非0；非隔离 incomplete 卡可能可见但不入统计 | SOURCE_OK（是合格统计次数，非所有可见非隔离卡数）；T1 |
| 摘要总距离、总时长、最大速度 | DS:643-665 → DV:876-892；有限非负 distanceKm 求和，非负分钟 Long 求和并防 Int overflow；最大规范化 speedKmh | 每指标独立 sample count，部分合计保留且 DS:673-680 标覆盖；未知不补0；summary 单位按偏好从 km/kmh 转显示 | SOURCE_OK；T1 |
| 四类日/周/月柱图，点选值/轴单位 | DS:887-1105 → DV:724-841,897-907；COUNT/TIME/DISTANCE/TOP_SPEED 均来自同合格集合 | count 空桶0可表达记录数；未知距离/时长/最大速度不画假零柱；已知真零保留；覆盖比例同时出现在桶提示和图下方 | SOURCE_OK；T1。缺日无柱压缩日历间距属表示边界，不能看成等距真实时间线 |
| 行程时间/起止地址 | DS:725-747 → DV:645-653,846-869；startDate 格式化，两侧地址各自独立 nullable；缺地址仅用各自坐标反查 | history receipt 保存原地址；反查结果此 VM 内存展示；异步结果 checkProof；无值unknown不冒充另一端 | SOURCE_OK；T2 |
| 卡片右侧 SOC | DS:706-707,719-740 → DTO:58-59 → HM:108-134；UF:142-146 检查0..100 | 每侧独立 nullable，真0可见，双未知不显示，单侧未知保留“—” | SOURCE_OK；T2 |
| 卡片能耗 | DS:704-705,758-767 → DV:179-242；合格Wh/km÷10，以固定 kWh/100km显示 | receipt 指标先做源/窗口匹配；但同整数SOC且无任何能量证据会进入固定145Wh/km兜底，且卡片无估算标识，见 D01 | BUG；T3 **当前锁定了该错误行为** |
| 卡片距离/最高速/分钟 | DS:769-796 → DTO:53-57；原同响应长度/速度直接显示，单位 `drivePresentationUnits` | 有限非负距离、非负速度/时长，缺值unavailable，真零保留；单卡不再次按当前偏好转换原值 | SOURCE_OK；T1/T2 |
| 驻车卡时间、时长、地址、SOC | DS:429-499,814-867；相邻 older.end → newer.start；正分钟间隙；要求两端同source/instance且≥0.5km，隐藏中间行程应阻断；SOC源是相邻行程，绝非当前SOC | list 数据内同位置/边界证据；但本地首读 DAO:179 排除 quarantined，使“完整序列”缺失隔离中间行程，见 D07 | BUG（Room-first列表边界）；T4 有纯函数/详情查询覆盖，但不能证明列表真实入参完整 |
| loading、无数据、错误/旧缓存提醒 | DS:142-151,308-330,354-402 → DV:553-575,655-668 | timeout20s保留缓存；identity unavailable清空。普通 `else -> error`可直接显示未经本地化的错误字符串；本轮未查所有错误常量路径 | SOURCE_OK（缓存保留）；错误文案端到端 UNVERIFIED |

本页实际摘要/柱图没有总净kWh、平均速度或平均能耗栏目；总kWh留详情，卡片四项是能耗/距离/最大速度/时长。未把未显示的 `avgDistancePerDrive/avgDurationPerDrive`（DV:273-274）当可见参数。

### DriveDetailScreen 与实际路线图

| 位置/参数 | UI → VM/来源；公式、单位 | 持久化、null/空/资格 | 判定 / 测试源码证据 |
|---|---|---|---|
| 地图点、起终点标记、折线 | DD:230,478-527 → AR:22-48 → AN:92-107,171-191；只吃 `drive_details` 实际经纬点，不从地址文本构造 A/B | DTO:167-176 → receipt/chunks；不足2个可用不同点显示明确数量。全局 distinct 抹去闭环终点，见 D05；原始顺序/大缺口未经时间投影 | BUG；T5只证明点回放/点不足，provider真实轨迹 UNVERIFIED |
| WGS/GCJ、SDK加载/失败 | AR:37-48 → AN:83,92-100 → AT:15-23；默认原始WGS84，中国内转GCJ一次；相同 native view渲染markers/line | AG:35-54 有15秒失败、未配置/未同意/未验证/需重启/渲染异常反馈；AN:111-124,214-222清生命周期 | SOURCE_OK（约定链）；DTO没有 coordinate_system 字段，实际提供方WGS契约、真Key、图块、实机路线均 UNVERIFIED；T5 |
| 路线起止地址 | DD:416-422 → DM:279-290；优先原地址，再各自边界/首末点反查 | DD 将未知一侧复制另一侧，详情和曲线可将“未知→B”变成“B→B”，见 D08；不代表地图合成坐标 | BUG；T2只覆盖列表，未证明详情 |
| 起止时间/头部时长 | DD:432-446 → DTO:119-125；日期格式化，durationMin否则durationStr | 原 receipt 可恢复；格式坏显示—。头部 durationMin没有与stats同样的>=0门，负值可能与指标栏不一致 | BUG（D13范围）；T6 |
| 距离/时长/按距离平均速度 | DD:261-262,280 → DX:120-141；已知长度原单位；平均=同单位distance÷精确正秒×3600（无有效端点时用正分钟） | 距离≥0/有限；durationMin≥0；不除0；真0保留，未知显示unavailable | SOURCE_OK；T6 |
| 效率/总净能量 | DD:263-269,303-308 → DM:174-205 → EB/ER → DX:25-53；kWh；Wh/km÷10 → kWh/100km，负值recovered | 合格receipt及源/窗口；已知能量和距离独立；真实0、负净回收均保留；不凭当前SOC还原过去耗电 | SOURCE_OK；T3/T6 |
| 能量来源、覆盖/秒数、容量假设说明 | DD:309-343；reported/power/endpoints/SOC capacity；容量额定/默认/车型假设明示；端点覆盖不冒充时间覆盖 | DM:235-265 optional容量只在验证当前车后细化；DAO:51-77以最新同窗重新算；receipt保存计算合同及basis | SOURCE_OK（静态链）；API reported不等于物理电池净计量；原始/提供方覆盖仍待自然证据，T3/T6 |
| 最高速、报告/样本均速、距离均速 | DD:278-280 → DX:93,129-131,141；原收据速度单位，样本均值为算术mean | 样本不要求整窗覆盖；只要任一样本就覆盖summary最高速，可能降低真实max，见 D09。报告speedAvg可能实际是后端样本均值但标签变“平均”，见 D14 | BUG；T6 |
| 最大加速/最小回收/平均功率 | DD:289-291 → DX:94,133-135；有限功率样本max/min/算术mean，fallback reported extrema；kW，负回收保留 | 平均明确“样本均值”标签（DX:88-89），空样本mean未知；部分样本极值同样覆盖全程reported极值，D09 | BUG（极值）；算术mean标签 SOURCE_OK，T6 |
| 起止SOC/使用百分点 | DD:300-302 → DX:97-139；优先明确0..100端点，否则靠边界最近样本，容差max30s/min5min/时长十分之一；used=start-end，仅非负使用 | exact同一时刻冲突拒绝；超容差拒绝；同距离但不同SOC的近邻平局仍任意选首个（D15）；SOC升高“使用量”未知，净回收能量独立 | SOURCE_OK（范围与缺失）；BUG（近邻平局），T6 |
| 海拔最大/最小/爬升/下降 | DD:349-358 → DX:95-96,136-137,147-155；相邻已知elevation差，正差gain、负差loss；Long累加防Int overflow | 部分/失序/缺日期样本无coverage门，删除null后桥接缺口；不能声称整程爬升准确；whole-card无elevation则隐藏 | BUG（D03的缺口/顺序问题）；来源高度单位合同 SOURCE_DATA_MISSING |
| 内外平均温度 | DD:363-369 → DX:142-143 → DTO:133-134；finite summary平均，UF仅附当前温度单位 | header receipt保存数字；只从后来status加载温度单位（DM:231-233），DTO同响应units未完整保留温标。离线偏好变更/非C源的单位证明不足；无summary温度则隐藏，即使point climate有值也不重算 | UNVERIFIED（同响应温标）；CM:77-102给后端样本均值；T6 |
| 内联速度曲线、点选时间 | DD:198-218,375-384,709-727；全positions时间+过滤后speed数组 | mi收据速度再次×0.621371（D04）；未知length也画km/h；mapNotNull破坏时间对应（D03）；无2个speed则整个图消失 | BUG；独立曲线测试T7不覆盖此函数 |
| 沿途天气表：时间/里程/温度/图标 | DD:387-393 → DM:294-306 → WR:96-121,127-148,253-305 → WC:193-224；实际GPS累计haversine里程，按行程距离选查询点；历史天气API，不是车内外温度曲线 | 临时内存，无Room。WC:304-310将OpenMeteo C按偏好转F，UF仅附标，未二次转换；天气码缺失默认0晴天（D16）；查询未显式timezone且DTO无timezone/units，小时对齐 UNVERIFIED | BUG（缺码晴天）；时间/实际天气 provider UNVERIFIED；无当前天气替代历史的代码证据 |
| 所属旅程/分离、曲线跳转 | DD:235-242,258-259 → DM:225-229,316-324；按scoped local car查询包含关系；NavGraph.kt:746-768带原car/drive跳曲线 | 操作前后proof检查；关联持久化在TripRepository，本页不独立验证Trip消费 | SOURCE_OK（当前入口）；旅程子链由后续审计负责 |
| 初载/失败/冷重开/前台刷新 | DD:112-120,151-174 → DM:106-110,134-171 | 先请求远端才读Room；每次load清数据，既有缓存可被长网络请求挡住；失败仅snackbar而内容为空；曲线页更无错误显示。见 D12 | BUG；T5只覆盖立即返回错误/损坏缓存，未证明慢网缓存先显 |

可达性说明：DD 当前只调用速度内联曲线（DD:375-384）。文件中 PowerChartCard/BatteryChartCard/ElevationChartCard（DD:732/757/788）是未在该 content 中调用的函数，不能当作当前详情已显示三种额外曲线。DC 当前也没有温度曲线；真实位置温度仅有summary指标，天气表属于另一来源，不以天气表证明车辆温度历史。

### DriveCurvesScreen

| 位置/参数 | UI → VM/来源；公式、单位 | 持久化、null/空/资格 | 判定 / 测试源码证据 |
|---|---|---|---|
| 标题距离/时长、副标题路线 | DC:96-107,285-290；DM同详情load；receipt长度单位直接显示 | 标题负duration未过滤；两侧地址交叉fallback同D08；数据来自同detail receipt | BUG（D08/D13） |
| KPI距离/最高速/已使用/平均 | DC:305-329；distance原单位；max优先detail报告再stats；used优先SOC百分点否则kWh；平均是stats.powerAvg | 真0可见；invalid distance/speed拒绝；used名下可能显示两种不同物理量，单位已随数值显示；power样本mean此处仅标“平均”（D14） | SOURCE_OK（数值/单位）；BUG（均值语义），T7 |
| 速度曲线 | DC:195-202,366-379；finite非负speed，原同响应km/h或mph，不再次转换 | detail未知length时隐藏；2个有效speed才画；point/chunk可恢复。时间映射仍D03 | SOURCE_OK（单位）；BUG（时间），T7 |
| 距离曲线 | DC:205-214,392-419；用sum(speed)占比分配总里程；无有效速度时线性分配；标题“估算” | 未用实际delta-time，missing speed当0，第一点非零，失去物理累计距离含义；D10。点序/缺口同D03 | BUG；T7只验证末值10mi/10km，未验中间时间 |
| 功率/动能回收曲线 | DC:216-223,434-447；power.toFloat，kW，零线 | null删除并桥接；负值保留正确；NaN/∞或Float overflow不拒绝，D13；时间D03 | BUG；T6只覆盖stats而非曲线非法值 |
| 电量曲线 | DC:225-232,459-481；batteryLevel.toFloat，% | 不拒绝-1/101；固定范围按错误值外扩；缺样本后时间轴错配，D03/D13 | BUG；T6的端点范围测试不覆盖此曲线 |
| 海拔曲线 | DC:235-242,493-505；实际elevation整数，m | null删除、timestamp未排序/未按时间加权，D03；数据缺失则不显示；来源单位和实际采样覆盖不足以确认整程 | BUG（轴）；SOURCE_DATA_MISSING（高度源合同） |
| 五图共用X/时间提示/空态 | DC:155-175,193-265,567-579 → LC:107-115,137-147,222-239 → CD:68-82 | X按压缩数组index均匀排；时间来自未按该变量过滤的positions，downsample也不保留原索引。positions>2但所有指标<2会无图且不进“无样本”空态；恰好2个真实点全部拒绝页级绘图 | BUG（D03）；测试T7只用等距、无日期、无缺口的3点 |

### ParkedDetailScreen

| 位置/参数 | UI → VM/来源；公式、单位 | 持久化、null/空/资格 | 判定 / 测试源码证据 |
|---|---|---|---|
| 头部地址、绝对起止、总时长 | PS:179-191,246-267 → PV:137-164,255-313；历史older.end→newer.start，Duration秒级，0/不足1分钟单独格式 | local receipt proof要求同车/源/实例/原窗；DAO intervening查询含所有质量阻断。remote只核对pair ID未核对window/source（D06）；本地address直接读primitive列 | SOURCE_OK（local窗）；BUG（remote窗），T4/T8 |
| 头部增/降/不变badge | PS:193-204,272-281；end-start，起止未知时用有界server battery_delta；有记录charge且正增才写充电 | 未借当前SOC；单scalar没有观察时刻，不等于可追溯边界；本地primitive SOC占位导致假0与假差（D02） | BUG（D02）；T8 |
| 起止SOC/变化pp | PS:344-385 → PV:292-302；0..100且变化[-100,100]；变化是百分点不当kWh | UI门正确，但PV直接从Room非空SOC列取值，绕过nullable receipt。已知值/未知/真0不能保持（D02） | BUG；T8当前fixture receipt未含SOC |
| 无充电证据时整体SOC pp/h | PS:206-209,387-410,616-624；`(end-start)/精确小时`，gain/drain用符号及绝对值 | 缺任一SOC/非正时长不出率；有charge/invalid session时禁止整体速率冒充非充电耗电；无complete archive时“无charge证据”并非没有自然充电 | SOURCE_OK（单位和门）；SOURCE_DATA_MISSING（自然全活动证明），T9 |
| 逐次充电：次数、每次时间/时长/kWh/kWh/h、跳转 | PS:418-423,528-575 → PV:152-186 → PB:85-130；每session唯一ID，时间排序；energy≥0有限；rate=energy/该次正小时 | server list优先，legacy single保留；一次最多展示3可展开，cap100；不足完整标记。local按相同source/instance筛选，但不带SOC观察时刻，因此不产生已证非充电SOC率 | SOURCE_OK（多次分开/空不补0）；后端CP最多64并标incomplete；T10 |
| 已记录充电时长、其外时长 | PS:577-587 → PB:132-139,167-182；与停车窗相交区间取union，outside=parked-union | 重叠不双计；open session已记录时长未知。名称明确“记录外”，不把它等同所有非充电自然时间 | SOURCE_OK；T10 |
| 非充电SOC变化率 | PS:589-610 → PB:140-153,156-164；`[(end-start)-ΣchargeSOC变化] / outsideHours`，消费符号反向 | 要server sessions完整、所有session端点SOC及观察时间5分钟内、无重叠/截断、正outside；仅SOC pp/h，文字明确非kWh。provider完整性只是已存会话非物理全采 | SOURCE_OK（严格区分）；真实活动覆盖 UNVERIFIED，T10 |
| 停车能量/每小时 | PS:437-446,649-663 → AD:75-92；只接受EnergyRemaining exact-window端点合同、nominal remaining、estimated、明确no-driving/no-charge；kWh / hours | 拒绝SOC容量乘法和raw energy_kwh/average_power_kw；有任何charge证据立即拒绝。当前CP:169-173与HOME:30-51不发此energy_contract，因此通常合法空态 | SOURCE_OK（资格/零/负值）；SOURCE_DATA_MISSING（当前源合同），T9 |
| 内外温度 | PS:448-451,662-663 → AD:62-63 → HOME:359,440-466；finite采样均值，°C | local fallback明确null；CP:170明确null；HOME可有值但此次未用真实provider验证；不借当前车温度，也无温度曲线/逐点Room | SOURCE_DATA_MISSING（cloud/local历史）；SOURCE_OK（nullable投影），T9 |
| 覆盖率/样本数/缺失说明/来源说明 | PS:453-475,655-658；有限coverage[0,1]×100；sampleCount≥0；local_room不把0当实测 | CP明确0样本/0覆盖，UI允许显示真实查询0；DTO字段缺键默认0无法证明“明确报告0”；coverage是power时间段比例，不是所有温度或活动覆盖 | SOURCE_OK（local空态）；SOURCE_DATA_MISSING（字段独立覆盖证明），T9 |
| [历史已移除] 驻车地图坐标/外部地图 | 旧PS:687–772分支在冻结快照已不存在，按后附复核为准 | VM旧坐标旁路仍在，但当前Screen不消费，不能说用户当前看见该地图 | HISTORICAL_REMOVED；不计当前可见字段或当前阻断 |
| 加载、离线fallback、错误和scope改变 | PS:100-159 → PV:111-135,168-210；Room先显，20s timeout，generation+scope检查；safe error label PS:667-669 | 新scope取消并清UI；late remote检查identity。成功server parked详情/会话/温度/coverage没有任何保存调用，重开退回邻接summary（D17） | SOURCE_OK（迟到guard）；BUG（持久化与D11），T8 |

## 已确认缺陷与最小不改布局建议

以下为源码确定的缺陷，不声称已在Jovi的真实记录或已安装包上复现。**本分支未发现可确定归为 P0 的问题。**

### P1

**D01 — 相同整数SOC会制造无观测支持的固定145Wh/km。** `DrivesViewModel.kt:227-239` 在resolver因相同SOC返回未知后，以 `min(distance*145/1000,capacity*0.009)` 生成kWh和效率；实际卡片 `DrivesScreen.kt:704-705,758-767` 无估算区分。触发：0.8km、60%→60%、无energy_contract/功率/能量计量，输出0.116kWh及14.5kWh/100km；detail resolver则保持分辨率不足。即使打上estimated，也没有依据证明145这个数属于该行程。最小修法：去掉这条常量兜底，复用详情resolver的unknown；确有容量端点估算时保留原basis合同。回归必须改掉 T3:107-121 期待虚构值的断言。

**D02 — 驻车Room fallback将未知SOC列的0占位变成历史实测。** `ParkedDetailViewModel.kt:292-302` 直接读 `DriveSummary.endBatteryLevel/startBatteryLevel`；`DriveEnergyBinding.kt:231-232` 和 UnifiedHistoryRepository.kt:573-574 会把未知写为0，而 `HistorySummaryMapper.kt:54-72,108-134` 已有可空receipt投影。触发：有效同源邻接receipt没有battery_details，Room标量0→0，则展示0%→0%、0pp、0pp/h；一端缺失则产生假耗电/增益。最小修法：proof验证后从两端 `toAnalysisDriveData()` 的nullable端点取值，保持真实0与null分开；地址同样使用已验证projection。T8的fixture本身receipt无SOC但断言primitive成功，必须新增缺失/真0/更正后重开对照。

**D03 — 图表的数值、时间、缺口和真实采样顺序没有同一索引。** `DriveCurvesScreen.kt:155-175,366,434,459,493,567-579` 和 `DriveDetailScreen.kt:198-218,709` 对值mapNotNull，却对完整positions取时间；`OptimizedLineChart.kt:107-115,137-147,222-239` 按压缩点序号定位，`ChartDrawUtils.kt:73-80` downsample只返值不带原时间索引。触发：t0速度10、t1缺失、t2速度30、t3速度40；图中第二个有效值会落在非原观察时刻，长缺口被连成连续平滑线。非均匀采样、重复/失序时间、长缺口同样没有门。最小修法：共用纯数据投影保留(timestamp,value,originalIndex)，按有效窗排序并明确重复时刻冲突策略；断开超过采样合同gap的segment，X使用elapsed time，downsample保留时间。维持现布局，仅替换图表数据模型/选点。补2点有效、>2positions但<2有效值、缺口、重复时间、downsample对齐消费者回归。

**D04 — 详情内联速度曲线二次转换英里，且未知长度单位仍画km/h。** `DriveDetailScreen.kt:709-727` 收到raw preferred speed后，按units.isImperial再×0.621371；同页 `DriveDetailScreen.kt:163` 已传同收据mi，`DriveDistanceUnits.kt:29-30` 不把其速度转成km。触发同响应60mph：数值卡60mph，内联图37.3mph；独立曲线 `DriveCurvesScreen.kt:366-379` 则60mph。显式lengthUnit=""时stats拒绝速度，内联图仍拿raw值附km/h。最小修法：与独立曲线共用同一个receipt-unit投影，不再换算；未知lengthUnit不消费raw speed。T7当前仅验证独立曲线，不能关闭此缺陷。

**D05 — 全局distinct把闭环真实终点删除。** `DriveDetailScreen.kt:521-527` 对整条GPS序列做distinct，`AmapRouteView.kt:30-34` 从剩余列表的last取终点。触发真实路线A→B→A，持久化3点正确，UI变A→B且B被标终点；多次经过同路也丢掉回程段。最小修法：用不同点数量判断是否存在可用移动；绘制时保留原有顺序和末点，仅可删除相邻重复。配合D03按时间/缺口做展示投影，永不从地址造坐标。

**D06 — 驻车远端成功仅验pair数字ID，未验本地已知的精确窗口/来源。** `ParkedDetailViewModel.kt:181-186` 只核对older/newer ID；`AdapterModels.kt:50-71` 没有车、source instance、source record或可验证边界引用。触发同pair ID返回另一start/end/源的payload，VM无条件替换已验证本地窗，之后PS:196-209按它计算SOC率。不能把请求使用正确账号当作响应窗口已证明。最小修法：有本地proof时同时核对source及精确边界；给协议补必要的source-instance/vehicle/window evidence并保持向后缺证据降级，而不是任意借本地字段拼成已验证响应。schema扩展应另列实施范围。

**D07 — 行程列表Room-first的“完整序列”实际预先丢掉隔离中间行程。** `DrivesViewModel.kt:515,525,716` 读 `DriveSummaryDao.getAllChronological`，该DAO `:179` 明确 `qualityState != 'quarantined'`；`DrivesScreen.kt:430-443` 因此可能把原本不相邻的两端认证为驻车。触发A、隔离B、C三段且A/C≥0.5km、同source/instance，离线第一帧可显示A→C的假驻车；真正详情 `ParkedDetailViewModel.kt:267-279` 用含所有质量的intervening查询会拒绝，列表/详情互相矛盾。最小修法：为邻接证明读取轻量含所有质量的原始时间序列/存在性，展示仍隐藏隔离卡；不能只给纯函数传入理想“完整列表”做回归。

### P2

**D08 — 单侧地址缺失被另一侧冒名填充。** `DriveDetailScreen.kt:417-421`、`DriveCurvesScreen.kt:287-290`。触发start缺失/end=B时两处显示B→B，而列表正确为unknown→B。最小修法：两侧分别fallback unknown，仅以各自端点的真实反查补齐。坐标轨迹和文本地址保持独立，不能将此问题误报成“伪造地图坐标”。

**D09 — 少量样本极值覆盖较完整的报告极值。** `DriveDetailMetrics.kt:129,133-134` 优先samples max/min，完全不看采样覆盖。触发报告speedMax=100、收到样本max=40，则详情最高速40而列表/曲线KPI100；功率/最小回收同理。最小修法：若报告极值本身同源同窗合格，优先报告；无合格报告时样本极值只能表述为已收到样本的极值，并与覆盖绑定。保持当前参数位置。

**D10 — 距离估算曲线忽略时间且缺全速度仍编造线性进度。** `DriveCurvesScreen.kt:397-407`。触发非等间隔speed样本时sum(speed)比例不是距离积分，首点非零；所有speed缺失仍画从0到总里程的直线。标题虽写estimated，仍缺支持该曲线形状的证据。最小修法：有可靠时间/速度才按dt积分并保留gap；无证据仅保留顶部总里程，使用既有空态，不画补造中间点。

**D11 — 停车位置可混合两次观测，网络失败后又丢已读坐标。** `ParkedDetailViewModel.kt:94-98` lat/lon分别fallback，older仅lat/newer仅lon时组成从未观测的位置；`ParkedDetailViewModel.kt:188-203` 的error/timeout/mismatch fallback重建state遗漏latitude/longitude，使同一离线卡先有地图后消失。最小修法：以完整可用坐标pair选择older或newer，同scope校验后publish；所有保留本地data的fallback同步保留该pair。加无网络、超时、错误pair三种消费者断言。

**D12 — 行程详情/曲线不是Room先显，长网络和失败会造成空白。** `DriveDetailViewModel.kt:106-110,134-140` 先重置state、等remote，再读Room；`DriveCurvesScreen.kt:126-139` 无detail时没有错误/重试内容。最小修法：按已验证context先发布summary+可读point receipt，再有限时网络刷新，失败保留同scope缓存；复用现有空态/提示组件及布局，不重排参数。测试必须挂住网络而不是立即返回ApiResult.Error，并验证首次/前台刷新/冷重开。

**D13 — 曲线/标题绕过已有有限值与范围资格。** `DriveCurvesScreen.kt:434,459` 对power/SOC直接toFloat，`ChartDrawUtils.kt:73-81` 未finite检查；power Double.MAX_VALUE→Float∞、SOC=-1/101会污染轴与tooltip。详情speed `DriveDetailScreen.kt:709` 也没有独立curve页面的finite/非负门。两页头部duration（DD:444、DC:100）无>=0过滤。最小修法：所有实际消费者共用变量级资格：speed有限非负、power有限且Float可表达、SOC0..100、duration非负；非法点留缺口不能压缩混用时间。

**D14 — 提供方的样本均值进入DTO后失去样本语义。** `history_sample_metrics.go:77-79,101-106` 的speed_avg是观测点算术mean，并附 `speed_metrics_source=observed_route_samples`；`DriveModels.kt:27,127` 没消费该来源元数据；`DriveDetailMetrics.kt:82-85` 一旦speedAvg存在就标“平均”。`DriveCurvesScreen.kt:327-328` 也把已知power样本mean仅标“平均”。最小修法：保留均值method/source及sample count；同现有标签位置选择样本均值文案，不把非均匀采样算术平均当整段时间均速；距离/精确时长均速另保留。

**D15 — 非exact SOC边界平局任意选首个。** `DriveDetailMetrics.kt:110-116` 只minBy时间距离；exact冲突已正确拒绝（:104-106）。触发边界两侧等距各10s、68与69两种SOC，改变输入顺序会改变边界和used；原始点归档刻意保留输入顺序。最小修法：收集所有最小距离候选，冲突则null，或协议明示单向边界规则；对称/重复/相同值样本都覆盖。

**D16 — 缺失天气码会被显示为晴天。** `WeatherRepository.kt:294-304` 默认weatherCode为0，`WeatherRepository.kt:53` 将0映射CLEAR；有温度但没有码的hour会凭空显示晴天图标。最小修法：天气条件可空或明确UNKNOWN，温度仍保留；不给缺失条件制造已知天气。世界各地时区小时对齐还需独立协议/fixture验证，本次未联网确认服务默认行为。

**D17 — 驻车远端详情和充电边界证明仅保留内存。** `ParkedDetailViewModel.kt:168-205` 成功只赋UiState，没有Room写入；`ParkedPageSource:55-58` 仅local summary/charge读取。触发在线显示两个session及observedAt、温度/coverage，关页/冷重开后断网，仅邻接summary可恢复，local projection PB:65-72又不保留SOC observedAt，已证非充电率丢失。最小修法：保存经source/vehicle/window验证的版本化parked receipt（含多个session和boundary observedAt）并优先读取；不能用当前SOC、单个linked_charge或原始标量补足缺失。

## 来源边界与未关闭项

- 当前cloud parked代码 `parked_history_bounds.go:147-174` 明确只给archive相邻边界，温度/能量为null；`CP:56-135` 逐次会话按source/instance/source vehicle/质量过滤、cap64，并明确完整标记只是匹配的**已存记录**全返回。`CP:184-188` 与后续查询有中间drive否决。源码不能证明MQTT新自然停车、真实活动无漏采或真实noncharging分段测量。
- self-host adapter `HOME:351-359` 的bounds只验证两端同car和时间正序，未见“中间无drive”门；Android自身列表/本地detail有邻接守卫，但直接API response仍可能非相邻，且PV只校验ID。该提供方缺口由D06的验窗/协议完整性覆盖；没有把仓库代码当当前部署版本。
- `HOME:427-466` 的采样温度是算术均值，power积分只积相邻≤900s；它产出的raw energy_kwh/average_power_kw没有AD所需nominal剩余能量合同，UI合法拒绝这两个数，不能把显示unavailable当成已经证明UI数据损坏。
- `DM:43-50,121-128,220-305` 对迟到可选地址/天气/容量结果有generation/context检查；但详情/曲线VM不像列表和驻车订阅scope changes。账号/来源在页面已发布后变化、数字carId保持相同、没有再触发load时，已有旧内容如何清除，需父任务结合全局Nav/Auth消费者独立验证，判 `UNVERIFIED`，未直接宣称泄漏已发生。
- 同响应只持久化lengthUnit，temperature/elevation没有同等完整单位或measurement point合同。当前自然数据是否都是C/m、旧selfhost首进/离线后status偏好变化是否会误标，均 `UNVERIFIED`。不能仅以“云端是km”关闭self-host单位问题。
- 原DTO不含route点provider总数、采集gap证明、原始坐标系、逐字段观察时间。route source、source-instance存在与否、完整自然行程是否已被正确采集，需同scope脱敏provider receipt和已安装页面交叉核对；本轮没有访问它们。

## 现有测试源码证据与缺口（未运行）

所有路径前缀是 `android/app/src/test/java/com/matelink/`，特别注明androidTest的除外。

| 编号 | 文件及已读入口 | 实际可支持的范围 / 不能支持的结论 |
|---|---|---|
| T1 | `ui/screens/drives/DrivesSummaryEvidenceTest.kt:11-108`；`DrivesChartEvidenceTest.kt:11-51`；`DrivesHistoryContractTest.kt` | 规范化单位、独立null/0/partial/overflow、bucket资格的断言存在；不是同scope真实provider→Room→Screen跑通证明 |
| T2 | `ui/screens/drives/DrivesAddressEnrichmentTest.kt`；`DrivesHistoryVisibilityTest.kt`；`data/repository/DriveResponseUnitsConsumerTest.kt` | 列表地址和响应长度边界有定向测试；未覆盖DD/DC交叉地址fallback和DD SpeedChartCard |
| T3 | `ui/screens/drives/DriveListEnergyConsumerTest.kt:20-39,89-121`；`DriveDetailEnergyPresentationTest.kt:10-88` | 真零、负净能量、source/coverage存在测试；107-121主动期待同SOC生成145Wh/km，属于需修正的反向断言，不能用该测试通过当业务正确 |
| T4 | `ui/screens/drives/ParkedRoomAdjacencyIntegrationTest.kt:49-71`；androidTest `ui/screens/drives/ParkedDetailScopedRoomAndroidTest.kt:38,74` | 详情实际Room query阻断中间隔离/短drive；未覆盖DV先用排除隔离行的DAO生成列表pair |
| T5 | `ui/screens/drives/DriveDetailOfflinePointConsumerTest.kt:47`；`data/repository/HistoryDetailCodecTest.kt`；`HistoryDetailRoomIntegrationTest.kt`；`ui/components/AmapEmbeddingContractTest.kt` | receipt/chunk回放、损坏拒绝、wrapper入口有断言；未证明闭环A→B→A终点、真实GCJ位置、SDKKey/provider点完整性或慢网络Room先显 |
| T6 | `ui/screens/drives/DriveDetailStatsEvidenceTest.kt`；`DriveDetailBatteryBoundaryTest.kt:32-193`；`DriveDetailSampleMeanLabelTest.kt`；`domain/analytics/DriveEnergyResolverTest.kt` | 已有null/0/finite、近边界SOC和exact冲突、积分/样本mean等断言；非exact平局、partial extrema与实际curve输入未被这些测试证明 |
| T7 | androidTest `ui/screens/drives/DriveCurvesUnitsAndroidTest.kt:23-55` | 真实DC组件用3个无日期/无缺口的synthetic点验证同响应mi与km，验证末值；不涵盖DD内联二次换算、非均匀时间、gap、downsample和非法power/SOC |
| T8 | `ui/screens/drives/ParkedDetailLocalFallbackTest.kt:10-54`；`ParkedDetailScopedLocalPublicationTest.kt:38-145`；androidTest `p0/ParkedDetailFullUiQualificationTest.kt:30,80` | source/instance/pair/generation/Room-first与合成界面有测试源码；LocalFallback fixture receipt无battery_details却用primitive列断言31→100，未证nullable0；没有本次测试/像素/provider结果 |
| T9 | `ui/screens/drives/ParkedHourlyRateTest.kt:11-32`；`ParkedTelemetryPresentationTest.kt:22-77` | pp/h与kWh/h、真零、负值、local缺样本、rawscalar拒绝的断言存在；不能代替真实EnergyRemaining合同或逐字段覆盖证明 |
| T10 | `ui/screens/drives/ParkedChargeBreakdownTest.kt:23-136`；androidTest `p0/ParkedDetailFullUiQualificationTest.kt:80`；`deploy/jourvolt-dev-mock/parked_history_bounds_test.go`；`deploy/teslamate-home-docker/adapter/cmd/adapter/parked_charges_test.go` | 多session、union duration、超cap、重叠/open、SOC observedAt门和两session导航fixture存在；没有自然充停充复充全采、冷重开parked receipt或本轮提供方验收 |

## 收口记录

本次已完成四屏及其实际地图/天气消费者的源码字段盘点；已确认 P1 7项（D01-D07）、P2 10项（D08-D17）。这里的“完成”只指审计文档已完成，所有修复、测试运行、隔离Room/UI验证、provider自然数据与已安装版本交叉验收仍未进行。下一步由父任务集中复核根因和授权范围，优先处理虚构能耗、未知SOC占位、真实轨迹终点、曲线时间/单位及驻车窗口/邻接证明；修复必须维持当前页面布局和参数排列。

## 冻结快照复核 — 新E盘审计目录（本节覆盖前文的当前可达性/行号判定）

最终工作目录：`E:\project\_worktrees\matelink-release-data-audit-20261026`。父任务的`isolation.json`确认954文件稳定复制；审计初始读取之后，别的过程修改了`ChargesScreen.kt`、`ParkedDetailScreen.kt`及`ParkedErrorPresentationTest.kt`。共享Git的`core.worktree`同时被改往另一个任务目录，因此前文`cdfe0b26+dirty`只描述原始读取基线，不能使用后来Git元数据冒充本文件的冻结版本。本次仅在新目录读源码及补记文档，没有恢复/删除任何产品UI，也没有改Git配置。

当前`android/app/src/main/java/com/matelink/ui/screens/drives/ParkedDetailScreen.kt`的SHA256为`9E27FD3AA44BA1B84801EF645DF82C836C4E2CFEEE19662B3D8303452F079592`。本次复查确定：

- **当前驻车Screen不存在地图/坐标渲染。** 限定搜索`ParkedMapCard`、`AmapPointView`、`latitude`、`longitude`及`geo:`均无命中；当前`ParkedDetailScreen.kt:122-126`只向content传data、chargeBreakdown和charge导航，没有VM坐标。前文驻车矩阵“地图坐标/地址/外部地图”行及其PS:687-772引用，明确属于**前一读取版本**，不能计入当前visible覆盖或当当前页面已具备地图。
- **D11降为历史/不可达消费者问题，不是本冻结Screen的当前阻断。** `ParkedDetailViewModel.kt:94-98,151,163,183,188-203`中的坐标pair混合/失败丢失代码仍存在，但当前Screen不消费这些坐标；不能继续声称当前页面发生“有地图后消失”。这是外部过程移除渲染后的可达性变化，**不是数据逻辑修复验证通过**，也不将删除地图当本轮成果。原有字段是否应恢复由父任务和Jovi处理，本次不改布局。
- **D02、D06、D17等其余VM根因仍适用。** 已在新目录核对PV:181-186仍只核pair ID，PV:292-293仍直接读取Room非空SOC，PV:168-205仍只有state发布没有parked receipt保存；其他03源码未列入稳定复制后的变更清单，不因地图移除而关闭这些问题。
- **驻车关键当前行号更新如下**（仅行号/可达性修订，前文公式与来源说明仍按相应根因保留）：

| 当前参数/函数 | 新冻结源码位置 |
|---|---|
| Screen入口、loading、local数据发布、content参数 | `ParkedDetailScreen.kt:77,108-126` |
| 起止SOC/变化/充电证据与整体SOC率输入 | `ParkedDetailScreen.kt:178-190` |
| 页面日期范围绑定 | `ParkedDetailScreen.kt:237` → 新`formatParkedDateRange:646-677`（手机时区；文案改为月日时间，不再显示原绝对年格式） |
| 起止SOC/百分点三个位置 | `ParkedDetailScreen.kt:322,330,338` |
| 无充电整体SOC pp/h及说明 | `ParkedDetailScreen.kt:361-385` |
| 多次充电卡/缺会话说明 | `ParkedDetailScreen.kt:391-397`；卡实现`:475`起 |
| 能量/每小时/内外温度/覆盖/样本数 | `ParkedDetailScreen.kt:411-435` |
| 来源说明 | `ParkedDetailScreen.kt:440-447` |
| 非充电SOC率/缺分段/不完整说明 | `ParkedDetailScreen.kt:563-584` |
| pp/h、kWh/h、telemetry投影 | `ParkedDetailScreen.kt:590-638` |
| 安全错误文本/保留boundary-time helper | `ParkedDetailScreen.kt:641-644,679-681` |

`ParkedErrorPresentationTest.kt:19-22`的安全错误断言仍在，`:25`新增日期范围格式测试；本次只读、未运行，不依据其存在声称新显示通过。当前可达问题计数相应为**P1 7项、P2 9项，另D11为历史问题**；前文P2 10项是初始读取版本的历史盘点，不应与当前9项重复合计。当前地图可用性为`SOURCE_DATA_MISSING（渲染入口已不存在）`，设备/provider/像素全部保持`UNVERIFIED`。
