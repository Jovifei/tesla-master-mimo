# F6 — 长途、报告与导出数据修复

Jovi 授权的 build72 审计问题 → build73 修复候选。执行者 `/root/audit_auth_home`；唯一目录 `E:\project\_worktrees\matelink-release-data-audit-20261026`。本组不修改 Git 配置、不提交、不推送、不跑 Gradle、不操作设备或网络；父任务统一执行测试、账本、版本与提交。布局保持现有结构和参数顺序。

## 当前计划与进度

- [x] 读取当前 AGENTS、06/09 独立复核及 FIX-PLAN，逐项确认 F6 实际消费者。
- [x] 首批 12 个可执行失败回归落盘：真实 Room 保存记录保护/无效成员/占用成员、CreateTrip scoped namespace/账号变更、Export 实际计数与年份同车、跨offset及显式charge旅途边界、路线失败槽位、CSV 负数、JSON 显式 null、输出文件唯一性。
- [x] 父任务实际执行 RED：`groups-red-03`联合49例/36fail/0error/0skip；本组12个新增断言全部按预期FAIL。运行角色是父任务，不认领为本组执行；XML由父保存在Download下该轮`groups-red-03-xml`目录，绝对artifact引用由父总账补。
- [x] 首轮实现：创建/导出 verified local key 与 late owner proof、保存成功门与失败恢复、浏览不删用户记录、路线/天气同 drive 槽位、冻结 cohort/MIME/独立文件、精确年份与数值 formatter。
- [x] 父任务focused GREEN与本组源码自审完成；实际证据分轮记录如下，本组未运行构建。
- [ ] 父任务最终同源码全量回归、独立复审及设备/提供方发布门；后续GPS gap及formatter父改动以父QA为准。

## 失败回归 targets（新增，本组未运行）

| Target | 断言 |
|---|---|
| `com.matelink.ui.screens.trips.TripSavedRecordProtectionTest` | `getTrips`保留同drive但不同用户名字/charge成员的两条USER_EDITED；缺失drive与后来被占用drive不能保存新孤儿记录 |
| `com.matelink.ui.screens.trips.CreateTripScopedConsumerTest` | remote=1候选必须读已验证负local key；同numeric换账号清除旧草稿 |
| `com.matelink.ui.screens.reports.ExportScopedConsumerTest` | 当前scoped负键1条与未链接旧正键2条区分；预览count必须与已验证年份同车 |
| `com.matelink.domain.TripChronologyConsumerTest` | 跨offset按Instant选首末；手动旅途显式charge在drive窗外也须纳入总边界/时长 |
| `com.matelink.ui.screens.trips.TripRouteRequestBudgetTest` | 新增中间drive失败保留显式空槽，与原有12请求/并行2/取消门共同运行 |
| `com.matelink.data.export.DataExporterContractTest` | CSV真实负kWh仍是数值；JSON未知energy/efficiency必须显式null；连续输出不能覆盖上次内容 |

DataExporter唯一文件测试使用应用隔离临时目录；Config.NONE无FileProvider注册，因此只验证真实写出的文件，不将其视作URI接收方分享实测。

## 额外精确文件申请（已报父，待协调）

- `SavedTripDao.kt`：新增事务验证/插入方法，保持schema不变，用于保存时在同Room事务内复核refs仍存在/未占用与当前owner proof。
- `ExportScreen.kt:175-185`：只让share intent读取被冻结的输出format/MIME。
- `AnnualReportPDFScreen.kt:440-450`：只改每次PDF的独立文件路径，不动绘制、排版或文本。
- `CreateTripScreen.kt:194-196` 与 `TripDetailScreen.kt:595-607`：只修canonical km→mi数值及每100mi费用分母；先经父协调归属。

年度到PDF所选year的NavGraph修复由父/对应agent持有，本组不编辑NavGraph。

## 首轮实现与编译冻结（待父GREEN）

