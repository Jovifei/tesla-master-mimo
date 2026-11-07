# 09 独立反证复核

日期：2026-10-26。复核者：`/root/audit_cross_review`。唯一工作根：`E:\project\_worktrees\matelink-release-data-audit-20261026`；唯一写入文件为本文。按父任务要求不使用共享 Git 元数据，不改 `core.worktree`。`isolation.json` 记录父任务稳定复制954文件；原 `cdfe0b26+dirty` 是原读取基线，不能当冻结树的单一提交身份。

已阅读01、02、03、06、07的相关发现/链路及08验证记录，按指定的九项关键声明独立追查实际生产源码。图工具由父任务确认不可用，采用限定 `rg` 和带行号读取。**本复核没有运行测试、构建、设备、网络或导出，没有读取私人数据库/文件，没有修改产品代码。** 08中11项审查断言（8失败、3对照通过）由父任务在原目录实际执行；这里仅引用其记录，不认领为本人的测试结果，不将其冒充954文件冻结目录的再次执行。

下文源码位置均相对上述冻结根。`CONFIRMED_SOURCE` 只表示分支与触发条件可确认；生产发生频率、提供方自然数据、手机行为和文件实际接收均保持 `UNVERIFIED`。

## 复核结果

| 原编号 | 独立结论 | 必须保留的边界 |
|---|---|---|
| 01 H01 首车选择未写回 | CONFIRMED_SOURCE，保留P1可用性缺陷 | 首页确有当前 `/cars` membership检查；不能把此项描述成已证明越权显示别人的车 |
| 06 N01 CreateTrip/Export远端ID直读Room | CONFIRMED_SOURCE，保留P1 | scoped库正常使用负local ID；无旧正ID行时是空/不可见，读旧命名空间必须以确有legacy行为前提 |
| 06 N06 浏览删除同drive保存旅途 | CONFIRMED_SOURCE，条件性P1数据保留风险 | 必须已有至少两个相同drive指纹的SavedTrip；当前自动检测已排除占用legs，不能声称每次浏览都会制造并删除正常新旅途 |
| 06 N03 年度→PDF年份 | 确认路由缺陷，建议按P2收口 | PDF先打开有年份标题/可改年份的预览，另点生成才写文件；不能写成点年度按钮即无提示自动导出错年文件 |
| 06 N07 CSV/JSON费用 | 确认输出差异/缺币种；P1严重性需发布合同支持，建议P2 | 代码导出raw `cost`；未找到界面承诺“当前有效费用账本/完整备份”，也没有导出总费用公式；不能宣称导出已混币种求和或抹掉原账单 |
| 07 SYS-01 峰平谷无消费者 | CONFIRMED_SOURCE，保留已开放功能不生效问题 | 50kWh区块明确是假设演算，不是虚构用户账单；需生效的是实际费用估算入口，不能要求覆盖有效源账单 |
| 07 SYS-04 TPMS保存/读取键 | CONFIRMED_SOURCE，保留P1 | 影响旧自定义目标/阈值profile；新变化规则使用local key，不能一并判错 |
| 07 SYS-05 WhereWasI身份栅栏 | 潜伏缺陷，移出当前可达P1 | 当前仅注册路由，没有生产导航/通知/deep-link入口；SYS-06/07同样按未开放处理 |
| 07 SYS-08 Sentry null→false | CONFIRMED_SOURCE，条件性P1；另有采集门缺口 | null-reset需要Worker已通过非空server gate并已有active session；纯云空server在此前整体退出，是另一条采集缺口 |

本表是指定关键声明的复核结果，不是全审计问题总数；最终优先级由父任务结合发布合同统一去重。另确认 `Vehicle3d` 与WhereWasI同属注册但未开放入口；03 D11按冻结附录保持历史/当前不可达，不恢复成当前地图阻断。

## 独立源码证据与最小非布局建议

### H01：确认首车失败，收窄“ownership”措辞

