# 07 我的入口、系统、配置与位置历史审查

基线：原E盘`cdfe0b26`加当前dirty；本轮只读产品源码。覆盖12路由：More、Settings、TariffConfig、TpmsSettings、AmapSetup、AmapPreview、About、DataReadiness、Updates、Vehicle3d、WhereWasI、SentryHistory。以下SOURCE_OK仅指源码路径可追，不代表实际提供方/手机已经验收。

## 逐位置/参数矩阵

记号：`S`为`android/app/src/main/java/com/matelink/ui/screens/`；`L`为`.../data/local/`；`R`为`.../data/repository/`。同一行显式列出多个位置时，它们共用同一源和规则，未将未测字段算作实车通过。

| 页面/展示位置 | 获取/存储及作用域 | 计算与单位 | UI/消费者依据 | 空态/边界及结论 |
|---|---|---|---|---|
| More 数据状态标题/说明 | 静态strings，不读取车辆状态 | 明示统计需要积累历史，无实时成功断言 | S/more/MoreScreen.kt:136；values-zh/strings.xml:301 | SOURCE_OK，说明不是连接实测 |
| More 分析/报告/系统全部入口 | NavGraph传入carId；回调转目标路由 | 无汇总计算；入口不是数据已就绪 | MoreScreen.kt:158–232；NavGraph.kt:1038 | 受01当前车辆绑定P1影响；不能默认ID=1 |
| Settings 账号授权/退出/配对 | TeslaLoginViewModel/SessionStore | 用户/车辆确认状态，不以页面能进替事件成功 | SettingsScreen.kt:145的accountContent、01报告 | 交由01完整审核，不能重复算通过 |
| Settings 连接模式 | ConnectionModeStore、配置推断 | CLOUD/SELF_HOSTED；无车数据数值 | SettingsViewModel.kt:129–146 | SOURCE_OK；云空自托管地址为正常非必填 |
| Settings 主服务器URL | SettingsDataStore | 用户字符串，serverUrlForDisplay、URL校验 | SettingsViewModel.kt:89、135；SettingsScreen服务器区 | SOURCE_OK；不是固定默认服务器即成功 |
| Settings API Token | SettingsDataStore/加密或既有存储合同 | 直接输入、密码样式，不参与统计 | SettingsViewModel.kt:140；SettingsScreen.kt:515 | SOURCE_OK(源码)，不读取/记录真实值 |
| Settings HTTP提示/连接测试/车辆数/首车名 | 显式testConnection请求，ServerTestResult | 连接成功只证明该次请求；显示车数和首名 | SettingsViewModel ServerTestResult:75–86、testConnection | UNVERIFIED真实服务；不等于完整历史或Fleet事件 |
| Settings 语言 | SettingsDataStore.languageCode | 本地配置、重建UI | SettingsViewModel.kt:144、206 | SOURCE_OK |
| Settings 货币 | SettingsDataStore.currencyCode | 只选币种，不等于汇率换算 | SettingsViewModel.kt:142、192；费用投影合同 | SOURCE_OK；金额资格另见02/05 |
| Settings 短行程/短充显示开关 | SettingsDataStore.showShortDrivesCharges | 控制列表过滤，不删除历史 | SettingsViewModel.kt:143、199 | SOURCE_OK，过滤空态不是源丢数据 |
| Settings Mock开关/调试项 | SettingsRepository.mockMode；构建条件 | 模拟来源与正式来源需隔离 | SettingsViewModel.kt:128、223；Screen调试分支 | UNVERIFIED正式候选逐分支实机，不能把模拟数计真实 |
| Settings 同步状态/重同步按钮/成功错误 | 同步服务及本地状态 | 不从本机行数推定上游完整；确认交互后触发 | SettingsScreen.kt:817–831；SettingsViewModel resync | SOURCE_OK(入口)；真实多次刷新待同ID对账 |
| Settings 多实例编辑、旧secondary/basic auth字段 | 源码保留但`if(false)`块不展示 | 不当成当前用户可见功能 | SettingsScreen.kt:368附近 | 不计可见参数缺数据；不得误称可用多实例切换 |
| Tariff 默认单价 | SettingsDataStore.defaultChargePrice | 有限且>=0，单位所选币/kWh | TariffConfigViewModel.kt:58–61；Screen.kt:198–208 | SOURCE_OK输入；费用消费者02/05另验 |
| Tariff 启用峰平谷 | DataStore.tariffEnabled | 开关只保存；生产费用未读取这组字段 | ViewModel.kt:53–55；全main引用索引 | **BUG SYS-01**，开关未打通实际计费 |
| Tariff 峰单价 | DataStore.tariffPeakPrice | Double，无有限/非负检查 | ViewModel.kt:64–66；Screen.kt:373 | **BUG SYS-02**，可保存负/NaN/Inf |
| Tariff 平单价 | DataStore.tariffFlatPrice | 同上 | ViewModel.kt:69–71 | **BUG SYS-02** |
| Tariff 谷单价 | DataStore.tariffValleyPrice | 同上 | ViewModel.kt:74–76 | **BUG SYS-02** |
| Tariff 峰/平/谷时间段及小时数 | 本地JSON数组TimeRange | 包含结束小时；列表分别求和 | TariffConfigScreen.kt:69–108、533–536 | 未校验跨组重叠/完整24小时；预览权重可重复，P2 |
| Tariff 月费用预览/分段/节省 | 用户费率+明确假设50kWh | 小时加权单价×50；比较三价格简单均值，节省下限0 | Screen.kt:532–548、596 | HYPOTHETICAL，明确显示“假设月充电50kWh”，不是用户月账单；SOURCE_OK为演算，不能算真车数据 |
| Tariff 货币符号/重置 | 当前设置加载符号；reset整个UiState为CNY | 重置不修改真实currencyCode | ViewModel.kt:48、94–97 | **BUG SYS-03(P2)**，非CNY用户重置后符号错误 |
| TPMS设置 目标胎压 | DataStore.get/saveTpmsAlertProfile(currentCarId) | bar、TpmsAlertProfile.isValid | SettingsViewModel.kt:131–151、282–305；TpmsSettingsScreen.kt:131 | **BUG SYS-04**：保存远端正ID，监测读localHistoryCarId负ID |
| TPMS设置 低压阈值 | 同一profile | 有限、低于目标；单位bar | SettingsViewModel.kt:285–288；Screen.kt:140 | 验证正确，作用域键同SYS-04 |
| TPMS设置 高压阈值 | 同一profile | 有限、高于目标；单位bar | SettingsViewModel.kt:286–288；Screen.kt:149 | 验证正确，作用域键同SYS-04 |
| TPMS设置 启用/当前车ID/保存成功 | 当前全局SettingsRepository.currentCarId | 成功表示写入成功，不证明worker采用 | SettingsViewModel.kt:299–305；Screen.kt:163 | SYS-04会造成“已保存/启用”但不生效 |
| TPMS变化提醒 开关/变化bar/时间小时 | cached verified context→localHistoryCarId→TpmsChangeRuleStore | candidate.valid后保存同localId | TpmsChangeSettingsCard.kt:23–43 | SOURCE_OK，与上面旧profile键不同；未验证身份时不显示，不能猜读 |
| AmapSetup 配置/隐私/已保存/已验证/重启状态 | AmapSettingsStore + AmapSetupViewModel事务状态 | 枚举状态，无虚假车辆数 | AmapSetupGuideScreen.kt:318–383 | SOURCE_OK源码；真实Key/SDK可用性UNVERIFIED |
| AmapSetup 包名/构建类型/SHA1 | Android安装身份provider | 签名散列/元信息，非车辆测点 | GuideScreen.kt:286–301 | SOURCE_OK获取路线；实际发布签名需候选包复核 |
| AmapSetup Key输入/验证结果/复制项 | 本机保存Key、用户输入/地图回执 | mask/校验/事务结束，不把存在Key当验证成功 | GuideScreen.kt:345–383、440–466；AmapSetupViewModel | UNVERIFIED真实SDK；本审查不读取真实Key |
| AmapPreview 坐标点/标记 | getCars验证身份→snapshot/status→scoped VehicleStatusStore | VehiclePositionResolver有限坐标；不从缺坐标补默认用户位置 | AmapMapViewModel.kt:115–160 | SOURCE_OK资格；离线cold fetch getCars失败时不取已存位置，P2缓存缺口 |
| AmapPreview 历史位置及时间 | getCachedPosition(scope,verified) | 明确positionHistorical及observedAt | AmapMapViewModel.kt:153–160；AmapMapScreen.kt:65–79 | SOURCE_OK标识；UI无坐标明确说明，SDK默认城市不能冒充车位 |
| AmapPreview 加载/成功/失败 | AmapMapView回调+15s保护 | 成功与失败状态，不是观测新鲜度 | AmapMapScreen.kt:38–77 | UNVERIFIED实际SDK/签名/权限；不以编译代替 |
| About 版本/提交 | BuildConfig.VERSION_NAME/GIT_SHA | 字符串 | AboutScreen.kt:162–164 | SOURCE_OK；dirty仍显示HEAD不能唯一证明当前源码 |
| About 隐私/协议/帮助公开链接 | PublicInfoLinks + BuildConfig public base | 白名单URL；未配置有提示 | AboutScreen.kt:110–119 | UNVERIFIED公开页面当前内容/在线可达，不操作用户账号 |
| DataReadiness 已报告/可用/未知数 | 六类服务端readiness items | reported非null；available有限状态；unknown含缺项，分母6 | DataReadinessScreen.kt:119–143 | SOURCE_OK计数；无响应为0已报告、6未知，不伪造车端0 |
| DataReadiness 实时/位置/TPMS/行程/充电/电池健康状态 | readiness six-key白名单、source/lastObservedAt | readinessItemStatus有限枚举、未知fallback | Screen.kt:72–89、310–383 | SOURCE_OK映射；实际每项raw观测UNVERIFIED |
| DataReadiness 授权/车辆/接收/资格/历史/展示诊断 | readiness verified receipt + local evidence session | captures同scope，await后反复current；PASS为已观测/处理非全产品成功 | ViewModel.kt:191–228；ReadinessDiagnosticCards.kt:30–76 | SOURCE_OK证据层级；真实Fleet首事件未获本轮证明 |
| DataReadiness 配对/钥匙/配置同步/最后观测 | getTelemetryPairingStatus + official URL校验 | configured不等于车主钥匙已确认 | Screen.kt:233–300；ViewModel.kt:230–280 | UNVERIFIED车主操作；不得补假就绪 |
| DataReadiness 归档绑定/迁移资格/目标/完成 | migration inspect+verified车辆、里程 | 独立资格和用户确认，无自动生产迁移 | ViewModel.kt:200–221、360以后；Screen.kt:460–530 | 源码可追；本轮不操作迁移/真实数据 |
| Updates 总更新次数 | repository.getUpdates→allUpdates→过滤 | items.size | SoftwareVersionsViewModel.kt:159–184、245；Screen.kt:177 | SOURCE_OK源码，数据缺源时不应承诺完整历史 |
| Updates 平均更新间隔 | 已知start Instant排序 | 相邻间隔毫秒/86400000平均；不足2为null | ViewModel.kt:235–243 | SOURCE_OK公式；无日期不算0天 |
| Updates 最早/最新版本 | 有合法日期的version序列 | first/last，清版本尾部 | ViewModel.kt:238、247–248、269 | SOURCE_OK，缺版本Unknown非观测 |
| Updates 每次版本/安装/结束/升级耗时/驻留天数/当前标识 | UpdateData start/end→projection | 负时长拒绝；最新start至now，其余至下一版本start | ViewModel.kt:202–229；Screen update cards | SOURCE_OK源码；进行中记录是否代表已安装需producer约束，UNVERIFIED |
| Updates 月柱图/日期筛选/最长高亮 | projection选中月份或ALL_TIME | 图表ALL_TIME仍固定最近24个月；列表可更早 | ViewModel.kt:252–265 | P2时间窗/覆盖说明缺口，不能图表当全部历史 |
| WhereWasI 目标时间/活动类型 | target timestamp+该日UnifiedHistory | 包含起止范围找activeDrive/Charge否则推parked | WhereWasIViewModel.kt:90–139 | **BUG SYS-05**身份/代际栅栏缺失；cached-only直接退出导致离线不显示 |
| WhereWasI 经纬度/地图/地点/国家/城市/地址 | detail nearest point或charge位置→geocode | nearest无最大间隔，无逐点资格；异步geocode直接写UI | VM:150–184、187–217、432以后；Screen.kt:198、268–346 | **BUG SYS-05/06**，不能证明精确“当时在此” |
| WhereWasI 里程表/行程距离 | drive.odometerEnd/distance或charge.odometer | 端点值非目标时刻；UnitFormatter使用当前status units | VM:171、175、207、271；Screen.kt:364–396 | SYS-06时点/同响应单位缺证据，缺值— |
| WhereWasI 速度 | 最近detail position.speed | 无插值、无时间距离上限 | VM:157–173；Screen.kt:388–389 | SOURCE_DATA_MISSING/BUG，不能用远时刻点标瞬时速度 |
| WhereWasI 车外温度 | nearest.outsideTemp否则drive/charge均温 | 历史均温不等于目标时刻 | VM:172、208、272；Screen.kt:372 | SYS-06应区分采样/摘要，不冒充当时观测 |
| WhereWasI 充电SOC/功率 | nearest charge point；SOC回退startBatteryLevel | 起点SOC非目标时刻；power无点则— | VM:197–210；Screen.kt:406–413 | SYS-06；缺采样不能倒填 |
| WhereWasI 驻车时长/开始/上一活动入口 | 最近过去活动；没找到则用未来firstOrNull | target-last.end，未来分支把endTime设target，得到0 | VM:239–279、331–347；Screen.kt:420–438 | SYS-06，缺边界应未知而非确定停在未来起点 |
| WhereWasI 天气温度/图标/描述 | OpenMeteo archive hourly API | 仅按hour匹配；缺weatherCode默认0晴，API未传timezone | VM:446–475；OpenMeteoApi.kt:11–16；Screen.kt:458–483 | **BUG SYS-07(P2)**错小时/缺码晴；外部服务可用UNVERIFIED |
| Sentry 当前活动/开始时间/本次事件/过去事件/时间/地点 | localHistoryCarId下StateDataStore+Room AlertLog，App轮询观测 | 65s去重；detectedAt为App观察时间，不是车机原始事件时间 | SentryStateRepository.kt:58–107；SentryHistoryViewModel.kt:122–153 | SOURCE_OK读取；SOURCE_DATA_MISSING：App未观察到的历史不会凭空补齐 |
| Sentry 热力格/日期/小时提示/点击定位 | DAO按epoch小时计数→6日×12个2h桶 | counts加总；本地日零点起点；tooltip同2h索引 | VM:132–151；Screen.kt:338–409；DAO:29–34 | SOURCE_OK基本公式；DST跨天为P2边界；无采样0格仅代表本机无事件 |
| Sentry 当前session清零/结束 | Worker status.sentryMode ?: false | 缺字段被当明确关闭，resetSession | ChargingNotificationWorker.kt:247；SentryStateRepository.kt:69–76 | **BUG SYS-08(P1)**，缺测不能重置已有活动状态 |
| Vehicle3d 车身/门/充电口/前备箱 | remember本地bool+固定wireframe，carId未使用 | 手势仅局部状态 | Vehicle3dScreen.kt:28–45、画布/按钮 | **BUG SYS-09(P2)**展示为“车辆控制”但没有真实车辆链路；演示需明确，不属于真实车况 |