- `SavedTripDao`新增无schema变化的事务验证后插入；`TripRepository.createTrip`在同事务复核全部refs存在/未占用及预览版本未变，before/after owner校验失败回滚。`getTrips`移除按drive指纹删除用户保存记录的cleanup；自动检测插入同样有current fence。
- `CreateTripViewModel`每次以scope+verified vehicle解析负local key；候选/预览/保存和晚结果绑定generation。scope切换清草稿，失败保留当前草稿、清isSaving且不发创建导航。Screen只增加不影响布局的Toast反馈与canonical km预览formatter。
- `TripsViewModel`订阅scope变化，清旧trip/units并阻止旧请求/单位发布；首次与刷新都通过当前proof读取。
- `ExportViewModel`在Room事务冻结同一cohort、override费用与选中币种；年份按本地Instant归属；preview与文件复用同一冻结成员；Uri附实际format，迟到或换scope拒绝发布。CSV/JSON保留原summary字段并明确raw语义，增加nullable qualified指标、raw/effective费用与币种/source/quality字段。真实0和负数保留，JSON null不省略。
- `DataExporter`和PDF使用每次独立临时文件；既有分享路径不被后续生成覆写。CSV仅对文本做公式前缀防护，合法负数不转字符串。
- `TripDetailViewModel`改走`HistoryDetailRepository`的同scope/source/exact window immutable receipt读写；旧只含drive IDs的路线bytes cache不再消费。每drive保留空槽与ID/window/receiptId；只由真实首末drive授权端点。地图按独立segments绘制，样式参数不变；失败段不桥接。
- `TripAggregator`/Timeline按Instant排序；手动显式charge纳入总边界。TripDetail实际literal是`/ 100 $distanceUnit`，只以对应显示距离作费用分母，未修改Stats另一处正确合同。
- AnnualReport按钮回调发送当前year（父接NavGraph）；年度图保留12个月槽，null断线；PDF预览接受合格0/负均耗。PDF绘制布局未变。

首轮未宣称GREEN。本轮先冻结交父编译；随后继续父已批的Trip nullable/coverage与N10共享三态消费者补充，H1/N10在追加验证前保持OPEN。

编译复核记录：父执行`groups-red-green-04`时发现CreateTripViewModel多余闭合括号，Kotlin报`Expecting a top level declaration`，尚未进入测试；本组只删除该多余括号后再次冻结。该轮是编译失败，不计RED/GREEN或业务通过。

父实际复跑`groups-red-green-05`：联合77例/17fail/0error/0skip，其中F6首轮 **16例全部GREEN**：DataExporter3、TripChronology2、ExportScoped1、CreateScoped2、TripBudget5、TripSaved3。包含12新增缺陷与4既有路线预算/取消对照；执行者为父任务，本组未运行Gradle。联合其他分组失败不归为F6通过，也不代表全App通过。

追加测试已准备（尚待父RED）：`TripQualifiedMetricsTest`3、`TripTimelineModeTest`2、TripSavedRecordProtection新增精确保存行身份1；同时只引入默认旧行为的可编译字段/函数seam，未把后续nullable/mode/保存ID行为冒充完成。

## 追加RED与实现冻结

父实际执行`groups-red-green-06`：联合86例/31fail/0error/0skip；本组追加QualifiedMetrics3/2fail、TimelineMode2/1fail、TripSaved4/1fail，精确4个缺陷FAIL、其余5个对照PASS。XML由父备份`groups-red-green-06-xml`；执行角色parent。随后父开放追加GREEN窗口，本组实现如下，现已冻结交`runner07`，**实际追加GREEN尚待结果**。