`data/repository/SettingsRepository.kt:23,31,45-46` 以全局 `current_car_id` 存整数并默认1。`ui/screens/auth/TeslaLoginViewModel.kt:627-633` 在旧ID不匹配时选择当前车单first用于配对，但不写回。全main文本搜索 `setCurrentCarId` 仅有定义与 `DashboardViewModel.kt:559` 调用，后者的 `switchCar:557` 没有生产调用者。`ui/navigation/MateLinkNavHost.kt:80-86` 将该旧ID带入Drives/Charges/More。

触发不依赖臆测提供方恒返回1：`deploy/jourvolt-dev-mock/main.go:101-104` 的车辆表为全表自增identity，`fleet_provider.go:204-206` 返回 `stored.ID`。合成前提“selected=1、当前车单只有2”可确定到 `DashboardViewModel.kt:285-305` 的 `vehicle_not_found`。但这里 **存在** `cars.find { it.carId == carId }` 与失败提前返回，因此根因是选择一致性和恢复能力，不能推导已显示跨账号车辆。

最小改法：在同scope/generation核准的车单中修复无效selection并持久化，导航读取同一新选择；身份选择按origin/account/UID绑定。复用现有界面，不擅增多车布局。缺失当前ownership时继续fail closed。

### N01：生产路由传remote ID，两个VM没有映射

`NavGraph.kt:890-903,980-984` 原样把路由 `carId` 传CreateTrip和Export，`CreateTripScreen.kt:85` 的启动及 `ExportScreen.kt:34-35` 的init可达。Create VM `:56-69` 保存该整数，`:96,142,148` 分别读候选、写SavedTrip、解析草稿，未捕获identity proof。Export VM `:47-50,90-101,124-148` 以相同整数读DAO、生成并发布URI；`:56` 的年份却经StatsRepository。`StatsRepository.kt:239-244` 明确先解析context，`VehicleContextStore.kt:116-118` 本地ID分配为负数，证明remote与local不是可互换参数。

所以年度列表有年份但导出计数/文件为空，是不需要legacy的确定分支；legacy正ID行存在时才可能读到未链接旧命名空间。两个VM也没有scope collector或发布前currentness fence。最小改法是将一次请求的scope、verified vehicle、local key与generation冻结，并在候选/保存/导出/发布处复核，失效清草稿和旧URI；不能靠最近裸Int映射放宽proof。

### N06：条件性删除真实存在，但不是当前检测器必然生产重复

真实读入口 `TripsViewModel.kt:125` → `TripsHistoryRefresher.kt:38` → `TripRepository.getTrips:45-54`；后者无用户删除操作即调用cleanup。`TripRepository.kt:78-89,100-104,496-497` 仅以排序drive IDs分组，按source/name/id排名后 `deleteTrip`，没有比较charge legs、名字相等、成员顺序、时间或来源证据。`SavedTripDao.kt:48-49` 真正删除保存实体；`SavedTrip.kt:18-29` 仅carId普通索引，无唯一成员约束。

这能确认：若旧库中两条USER_EDITED旅途共享drives而charge/名称不同，次优行会被删除，用户元数据未合并保存。必须同时说明 `TripRepository.kt:448-456` 当前检测器已排除占用的drive/charge，`getUnusedLegsAround:308-319` 也过滤已用legs；本轮不证明正常顺序创建会产生这种重复，更没有触发真实用户库。

最小改法：浏览路径不删除非完全相同的用户保存实体；历史beta清理须限定可证明的AUTO重复并保护所有差异字段，另行制定恢复/迁移。不能把source排名视为用户对删另一条记录的授权。

### N03：路由年份丢失；PDF自身冻结门仍有效

`AnnualReportScreen.kt:79-82` 改VM year，`:123` 调不带参数callback；`NavGraph.kt:965-966` 却使用进入年度页时的 `route.year`。因此选2025后可打开初始2026的PDF页。反证边界：`AnnualReportPDFScreen.kt:77,99-109,120,126` 明示当前年且可重选；`:169-170` 另一次点击才调用生成。`AnnualReportPDFViewModel.kt:183-201` 用同一冻结snapshot并在写后复核identity，未见生成过程中混合两个年度的证据。