## 可操作发现

### SYS-01 [P1] 分时电价配置没有实际费用消费者

全main精确检索`tariffEnabled/tariffPeakPrice/...Ranges`只有SettingsDataStore与TariffConfigViewModel。实际费用`projectEffectiveChargeCost`只收默认单价，没有峰平谷时间和分段输入；开启开关改变预览，但不改变真实充电列表/详情/统计。UI说明明确“启用峰/平/谷电价计算充电费用”（values-zh:1370）。需要接入具备同会话真实时间/能量片段的费用投影，证据不足仍未知/明确估算；不得把50kWh预览结果当用户账单。

### SYS-02/03 [P2] 峰平谷价格校验及重置币种

`TariffConfigScreen.kt:373`允许Double解析后直接回调，VM:64–76未检查有限或非负，DataStore.saveTariffConfig:428–434直接保存。负值/NaN/Infinity可进预览与持久化。另VM:95重置整个UiState恢复默认CNY符号，却没有改变实际用户currencyCode。修复应约束输入和保存，并保留当前币种，不改布局。

### SYS-04 [P1] TPMS目标/上下限保存键和监测读取键不一致

SettingsViewModel:131/299使用`SettingsRepository.currentCarId`远端正ID；TpmsPressureWorker:278/294使用已验证`vehicle.localHistoryCarId`负ID读取同一profile。SettingsDataStore:441/448按精确Int索引，没有正负键映射。用户界面“已保存/已启用”不代表监测采用。需通过同scope verified context保存/读取，保留旧记录不猜迁移。`TpmsChangeSettingsCard`新变化规则已正确映射localId，不应误判它也有同一缺陷。