- 保留`TripAggregator.buildTrip`的纯算式测试合同，另将所有生产TripRepository/Create/Detector入口统一到`buildQualifiedTrip`，消费`toAnalysisDriveData/toAnalysisChargeData`的nullable receipt资格。known distance、duration、charge energy、SOC和max speed不从Room原始0占位推测；真实明确0与负净能量保留。Trip增加known值与sampleCount，保存成员缺失保留expected成员分母；scope实体、原raw receipt和SavedTrip不删除。
- SavedTrip rehydrate带真实savedTripId；findSavedTripId先验证该保存行，旧无ID对象仅在name、drive及charge成员完全匹配且唯一时绑定。列表/Create回调带该ID，详情可选ID参数与旧startDate路由兼容；父持有NavGraph接线。添加/合并/重命名/删除在SavedTripDao事务中复核捕获的owner generation，失败回滚，不靠迟到的裸local Int复核。
- AC/DC消费者复用父提供的`qualifiedChargeMode`。明确DC与明确AC相位分别从当前local key的DAO集合取证；不以“不在DC集合”判AC。Timeline、候选、草稿、详情标签和能量/费用分组保持UNKNOWN；未知模式不分到AC或DC。图形布局与既有已知模式样式不变。
- Trip列表/预览/详情使用known值，缺失保持`—`，部分距离/时长/充入能量保留样本计数；费用/100距离要求完整距离分母，AC/DC平均功率仅用同一组已知能量/时长配对。年度页面与PDF预览/文件在现有值位置附部分记录覆盖，不新加参数格。
- PDF/年度有限真实0/负能耗仍可见；12月槽及null断线保留。原TripWeather位置将新增UNKNOWN映射为中性问号及未知读屏语义，未冒充晴天；天气实际观测、空间/时间估算与车端温度仍分开。

## 审查边界与剩余验收门

- 全部运行由父统一执行；本组没有Gradle、设备、私有数据库、网络或文件分享接收方操作。首轮16例GREEN与追加4例RED均分开记录，不能用它们替代APK/R8/lint/API35或真机可见像素。
- 地图已按每drive独立segments，不跨失败drive画桥，且首末失败不冒用中间点。**单个drive内部GPS缺值/采样空档**仍沿既有点投影与weather插时政策；未宣称已证明整条自然路线完整，也未重建从未采集的轨迹。
- 缺失全部drive证据的SavedTrip可暂时不可投影成Trip，但保存实体/legs仍原样存在；未自动删除或猜测迁移。旧无ID startDate路由保留兼容；新列表/Create都携带保存行ID。
- 年度费用的币种/有效投影来自原Stats snapshot；导出新增raw/qualified字段明确区分，不称可恢复数据库备份，不把未知原始summary 0当已观测指标。文件URI唯一性测试只验证隔离写出文件，接收方URI权限/生命周期和PDF像素仍需父最终验收。
- 当前未更改控件顺序、尺寸、字体、间距、既有颜色或页面布局；仅现有位置的数据、格式、nullable/模式/端点取值和Toast反馈变化。最长未知/覆盖文案的实际视口仍需最终包验收。

追加编译记录：父`runner07`在测试前因TripDetailScreen缺`ChargeSummary/Locale` import失败；本组补齐两import并核验无重复import，未把该轮记为RED/GREEN。同次按父许可将旧startDate兼容查询改`singleOrNull`，同起点多保存记录且没有精确ID时拒绝猜测first；新带ID路径不受影响。父将重跑同batch的`runner08`，结果仍待返回。

## 已取得的追加GREEN（父执行）

父回执`runner09`：F6追加 **QualifiedMetrics 3/3、TripSavedRecordProtection 4/4、TripTimelineMode 2/2，全部GREEN**。原4个确定缺陷RED均已有实际GREEN；首轮其他target维持之前独立GREEN记录，本组不将未重跑集合冒充runner09全量。候选版本为build73 / 2.1.54；无设备安装/生产验收主张。

父最终data-binding自审又修正两处（parent实施，尚待其全量QA）：TripDetail distance label记忆键覆盖distanceSampleCount/expectedDriveCount，避免合计不变但分母变化时显示旧coverage；Trips summary充入量保留1位小数，12.5不舍入成13。仅原位置数据formatter/remember keys变化，不改布局参数。TripDetailVM内部GPS gap集成与route回归已移交parent，本组不再编辑该VM。

`runner07`与随后08属于本轮编译集成过程，其失败不记业务RED/GREEN；07本组缺失imports已修并由实际runner09验证，其他分组的编译/业务结果由父总账负责。最终全量单元/lint/R8/API35和保存/分享/PDF实际可见像素门仍需父固定候选执行。后续内部GPS断档若最小闭环，须单独测试与source冻结，不能用本段GREEN覆盖新修改。