最小改法：callback传当前UI year并用于目标路由。建议将此列为明确P2导航错误；如发布合同将该按钮定义为直接生成所选年度报告，可由父任务保留更高优先级，但不得扩大实际执行事实。

### N07与输出路径：raw费用差异确认，导出语义不要扩大

`DataExporter.kt:94-96,131-137` 输出Room `c.cost`，CSV header为Cost、JSON key为cost，无币种、费用状态或override。`ExportViewModel.kt:132-145` 只传ChargeSummary列表；`ChargeSummary.kt:41-42,54-55` 将raw cost与apiEvidence分开保存。对照实际有效投影 `ChargeCostProjection.kt:86-95` 的手动金额优先、`:100-115` 的币种/估算门，以及 `StatsRepository.kt:133-137`，确定手动更正0和raw12可以在两条输出链上不同。

没有找到导出界面宣称“有效账本”或“完整可恢复备份”（`ExportScreen.kt:81-98,140-159`；values-zh `export_*:1488-1503`），也没有导出金额求和。故确认“输出字段语义/币种不充分”，不称“账本已被修改/混币种求和”。最小改法是在同一冻结请求中保留明确raw字段，并按已批准导出合同提供有效cost、currency与状态；原receipt不覆盖。没有既定合同前，不默认新增大套source/quality/method导出功能为本轮必修范围。

实际源码输出路径为 **应用cache目录**（没有真实生成文件）：CSV/JSON `DataExporter.kt:51-53` → `<cacheDir>/matelink_export_<epochSeconds>.<csv|json>`；PDF `AnnualReportPDFScreen.kt:440-450` → `<cacheDir>/matelink_annual_report_<year>.pdf`。FileProvider `${packageName}.fileprovider`（DataExporter `:60-63`；PDF Screen `:54-62`），`res/xml/file_paths.xml:3` 允许cache path。PDF同年delete+重写、普通导出秒级名称确有路径复用风险；VM冻结数据不能保证旧URI内容不被以后文件覆写。最小改法为每次唯一文件路径并一同返回实际MIME，不把它误说成已知文件泄露或已观察分享失败。

### SYS-01：分时配置只到预览；不要把假设预览列为虚构观测

全 `android/app/src/main` 对 `tariffEnabled/PeakPrice/FlatPrice/ValleyPrice/*Ranges` 的引用只有SettingsDataStore与TariffConfigViewModel。`TariffConfigScreen.kt:144-159,226-237` 确有可达开关/配置，Settings入口 `SettingsScreen.kt:688` → `NavGraph.kt:553-554,587-590`。实际费用函数 `ChargeCostProjection.kt:74-81,112-115` 只收defaultPricePerKwh，没有分时输入，故改峰谷不会改变实际估算。

07引用的 `tariff_configuration_hint` 字符串1370未找到生产调用，应以确实渲染的 `tariff_enable_hint`（Screen `:231`，values-zh `:350` “启用峰、平、谷三时段计费”）为界面承诺证据。`Screen.kt:532-544,597` 明示50kWh假设与小时权重，不把它描述为用户月度账单。最小改法是让已开放配置按同会话已证明时间/能量或明确估算政策进入现有费用投影，不能凭空制造分时能量，也不覆盖有效提供方账单。

### SYS-04：精确键不一致确认，影响范围限定旧profile

Settings VM `:131-132,147,298-300` 从全局remote currentCarId读取并保存profile；`SettingsDataStore.kt:438-449` 按精确Int索引，未映射；Worker `TpmsPressureWorker.kt:278,294,308-310` 用verified localHistoryCarId加载并将profile交给真实提醒处理。cloud `:196-199` 和selfhost `:227-232` 两条入口均到该消费者。新增local key与正remote key确定不同。

08父测试用真实DataStore按正/负键调用返回null，是支持证据；它没有直接执行Worker。最小改法：UI也绑定同scope verified local key；保留旧键记录，不猜迁移。`TpmsChangeSettingsCard` 是另一套已按local key保存的变化规则，不能把所有TPMS提醒归为失效。