### SYS-05 [P1] WhereWasI缺身份/代际守护，迟到位置/天气可发布旧账号数据

VM仅`loaded`布尔(82–86)，取得history后没有验证`verifiedReadContext`或订阅scope；detail/status/geocode/weather多个await后直接写UI(166/202/266/435/474)。切账号/来源/同数ID后旧页面和迟到请求可继续显示旧车位置。需复用verified identity/request generation，失败/取消/换身份正确清屏；不能仅在初始history API中验证一次。

### SYS-06 [P1/P2] WhereWasI展示目标时刻而消费端点、无界nearest与未来活动

VM:157–175给最近采样但不限定其距target的间隔，且里程直接用行程终点；VM:209可用充电开始SOC；VM:341–347选未来firstOrNull并把该活动时间伪设target。不能解释为“准确当时状态”；需要有界样本、明确最近观测时间，缺证据保留未知。历史状态单位应从同receipt取得，不能借今天status的units。

### SYS-07 [P2] 历史天气时区/缺码

OpenMeteoApi:11–16不传timezone，VM只以targetTime.hour选择hourly值，并且缺weatherCode默认0；Screen显示晴。应以目标Instant和响应时区匹配完整时刻，缺码未知。此处未联网断言提供方当前行为，服务默认时区须另以官方合同核实。

### SYS-08 [P1] 缺sentry_mode被当关闭并结束session

Worker:247的`?: false`会把部分status缺字段转为明确off；SentryStateRepository:69–76据此resetSession。需只在明确可信bool观测时转移状态，未知不删除已有session。事件页展示的是本机采样告警，不应保证完整车机历史。

### SYS-09 [P2] 3D“车辆控制”是未接真实数据的本地演示

`carId`未被使用，三个车辆状态是本地remember布尔，固定wireframe无真实车型/接口。More当前未列出该入口，但NavGraph:989路由存在。应在上线范围声明未开放/演示，不能计真实车辆链路已通。任何入口/文案调整需Jovi授权，当前不改布局。

## 冻结快照的可达性修正

独立复核09确认：当前没有发现`Screen.WhereWasI`导航构造/点击入口，通知allowlist和deep link亦未提供该路由；Vehicle3d也未在当前More列入口。SYS-05/06/07及SYS-09保留为注册但未开放实现问题，不计当前可达页面的上线P1。矩阵仍覆盖其注册实现，不能把潜伏代码当成用户已看到的现象。

SYS-08的null→false→resetSession只在Worker实际执行到该分支时成立（已配置serverUrl的selfhost/保留非空配置等）。纯cloud空serverUrl会更早被Settings.isConfigured挡住，另构成采集路径缺口：ChargingNotificationWorker:131–135不请求status，哨兵观察不能据此保证完整。不得把这两种触发混为一谈。

## 验证层级与未验门

本组只执行源码读取/精确引用检索，未运行产品测试或访问设备/提供方。可用测试源码包括SettingsViewModelTest、SettingsExperienceContractTest、AmapSetupWizardStepTest、DataReadinessReviewContractTest、ReadinessDiagnosticBehaviorTest、SoftwareVersionsProjectionTest、SentryHistoryIdentityConsumerTest；其存在不证明本组新缺陷已被覆盖。上线前至少需要新候选同身份切换/离线/部分status/TPMS配置到worker真实消费/分时费用场景回归和实际设备核对。