### SYS-05/06/07、SYS-09：潜伏问题不计当前可达阻断

全main限定检索 `WhereWasI` 只有 `NavGraph.kt:185` 声明与`:862-864`注册及Screen/VM，无构造目标路由的生产调用；`notificationScreen:269-288` 无该类型，无 `navDeepLink` 入口。Timeline `TimelineScreen.kt:105-106` 仅呈现卡片，不转此页。Vehicle3d也只有NavGraph `:988-990` 注册，无navigate调用；More最终绑定 `NavGraph.kt:1037-1057` 未暴露这两个功能。

VM `WhereWasIViewModel.kt:82-86,105-139,166-184,202-217,425-456` 的缺scope/迟到发布问题成立，但仅是尚未开放代码的风险。SYS-06的时点匹配和SYS-07天气时区/缺码同样不计当前用户可达P1/P2。注册路由覆盖数不能称“同数目可达页面已验收”。最小建议是保持未开放状态；重新开放前补identity、bounded样本与时区门。本轮不补入口、不改布局。

### SYS-08：区分未知误清零与纯云采集提前退出

**分支A——条件性null-reset：** `ChargingNotificationWorker.kt:247-250` 将nullable sentryMode转false，`SentryStateRepository.kt:66-76` 对active session执行reset，`SentryStateDataStore.kt:84-89` 真正把active/count/lastEvent/sessionStart清零。不会删除Room过去的AlertLog，不能声称历史事件表被删。nullable输入不是凭空构造：`CarModels.kt:160,179` 保留null；后端 `fleet_provider.go:309` 保留指针，`main.go:864` 原样输出，未发现生产者保证每份partial status都带bool。触发还必须有既存active session且Worker实际进入该段。

**分支B——Cloud blank server采集门：** `SettingsDataStore.kt:73-74` 的isConfigured只检查serverUrl非空；Worker `:131-135` 因空值返回，早于getCars `:140`、充电处理`:217-243` 与Sentry `:246-250`。纯云端空server属于项目正常设置（Settings VM `:129-136`），因此不是分支A的复现前提，而是Worker整体不采Sentry/不启动这条充电通知处理链的源码缺口。全main唯一真实 `processStatus` 调用为Worker `:250`；Settings VM `:559` 是debug force increment，widget只读event count，不能补上采集。`ChargingMonitorService.start` 生产调用也只有Worker `:221,236`。启动来源 `BootReceiver.kt:21-28` 在开机调度；`MateLinkApplication.kt:27-40` 当前只调度TPMS，不证明首次安装已调起此Worker。以上不推断手机已有WorkManager队列或通知权限状态。

最小改法：以当前连接模式/认证状态定义Worker可执行资格，保护cloud不因旧server字段被跳过，并按既定云后台采集政策处理；状态转换仅接受明确可信bool，unknown保留session。两分支分别验证，不能以绕过网络/身份门或后台无条件唤车来修复。

## 去重与交付边界

- N01与SYS-04共享“remote key/local history key”模式，但一个是数据读写/分享，另一个是配置消费者，修复需覆盖各真实调用者；不可用同一helper通过代替全部消费者通过。
- Trip/Export primitive占位沿03 D02的nullable receipt根因归并；N07另限费用导出语义。WhereWasI未开放，不把它重复加到当前身份P1总数。
- N06为保存实体删失；N03为进入预览年份；N16为文件路径身份。这三个故障层不同，不合并成一个“报告数据错”。
- 03冻结附录的D11是当前Screen不消费坐标的历史缺陷；移除地图不构成数据修复或视觉验收通过，本轮不改其布局。

复核清单：九项指定声明均完成源码反证；可达性、producer nullable义务、scope键、写入/输出路径、已有保护与触发边界已记录。收口时按 `isolated-source.json` 逐文件重算SHA256，记录的954文件均无drift；这是文件一致性校验，不是业务测试。父任务后续集中补真实消费者回归与同冻结版本设备/持久化证据；本文不关闭任何未执行门。
